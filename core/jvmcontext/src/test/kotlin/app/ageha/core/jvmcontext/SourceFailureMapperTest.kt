package app.ageha.core.jvmcontext

import app.ageha.core.js.NoJsRuntime
import app.ageha.core.model.BrowserActionRequiredException
import app.ageha.core.model.JsCapability
import app.ageha.core.model.SourceFailure
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.json.JSONException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.jsoup.HttpStatusException
import java.io.IOException

/**
 * The requirement these tests exist for:
 *
 * > For the conditional anti-bot sources, a NoJsRuntime failure must be distinguishable from a
 * > network failure and must surface as an actionable prompt to install the browser component.
 * > Not a generic error.
 *
 * The hard case is not "JavaScript threw". It is "JavaScript threw, the parser caught it and
 * carried on, and then the call failed for an unrelated-looking reason". That is exactly what the
 * 257 MangaReader-derived sources do -- `getNetShieldCookie` wraps its `evaluateJs` call in
 * `runCatchingCancellable` and discards the result -- and it is the case a plain try/catch loses.
 */
class SourceFailureMapperTest {

	private val source = "TEST_SOURCE"

	@Test
	@DisplayName("a swallowed JS refusal still reports as MissingJsRuntime, not as a network error")
	fun swallowedRefusalSurvives() = runTest {
		val failure = assertThrows<SourceFailure> {
			runSourceCall(source) {
				// Exactly what MangaReaderParser.getNetShieldCookie does: try the JS path, and
				// quietly give up on it if anything goes wrong.
				try {
					NoJsRuntime.evaluate("slowAES.decrypt()")
				} catch (ignored: Throwable) {
					// swallowed, deliberately
				}
				// The parser then fails somewhere else, for a reason that looks like transport.
				throw IOException("Unexpected end of stream")
			}
		}

		val missing = assertInstanceOf(SourceFailure.MissingJsRuntime::class.java, failure)
		assertEquals(JsCapability.PLAIN_SCRIPT, missing.capability)
		assertEquals(source, missing.sourceName)
	}

	@Test
	@DisplayName("a genuine network failure is not misreported as a missing runtime")
	fun realNetworkFailureStaysNetwork() = runTest {
		val failure = assertThrows<SourceFailure> {
			runSourceCall(source) {
				throw IOException("connect timed out")
			}
		}

		val network = assertInstanceOf(SourceFailure.Network::class.java, failure)
		assertTrue(network.isTransient, "a network failure should be marked retryable")
	}

	@Test
	@DisplayName("an unswallowed refusal reports the capability that was refused")
	fun directRefusalIsClassified() = runTest {
		val failure = assertThrows<SourceFailure> {
			runSourceCall(source) {
				NoJsRuntime.evaluateInPage("https://example.org/", "return 1", 1000L)
			}
		}

		val missing = assertInstanceOf(SourceFailure.MissingJsRuntime::class.java, failure)
		assertEquals(JsCapability.PAGE_CONTEXT, missing.capability)
	}

	@Test
	@DisplayName("a Cloudflare hand-off is a challenge, not a missing runtime")
	fun cloudflareIsItsOwnFailure() = runTest {
		val failure = assertThrows<SourceFailure> {
			runSourceCall(source) {
				throw BrowserActionRequiredException(source, "https://example.org/", isCloudflare = true)
			}
		}

		val challenge = assertInstanceOf(SourceFailure.ChallengeRequired::class.java, failure)
		assertEquals("https://example.org/", challenge.url)
	}

	/**
	 * A sign-in or captcha is a check at a url, like a Cloudflare page -- not a missing component.
	 *
	 * Reported as a missing browser component, it offered an install; with the component installed,
	 * it offered the install again, because an install was never what the site asked for. The url
	 * is what the remedy needs: it is where the browser window has to open.
	 */
	@Test
	@DisplayName("a non-Cloudflare browser hand-off is a check to pass at its url")
	fun interactiveBrowserIsAChallenge() = runTest {
		val failure = assertThrows<SourceFailure> {
			runSourceCall(source) {
				throw BrowserActionRequiredException(source, "https://example.org/login", isCloudflare = false)
			}
		}

		val challenge = assertInstanceOf(SourceFailure.ChallengeRequired::class.java, failure)
		assertEquals("https://example.org/login", challenge.url)
	}

