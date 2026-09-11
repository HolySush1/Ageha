package app.ageha.core.jvmcontext

import app.ageha.core.js.JsRuntime
import app.ageha.core.model.JsCapability
import app.ageha.core.network.PersistentCookieJar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Gets a request past a Cloudflare check by passing the check in the browser component, then asks
 * again with the cookie that earned.
 *
 * ## Why this exists
 *
 * On Android a Cloudflare check passed in a WebView lets the whole app through, because the
 * WebView's cookie store is the app's. Ageha's browser keeps its own, so a check passed there used
 * to let the browser in and nothing else -- and most sources never ask the browser anything: they
 * make an ordinary HTTP request, get Cloudflare's interstitial back, and fail. ComicK is the case
 * that forced this: its search calls `/api/search`, Cloudflare answers 403, and the parser catches
 * that and falls back to an HTML scraper that upstream never finished and that returns an empty
 * list whatever happens. The only way ComicK search works is for that first request to get
 * through, which this makes happen.
 *
 * ## Why it sits here
 *
 * Innermost among the context's interceptors, after `ParserDispatchInterceptor`, so it sees the
 * request exactly as it will go out -- the user agent in particular, which the clearance is bound
 * to. And it answers before the parser sees the response, which matters because parsers routinely
 * swallow the failure themselves: by the time a failure surfaced, the parser would already have
 * taken its fallback.
 *
 * Without the browser component nothing changes: the challenge goes back to the parser exactly as
 * it always did.
 */
internal class CloudflareClearanceInterceptor(
	private val jsRuntime: JsRuntime,
	private val cookieJar: PersistentCookieJar,
	private val clock: () -> Long = System::currentTimeMillis,
) : Interceptor {

	/**
	 * One clearance per host at a time. A source loading a page fires several requests at once --
	 * listing, cover, chapters -- and without this each would open its own browser for the same
	 * check. The ones that wait find the cookie already there and simply retry.
	 */
	private val locks = ConcurrentHashMap<String, Mutex>()

	/**
	 * Hosts that failed recently, and until when they are left alone.
	 *
	 * Upstream's `FAILURE_COOLDOWN_MS`, for upstream's reason: a check that did not clear will not
	 * clear for the next request either, and a burst of requests would otherwise queue a full
	 * attempt each -- a window reappearing over and over for a site that has already said no.
	 */
	private val coolingUntil = ConcurrentHashMap<String, Long>()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		if (!isChallenge(response)) return response
		if (JsCapability.INTERACTIVE_BROWSER !in jsRuntime.capabilities) return response
		val url = request.url
		if (isCoolingDown(url.host)) return response

		val before = clearanceFor(url)
		val cleared = try {
			runBlocking { clear(chain, url, request.header(USER_AGENT), before) }
		} catch (e: CancellationException) {
			// The call this was for was cancelled -- the user navigated away. OkHttp lets nothing
			// but an IOException out of an interceptor.
			response.close()
			throw IOException("Canceled while clearing a Cloudflare check at $url", e)
		}
		if (!cleared) return response

		response.close()
		val retried = chain.proceed(request)
		// Cleared in the browser and still refused here means the site wants more than the cookie
		// -- a TLS fingerprint OkHttp cannot present. Trying again for every request would only
		// reopen the browser for the same answer.
		if (isChallenge(retried)) coolingUntil[url.host] = clock() + COOLDOWN_MILLIS
		return retried
	}

	private suspend fun clear(
		chain: Interceptor.Chain,
		url: HttpUrl,
		userAgent: String?,
		before: String?,
	): Boolean = coroutineScope {
		// OkHttp signals cancellation by flag, not by coroutine, so it is polled for. Without this a
		// user who left the screen would still get a browser window a few seconds later.
		//
		// The scope is cancelled, not the watcher: a child that merely throws CancellationException
		// ends itself quietly and leaves its siblings running -- which is what the first version
		// did, and the browser went on waiting for a request nobody wanted any more.
		val clearing = this
		val watcher = launch {
			while (isActive) {
				if (chain.call().isCanceled()) clearing.cancel(CancellationException("call cancelled"))
				delay(CANCEL_POLL_MILLIS)
			}
		}
		try {
			locks.getOrPut(url.host) { Mutex() }.withLock {
				val now = clearanceFor(url)
				when {
					// Another request cleared this host while this one waited for the lock.
					now != null && now != before -> true
					isCoolingDown(url.host) -> false
					else -> passInBrowser(url, userAgent)
				}
			}
		} finally {
			watcher.cancel()
		}
	}

	private suspend fun passInBrowser(url: HttpUrl, userAgent: String?): Boolean {
		val cookies = try {
			jsRuntime.openInteractive(url.toString(), userAgent)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			// The browser refused or failed to start. Leave the challenge for the parser, as it
			// was before there was a browser to ask.
			null
		}
		val saved = cookies?.let { cookieJar.saveBrowserCookies(url.toString(), it) } ?: 0
		if (saved == 0) coolingUntil[url.host] = clock() + COOLDOWN_MILLIS
		return saved > 0
	}

	private fun isCoolingDown(host: String): Boolean = (coolingUntil[host] ?: 0L) > clock()

	private fun clearanceFor(url: HttpUrl): String? =
		cookieJar.loadForRequest(url).firstOrNull { it.name == CLEARANCE_COOKIE }?.value

	private companion object {
		const val USER_AGENT = "User-Agent"
		const val CLEARANCE_COOKIE = "cf_clearance"
		const val COOLDOWN_MILLIS = 30_000L
		const val CANCEL_POLL_MILLIS = 250L

		/**
		 * The parsers library's own test, so Ageha and the parsers agree on what a Cloudflare
		 * interstitial looks like. It reads the body with `peekBody`, so the response is still whole
		 * when it goes back to the parser.
		 *
		 * A captcha-style check only. An outright block ("you have been blocked") is not a check a
		 * browser can pass, and opening one for it would be a window with nothing to do.
		 */
		fun isChallenge(response: Response): Boolean =
			CloudFlareHelper.checkResponseForProtection(response) == CloudFlareHelper.PROTECTION_CAPTCHA
	}
}
