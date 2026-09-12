package app.ageha.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Header names the parsers library and the sites it talks to care about. */
object HttpHeaders {
	const val USER_AGENT = "User-Agent"
	const val REFERER = "Referer"
	const val ACCEPT_LANGUAGE = "Accept-Language"
	const val RETRY_AFTER = "Retry-After"

	/**
	 * Ageha's own marker naming the source a request belongs to. Never sent to a site.
	 *
	 * It lives here rather than in `:core:jvmcontext`, where it is written and consumed, because
	 * [RateLimitInterceptor] also needs to recognise it and may not name a parsers type. See
	 * `SourceTagInterceptor` for why the source travels as a header at all.
	 */
	const val SOURCE_NAME = "X-Ageha-Source"
}

/**
 * Fills in the headers a browser would send and a bare HTTP client would not.
 *
 * Only ever *adds* -- a parser that sets its own User-Agent or Referer knows something about its
 * site that we do not, and overriding it is how you break one source while fixing another.
 *
 * ## This must run below the parser, not above it
 *
 * "Only adds" is a property of the request, and on its own it is not enough. Upstream's
 * `MangaParserWrapper.intercept` merges each parser's `getRequestHeaders()` with
 * `mergeWith(..., replace = false)`, which *skips* every name already present -- so filling a gap
 * before the parser is reached does not add to the parser's headers, it replaces them. Ageha
 * shipped it that way and silently dropped the Referer, User-Agent or Accept-Language of 52 parser
 * classes, several of them base classes serving dozens of sources.
 *
 * So on the source stack's client this is installed *inside* the parser dispatch, which is where
 * the Android app has it too. See `AgehaMangaLoaderContext.httpClient`, which does that reordering
 * explicitly and explains it. Off that client -- the update service, sync, the app update check --
 * there is no parser and position does not matter.
 */
class CommonHeadersInterceptor(
	private val defaultUserAgent: () -> String,
	private val acceptLanguage: () -> String,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val builder = request.newBuilder()
		if (request.header(HttpHeaders.USER_AGENT) == null) {
			builder.header(HttpHeaders.USER_AGENT, defaultUserAgent())
		}
		if (request.header(HttpHeaders.ACCEPT_LANGUAGE) == null) {
			builder.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage())
		}
		if (request.header(HttpHeaders.REFERER) == null) {
			// Many sources reject image requests without a same-origin referer.
			builder.header(HttpHeaders.REFERER, "${request.url.scheme}://${request.url.host}/")
		}
		return chain.proceed(builder.build())
	}
}

/**
 * Keeps Ageha from hammering a single host.
 *
 * Two jobs. First, a floor on the interval between requests to the same host, because a reader
 * prefetching twenty pages will otherwise open twenty connections at once and look exactly like
 * a scraper. Second, honouring `Retry-After` on 429 and 503 instead of retrying blindly.
 *
 * Per-host rather than global: one slow source must not stall every other tab.
 */
class RateLimitInterceptor(
	private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MS,
	/**
	 * The floor for page and cover images, which is lower than [minIntervalMillis] on purpose.
	 *
	 * The general floor exists so a burst of parser calls does not look like a scraper. Images are
	 * different in kind: a reader opening a chapter legitimately wants twenty of them at once, and
	 * a browser fetching a page of a comic behaves exactly the same way -- so the general floor
	 * serialises the one path where the delay is directly visible, at 250ms a page, while
	 * [throttle] holds a thread asleep for each one.
	 *
	 * Not zero. A floor of some kind is still what keeps Ageha from hammering one host, and a
	 * source's images are frequently on the same host as its pages.
	 */
	private val imageIntervalMillis: Long = DEFAULT_IMAGE_INTERVAL_MS,
	private val maxRetries: Int = 2,
	private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
	private val clock: () -> Long = System::currentTimeMillis,
) : Interceptor {

	private val nextAllowedAt = ConcurrentHashMap<String, Long>()

	override fun intercept(chain: Interceptor.Chain): Response {
		val host = chain.request().url.host
		// Ageha marks the requests it makes on a source's behalf, which are its image requests.
		// The marker is still on the request here: SourceTagInterceptor, which consumes it, runs
		// further in.
		val interval = if (chain.request().header(HttpHeaders.SOURCE_NAME) != null) {
			imageIntervalMillis
		} else {
			minIntervalMillis
		}
		var attempt = 0
		while (true) {
			throttle(host, interval)
			val response = chain.proceed(chain.request())
			if (response.code !in RETRYABLE_CODES || attempt >= maxRetries) {
				return response
			}
			val retryAfter = response.header(HttpHeaders.RETRY_AFTER)?.toRetryAfterMillis()
			if (retryAfter == null) {
				return response
			}
			response.close()
			nextAllowedAt[host] = clock() + retryAfter
			attempt++
		}
	}

	private fun throttle(host: String, intervalMillis: Long) {
		val now = clock()
		// compute() rather than get/put so two threads racing on the same host cannot both pass
		// the gate and fire simultaneously. Each caller claims a slot and is told when it starts.
		var slotStartsAt = now
		nextAllowedAt.compute(host) { _, previousSlotEnd ->
			slotStartsAt = maxOf(previousSlotEnd ?: 0L, now)
			slotStartsAt + intervalMillis
		}
		val waitFor = slotStartsAt - now
		if (waitFor > 0) {
			sleeper(waitFor)
		}
	}

	private fun String.toRetryAfterMillis(): Long? {
		// Retry-After is either delta-seconds or an HTTP date. We only honour the former; a date
		// far in the future would otherwise let a hostile server pin a thread indefinitely.
		val seconds = trim().toLongOrNull() ?: return null
		return seconds.coerceIn(0, MAX_RETRY_AFTER_SECONDS) * 1000L
	}

	private companion object {
		const val DEFAULT_MIN_INTERVAL_MS = 250L
		const val DEFAULT_IMAGE_INTERVAL_MS = 50L
		const val MAX_RETRY_AFTER_SECONDS = 30L
		val RETRYABLE_CODES = setOf(429, 503)
	}
}
