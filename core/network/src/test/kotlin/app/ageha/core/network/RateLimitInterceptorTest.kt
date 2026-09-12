package app.ageha.core.network

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RateLimitInterceptorTest {

	@Test
	@DisplayName("consecutive requests to one host are spaced out")
	fun spacesRequestsToTheSameHost() {
		val clock = FakeClock()
		val interceptor = RateLimitInterceptor(
			minIntervalMillis = 100L,
			sleeper = clock::advance,
			clock = clock::now,
		)

		repeat(3) {
			interceptor.intercept(FakeChain(request("https://example.org/a"), code = 200))
		}

		// First request goes immediately; the next two each wait a full interval.
		assertEquals(200L, clock.slept, "expected two 100ms waits")
	}

	@Test
	@DisplayName("a slow host does not throttle a different one")
	fun throttlingIsPerHost() {
		val clock = FakeClock()
		val interceptor = RateLimitInterceptor(
			minIntervalMillis = 100L,
			sleeper = clock::advance,
			clock = clock::now,
		)

		interceptor.intercept(FakeChain(request("https://one.test/x"), code = 200))
		interceptor.intercept(FakeChain(request("https://two.test/x"), code = 200))

		assertEquals(0L, clock.slept, "different hosts should not wait on each other")
	}

	@Test
	@DisplayName("Retry-After on a 429 is honoured, then the request is retried")
	fun honoursRetryAfter() {
		val clock = FakeClock()
		val interceptor = RateLimitInterceptor(
			minIntervalMillis = 0L,
			maxRetries = 1,
			sleeper = clock::advance,
			clock = clock::now,
		)

		val chain = FakeChain(request("https://example.org/a"), code = 429, retryAfterSeconds = 2)
		val response = interceptor.intercept(chain)

		assertEquals(2, chain.calls, "should have retried once")
		assertTrue(clock.slept >= 2000L, "should have waited out the Retry-After")
		response.close()
	}

	/**
	 * The regression this file previously enshrined.
	 *
	 * A bare 429 with no `Retry-After` used to be handed straight back as a failure, and that is
	 * the commonest shape of the header in the wild: a host with a *concurrency* cap rather than a
	 * rate quota has nothing meaningful to put in it. ComicK's CDN serves ten simultaneous requests
	 * and refuses the eleventh that way -- so a reader opening a sixteen-page chapter lost six
	 * pages to a limit that had already cleared by the time it was told about it, and the page's
	 * Retry button fired straight back into the same saturated burst. Measured: 10 of 16 pages
	 * before, 16 of 16 after.
	 */
	@Test
	@DisplayName("a 429 with no Retry-After is backed off and retried, not given up on")
	fun retriesWithoutRetryAfter() {
		val clock = FakeClock()
		val interceptor = RateLimitInterceptor(
			minIntervalMillis = 0L,
			sleeper = clock::advance,
			clock = clock::now,
		)

		val chain = FakeChain(request("https://example.org/a"), code = 429)
		val response = interceptor.intercept(chain)

		assertEquals(2, chain.calls, "a bare 429 should be retried")
		assertEquals(200, response.code, "the retry's answer is what the caller gets")
		assertTrue(clock.slept > 0L, "the retry should have waited first, not hammered")
		response.close()
	}

	/**
	 * One refusal has to slow the whole host, not just the request that met it.
	 *
	 * The rest of a chapter's images are already in flight behind the first refusal, and letting
	 * them arrive at the same closed door turns one 429 into a chapter of them.
	 */
	@Test
	@DisplayName("a 429 pushes out the next request to that host as well")
	fun backoffAppliesToTheWholeHost() {
		// A sleeper that records without advancing the clock, which is the only way to model what
		// actually happens: the other fifteen images of the chapter are *already in flight* when
		// the first one is refused, so they arrive while the backoff is still pending rather than
		// after it has elapsed. A clock that advances on sleep would have the first request consume
		// its own backoff and let the next one straight through, which is not the situation.
		var slept = 0L
		val interceptor = RateLimitInterceptor(
			minIntervalMillis = 0L,
			sleeper = { slept += it },
			clock = { 0L },
		)

		interceptor.intercept(FakeChain(request("https://example.org/a"), code = 429)).close()
		val duringRetry = slept

		// A second request to the same host, arriving inside the backoff window.
		interceptor.intercept(FakeChain(request("https://example.org/b"), code = 200)).close()

		assertTrue(
			slept > duringRetry,
			"a request arriving while a host is backing off should wait too, not sail past",
		)
	}

	@Test
	@DisplayName("a host that keeps refusing is given up on rather than retried for ever")
	fun stopsAfterMaxRetries() {
		val clock = FakeClock()
		val interceptor = RateLimitInterceptor(
			minIntervalMillis = 0L,
			maxRetries = 2,
			sleeper = clock::advance,
			clock = clock::now,
		)

		// Refuses every time, unlike FakeChain, so the retry budget is what ends the loop.
		val chain = StubbornChain(request("https://example.org/a"))
		val response = interceptor.intercept(chain)

		assertEquals(3, chain.calls, "one attempt plus two retries")
		assertEquals(429, response.code, "the caller is told, rather than waiting for ever")
		response.close()
	}

	/**
	 * A reader opening a chapter legitimately wants twenty images at once, and the general floor
	 * serialised exactly that -- 250ms a page, with a thread held asleep for each one, on the only
	 * path where the delay is directly visible.
	 */
	@Test
	@DisplayName("page images get the lower floor, not the one meant for parser calls")
	fun imagesUseTheImageInterval() {
		val clock = FakeClock()
		val interceptor = RateLimitInterceptor(
			minIntervalMillis = 1_000L,
			imageIntervalMillis = 10L,
			sleeper = clock::advance,
			clock = clock::now,
		)

		repeat(3) {
			interceptor.intercept(FakeChain(imageRequest("https://cdn.example.org/1.jpg"), code = 200))
		}

		assertEquals(20L, clock.slept, "images should wait the image interval, not the general one")
	}

	@Test
	@DisplayName("a parser's own call still gets the general floor")
	fun parserCallsKeepTheGeneralInterval() {
		val clock = FakeClock()
		val interceptor = RateLimitInterceptor(
			minIntervalMillis = 1_000L,
			imageIntervalMillis = 10L,
			sleeper = clock::advance,
			clock = clock::now,
		)

		repeat(2) {
			interceptor.intercept(FakeChain(request("https://example.org/api"), code = 200))
		}

		assertEquals(1_000L, clock.slept, "an untagged request is not an image request")
	}

	private fun request(url: String) = Request.Builder().url(url).build()

	/** As Ageha's image requests arrive: carrying the marker naming the source they belong to. */
	private fun imageRequest(url: String) = Request.Builder()
		.url(url)
		.header(HttpHeaders.SOURCE_NAME, "TESTSOURCE")
		.build()

	private class FakeClock {
		private var current = 0L
		var slept = 0L
			private set

		fun now() = current

		fun advance(millis: Long) {
			slept += millis
			current += millis
		}
	}

	/** Like [FakeChain], but never relents -- for proving the retry budget is finite. */
	private class StubbornChain(private val request: Request) : Interceptor.Chain {

		var calls = 0
			private set

		override fun request(): Request = request

		override fun proceed(request: Request): Response {
			calls++
			return Response.Builder()
				.request(request)
				.protocol(Protocol.HTTP_1_1)
				.code(429)
				.message("test")
				.body("".toResponseBody(null))
				.build()
		}

		override fun connection() = null
		override fun call() = throw UnsupportedOperationException()
		override fun connectTimeoutMillis() = 0
		override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
		override fun readTimeoutMillis() = 0
		override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
		override fun writeTimeoutMillis() = 0
		override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
	}

	/**
	 * Enough of [Interceptor.Chain] to drive the interceptor. Only `request` and `proceed` are
	 * reachable from the code under test; the rest exist to satisfy the interface.
	 */
	private class FakeChain(
		private val request: Request,
		private val code: Int,
		private val retryAfterSeconds: Int? = null,
	) : Interceptor.Chain {

		var calls = 0
			private set

		override fun request(): Request = request

		override fun proceed(request: Request): Response {
			calls++
			// After a retry, pretend the host relented, so the loop terminates the way it would
			// in reality rather than by exhausting the retry budget.
			val effectiveCode = if (calls > 1) 200 else code
			return Response.Builder()
				.request(request)
				.protocol(Protocol.HTTP_1_1)
				.code(effectiveCode)
				.message("test")
				.body("".toResponseBody(null))
				.apply {
					if (effectiveCode == code && retryAfterSeconds != null) {
						header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
					}
				}
				.build()
		}

		override fun connection() = null
		override fun call() = throw UnsupportedOperationException()
		override fun connectTimeoutMillis() = 0
		override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
		override fun readTimeoutMillis() = 0
		override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
		override fun writeTimeoutMillis() = 0
		override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
	}
}
