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
	const val ACCEPT = "Accept"
	const val RETRY_AFTER = "Retry-After"

	/**
	 * What the Android app asks for on a page image, character for character.
	 *
	 * Its `PageLoader.createPageRequest` sets exactly this, and Ageha sent no Accept at all -- the
	 * constant was here, unused, and I deleted it as dead before finding out upstream uses it on
	 * the one request type that matters most. A CDN is entitled to vary its answer on Accept, and
	 * several serve WebP only when asked, so sending nothing is not a neutral choice.
	 */
	const val IMAGE_ACCEPT = "image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8"

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
	 * The floor for page and cover images. The same as [minIntervalMillis] by default.
	 *
	 * It was briefly 50ms, on the argument that a reader opening a chapter legitimately wants
	 * twenty images at once and the general floor serialises the one delay a person can feel. That
	 * argument is still true and it is not worth the risk: 0.3.7 shipped it, page loads started
	 * failing in the field, and a floor five times lower is the obvious suspect -- a source that
	 * answers four requests a second happily may well refuse twenty. Nothing established that 50ms
	 * was safe, and the cost of being wrong lands on the person reading, not on us.
	 *
	 * Kept as a parameter rather than deleted so the experiment can be re-run against a source
	 * that has actually been measured, one host at a time.
	 */
	private val imageIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MS,
	private val maxRetries: Int = DEFAULT_MAX_RETRIES,
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

			// A 429 without Retry-After used to be given up on immediately, and that is the single
			// most common shape of the header in the wild -- a host with a *concurrency* cap rather
			// than a rate quota has nothing sensible to put in it. ComicK's CDN is exactly that:
			// it serves ten simultaneous requests and refuses the eleventh with a bare 429, and the
			// same url asked again a moment later returns 200. So a reader opening a sixteen-page
			// chapter lost six pages to a limit it had already cleared by the time it was told, and
			// the Retry button fired straight back into the same saturated burst.
			//
			// Retry-After is still honoured where it is sent; where it is not, back off on our own
			// and double each time.
			val backoff = response.header(HttpHeaders.RETRY_AFTER)?.toRetryAfterMillis()
				?: backoffFor(attempt)
			response.close()
			// Push the whole host out, not just this request. The rest of the burst is already in
			// flight behind this one, and letting it arrive at the same closed door would turn one
			// refusal into a chapter of them. Every waiting request now queues behind the backoff.
			nextAllowedAt[host] = maxOf(nextAllowedAt[host] ?: 0L, clock() + backoff)
			attempt++
		}
	}

	/** Doubling, from [INITIAL_BACKOFF_MS], capped so a hostile server cannot pin a thread. */
	private fun backoffFor(attempt: Int): Long =
		(INITIAL_BACKOFF_MS shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)

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

		/**
		 * Three retries, because the failure this exists for clears almost immediately.
		 *
		 * A concurrency cap is not a quota: the refused request is competing with Ageha's own
		 * other requests, and those finish in the time the first backoff takes.
		 */
		const val DEFAULT_MAX_RETRIES = 3
		const val INITIAL_BACKOFF_MS = 400L
		const val MAX_BACKOFF_MS = 4_000L

		/** Guards the shift itself, so a raised [DEFAULT_MAX_RETRIES] cannot overflow it. */
		const val MAX_BACKOFF_SHIFT = 8
		const val MAX_RETRY_AFTER_SECONDS = 30L
		val RETRYABLE_CODES = setOf(429, 503)
	}
}
