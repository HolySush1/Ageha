package app.ageha.core.jvmcontext

import app.ageha.core.js.InterceptedHttpRequest
import app.ageha.core.js.JsRuntime
import app.ageha.core.model.BrowserCookie
import app.ageha.core.model.JsCapability
import app.ageha.core.network.PersistentCookieJar
import app.ageha.core.network.UserAgents
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Cloudflare hand-off, without Cloudflare or a browser.
 *
 * The "site" is an interceptor that serves Cloudflare's interstitial until the jar holds a
 * clearance, and the "browser" hands one back when asked. What is under test is everything Ageha
 * decides between the two: when to ask, how often, with what, and what the parser gets back.
 */
class CloudflareClearanceInterceptorTest {

	private val url = "https://comick.example/api/search?q=overlord"

	private var now = 1_000_000L

	private fun jar(dir: File) = PersistentCookieJar(File(dir, "cookies.json"))

	/** A browser that earns [cookies] when asked, and counts how often it was. */
	private class FakeBrowser(
		var available: Boolean = true,
		var cookies: suspend () -> List<BrowserCookie>? = { listOf(clearance()) },
	) : JsRuntime {
		val opened = AtomicInteger()

		@Volatile
		var userAgent: String? = null

		override val capabilities: Set<JsCapability>
			get() = if (available) setOf(JsCapability.INTERACTIVE_BROWSER) else emptySet()
		override val browserUserAgent: String? = null

		override suspend fun openInteractive(url: String, userAgent: String?): List<BrowserCookie>? {
			opened.incrementAndGet()
			this.userAgent = userAgent
			return cookies()
		}

		override suspend fun evaluate(script: String): String? = error("not used")
		override suspend fun evaluateInPage(baseUrl: String, script: String, timeoutMillis: Long): String? =
			error("not used")

		override suspend fun interceptRequests(
			pageUrl: String,
			filterScript: String?,
			pageScript: String?,
			maxRequests: Int,
			timeoutMillis: Long,
			urlPattern: Regex?,
		): List<InterceptedHttpRequest> = error("not used")

		override suspend fun close() = Unit
	}

	/**
	 * Cloudflare's interstitial until the jar holds a clearance -- or forever, when [stubborn].
	 *
	 * Reads the jar directly rather than a Cookie header, because it sits among the application
	 * interceptors, which run before OkHttp attaches cookies.
	 */
	private class FakeSite(private val jar: PersistentCookieJar, private val stubborn: Boolean = false) : Interceptor {
		val served = AtomicInteger()

		/** The agent of the most recent request, for checking what the retry presented. */
		@Volatile
		var lastUserAgent: String? = null
			private set

		override fun intercept(chain: Interceptor.Chain): Response {
			served.incrementAndGet()
			val request = chain.request()
			lastUserAgent = request.header("User-Agent")
			val cleared = !stubborn && jar.loadForRequest(request.url).any { it.name == "cf_clearance" }
			val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
			return if (cleared) {
				builder.code(200).message("OK")
					.body("""{"data":[]}""".toResponseBody("application/json".toMediaType()))
					.build()
			} else {
				builder.code(403).message("Forbidden").header("Server", "cloudflare")
					.body(CHALLENGE.toResponseBody("text/html".toMediaType()))
					.build()
			}
		}
	}

	private fun client(browser: JsRuntime, jar: PersistentCookieJar, site: FakeSite) = OkHttpClient.Builder()
		.cookieJar(jar)
		.addInterceptor(CloudflareClearanceInterceptor(browser, jar, clock = { now }))
		.addInterceptor(site)
		.build()

	private fun OkHttpClient.get(userAgent: String = "TestAgent/1"): Response =
		newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute()

	@Test
	@DisplayName("a challenge is passed in the browser and the request asked again with its cookie")
	fun clearsAndRetries(@TempDir dir: File) {
		val jar = jar(dir)
		val browser = FakeBrowser()
		val site = FakeSite(jar)

		client(browser, jar, site).get(UserAgents.CHROME_DESKTOP).use { response ->
			assertEquals(200, response.code)
		}
		assertEquals(1, browser.opened.get())
		// The clearance is bound to the user agent that earned it, so the browser must present the
		// one the request will be retried with. An agent that already agrees with the engine about
		// the platform and the Chrome major is presented exactly as it arrived.
		assertEquals(UserAgents.CHROME_DESKTOP, browser.userAgent)
		assertEquals(2, site.served.get(), "one challenged request, one retry")
	}

	/**
	 * Ported from the Android app's `CloudFlareActivity.alignUserAgentWithEngine`.
	 *
	 * Several parsers hard-code a desktop User-Agent as their `ConfigKey.UserAgent` default --
	 * HotComics ships `X11; Linux x86_64 ... Chrome/114` -- and presenting that to a challenge makes
	 * everything the challenge measures contradict it: the platform, `navigator.userAgentData`, the
	 * WebGL renderer all report the real engine. That contradiction is the signature anti-bot checks
	 * look for, so the check fails and reloads for ever.
	 *
	 * Ageha was accidentally immune while its own headers ran above the parser's. 0.3.7 put the
	 * parser's back in front, which was right in every other respect and opened this up.
	 */
	@Test
	@DisplayName("an agent that contradicts the engine is realigned before the check is attempted")
	fun contradictoryAgentIsRealigned(@TempDir dir: File) {
		val jar = jar(dir)
		val browser = FakeBrowser()
		val site = FakeSite(jar)

		val parserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
			"(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
		client(browser, jar, site).get(parserAgent).use { response ->
			assertEquals(200, response.code)
		}

		assertEquals(
			UserAgents.CHROME_DESKTOP,
			browser.userAgent,
			"a Linux Chrome 114 cannot be presented by a Windows Chrome 146 engine",
		)
		assertNotEquals(parserAgent, browser.userAgent)
	}

