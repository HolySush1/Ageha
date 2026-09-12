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

	@Test
	@DisplayName("a 429 without Retry-After is returned rather than retried blindly")
	fun doesNotRetryWithoutRetryAfter() {
		val clock = FakeClock()
		val interceptor = RateLimitInterceptor(
			minIntervalMillis = 0L,
			sleeper = clock::advance,
			clock = clock::now,
		)

		val chain = FakeChain(request("https://example.org/a"), code = 429)
		interceptor.intercept(chain).close()

		assertEquals(1, chain.calls)
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

	/**
	 * Enough of [Interceptor.Chain] to drive the interceptor. Only [request] and [proceed] are
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