	@Test
	@DisplayName("a 403 is reported as blocked, not as an unreachable network")
	fun forbiddenIsNotANetworkFailure() = runTest {
		// jsoup raises HttpStatusException, which extends IOException. Read in the wrong order,
		// every bot-protection 403 becomes "check your connection" -- which sends the user to
		// look at the one thing that is definitely working.
		val failure = assertThrows<SourceFailure> {
			runSourceCall(source) {
				throw HttpStatusException("Forbidden", 403, "https://example.org/search")
			}
		}

		val blocked = assertInstanceOf(SourceFailure.Blocked::class.java, failure)
		assertEquals(403, blocked.statusCode)
		assertFalse(blocked.isTransient, "being blocked is not fixed by retrying")
	}

	@Test
	@DisplayName("a 5xx really is the server failing, and stays retryable")
	fun serverErrorStaysNetwork() = runTest {
		val failure = assertThrows<SourceFailure> {
			runSourceCall(source) {
				throw HttpStatusException("Bad Gateway", 502, "https://example.org/search")
			}
		}

		assertTrue(assertInstanceOf(SourceFailure.Network::class.java, failure).isTransient)
	}

	@Test
	@DisplayName("a 404 is not found, not blocked")
	fun notFoundIsItsOwnFailure() = runTest {
		val failure = assertThrows<SourceFailure> {
			runSourceCall(source) {
				throw HttpStatusException("Not Found", 404, "https://example.org/manga/gone")
			}
		}

		assertInstanceOf(SourceFailure.NotFound::class.java, failure)
	}

	@Test
	@DisplayName("a refusal wrapped by the interceptor's IOException re-wrap is still found")
	fun refusalSurvivesInterceptorRewrapping() = runTest {
		// ParserDispatchInterceptor must re-wrap non-IO exceptions as IOException, because OkHttp
		// tolerates nothing else escaping an interceptor. The classifier has to see through that.
		val failure = assertThrows<SourceFailure> {
			runSourceCall(source) {
				val refusal = runCatching { NoJsRuntime.openInteractive("https://example.org/", null) }
					.exceptionOrNull()
				throw IOException("Parser interceptor failed", refusal)
			}
		}

		assertInstanceOf(SourceFailure.MissingJsRuntime::class.java, failure)
	}

	@Test
	@DisplayName("a call that recovers from a refusal is not a failure")
	fun refusalWithoutFailureIsNotReported() = runTest {
		val result = runSourceCall(source) {
			try {
				NoJsRuntime.evaluate("anything")
			} catch (ignored: Throwable) {
				// The parser has a fallback and it works.
			}
			"parsed anyway"
		}

		assertEquals("parsed anyway", result)
	}

	/**
	 * The commonest failure in the whole app, previously shown as "failed unexpectedly".
	 *
	 * 240 of the parsers in the bundled build read their answers through `org.json`. Almost none of
	 * them check first, so a site that renames a field -- or answers an API call with an HTML error
	 * page -- produces a bare `JSONException`. It extends Exception rather than IOException, so it
	 * fell through every case in the mapper into `Unknown`, which is the screen that says the source
	 * failed unexpectedly and offers to send a report. Both halves of that are wrong: it is entirely
	 * expected, and there is nothing on the reader's side to report.
	 */
	@Test
	@DisplayName("a JSON error is the site changing shape, not an unexpected failure")
	fun jsonErrorIsUnparseable() = runTest {
		val failure = assertThrows<SourceFailure> {
			runSourceCall(source) { throw JSONException("No value for chapters") }
		}
		assertTrue(
			failure is SourceFailure.Unparseable,
			"expected Unparseable, got " + failure::class.simpleName,
		)
	}

	@Test
	@DisplayName("a JSON error nested behind the interceptor's re-wrap is still found")
	fun nestedJsonErrorIsUnparseable() = runTest {
		val failure = assertThrows<SourceFailure> {
			runSourceCall(source) {
				throw IOException("Parser interceptor failed", JSONException("No value for md_comics"))
			}
		}
		assertTrue(
			failure is SourceFailure.Unparseable,
			"expected Unparseable, got " + failure::class.simpleName,
		)
	}

	/** A chapter number scraped out of text that no longer holds one. Same cause, same answer. */
	@Test
	@DisplayName("a number that would not parse is the site changing shape too")
	fun numberFormatErrorIsUnparseable() = runTest {
		val failure = assertThrows<SourceFailure> {
			runSourceCall(source) { throw NumberFormatException("For input string: Extra") }
		}
		assertTrue(
			failure is SourceFailure.Unparseable,
			"expected Unparseable, got " + failure::class.simpleName,
		)
	}
}