	@Test
	@DisplayName("the retry presents whichever agent earned the clearance, not the one that asked")
	fun retryPresentsTheClearingAgent(@TempDir dir: File) {
		val jar = jar(dir)
		val browser = FakeBrowser()
		val site = FakeSite(jar)

		val parserAgent = "Mozilla/5.0 (X11; Linux x86_64) Chrome/114.0.0.0 Safari/537.36"
		client(browser, jar, site).get(parserAgent).use { it.body.string() }

		// A cookie is worth nothing presented by anybody else, so the retry has to carry the agent
		// the browser actually used rather than the one the parser wrote.
		assertEquals(UserAgents.CHROME_DESKTOP, site.lastUserAgent)
	}

	@Test
	@DisplayName("without the browser component the challenge reaches the parser untouched")
	fun noBrowserNoChange(@TempDir dir: File) {
		val jar = jar(dir)
		val browser = FakeBrowser(available = false)

		client(browser, jar, FakeSite(jar)).get().use { response ->
			assertEquals(403, response.code)
			// Whole, so the parser can still read the interstitial and decide for itself.
			assertTrue(response.body.string().contains("challenge-error-text"))
		}
		assertEquals(0, browser.opened.get())
	}

	/** A page load fires several requests at once; one window must serve them all. */
	@Test
	@DisplayName("requests racing into the same challenge share one clearance")
	fun concurrentRequestsShareOneClearance(@TempDir dir: File) {
		val jar = jar(dir)
		val browser = FakeBrowser(cookies = {
			delay(300)
			listOf(clearance())
		})
		val client = client(browser, jar, FakeSite(jar))
		val pool = Executors.newFixedThreadPool(5)
		try {
			val codes = (1..5).map { pool.submit<Int> { client.get().use { it.code } } }.map { it.get(10, TimeUnit.SECONDS) }

			assertEquals(List(5) { 200 }, codes)
			assertEquals(1, browser.opened.get())
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	@DisplayName("a check that did not clear is not retried for every request that follows")
	fun failureCoolsTheHostDown(@TempDir dir: File) {
		val jar = jar(dir)
		val browser = FakeBrowser(cookies = { null })
		val client = client(browser, jar, FakeSite(jar))

		client.get().use { assertEquals(403, it.code) }
		client.get().use { assertEquals(403, it.code) }
		assertEquals(1, browser.opened.get(), "the second request must not reopen the browser")

		now += 31_000
		client.get().use { assertEquals(403, it.code) }
		assertEquals(2, browser.opened.get(), "after the cooldown it is worth asking again")
	}

	/**
	 * The site wants more than the cookie -- a TLS fingerprint OkHttp cannot present. Reopening the
	 * browser for every request would be a window appearing over and over for nothing.
	 */
	@Test
	@DisplayName("a site that refuses even with the cookie stops getting browser windows")
	fun stubbornSiteCoolsDown(@TempDir dir: File) {
		val jar = jar(dir)
		val browser = FakeBrowser()
		val client = client(browser, jar, FakeSite(jar, stubborn = true))

		client.get().use { assertEquals(403, it.code) }
		client.get().use { assertEquals(403, it.code) }
		assertEquals(1, browser.opened.get())
	}

	/** The user left the screen: no window should appear for a request nobody is waiting on. */
	@Test
	@DisplayName("cancelling the call abandons the clearance")
	fun cancellationAbandonsClearance(@TempDir dir: File) {
		val jar = jar(dir)
		val browser = FakeBrowser(cookies = { awaitCancellation() })
		val call = client(browser, jar, FakeSite(jar)).newCall(Request.Builder().url(url).build())
		val pool = Executors.newSingleThreadExecutor()
		try {
			val outcome = pool.submit<Throwable?> { runCatching { call.execute().close() }.exceptionOrNull() }
			while (browser.opened.get() == 0) Thread.sleep(10)
			call.cancel()

			assertInstanceOf(IOException::class.java, outcome.get(5, TimeUnit.SECONDS))
		} finally {
			pool.shutdownNow()
		}
	}

	private companion object {
		fun clearance() = BrowserCookie(
			name = "cf_clearance",
			value = "earned",
			domain = ".comick.example",
			path = "/",
			secure = true,
			httpOnly = true,
			expiresAtMillis = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1),
		)

		/** The parts of Cloudflare's managed-challenge page the parsers library looks for. */
		const val CHALLENGE = """
			<!DOCTYPE html><html><head><title>Just a moment...</title></head>
			<body><div id="challenge-error-text">Enable JavaScript and cookies to continue</div></body></html>
		"""
	}
}
