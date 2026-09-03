package app.ageha.core.js

import app.ageha.core.model.JsCapability
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The script engine, and above all its sandbox.
 *
 * The scripts this runs are **served by the manga site being scraped**. They are hostile input,
 * and the tests that matter here are the ones asserting a hostile script cannot reach the
 * machine. Rhino's default scope would hand it `java.lang.Runtime`; these confirm this one does
 * not, by asking for it directly rather than by trusting the configuration.
 */
class RhinoJsRuntimeTest {

	private val runtime = RhinoJsRuntime()

	// ------------------------------------------------------------------ the contract

	/**
	 * The real workload's shape. The parsers rewrite `document.cookie = <expr>` into
	 * `return <expr>`, so what arrives has a top-level `return` -- legal only inside a function.
	 */
	@Test
	fun `a top-level return is the contract`() = runTest {
		assertEquals("cf_clearance=abc", runtime.evaluate("return 'cf_clearance=' + 'abc';"))
	}

	@Test
	fun `a script with no return still yields its completion value`() = runTest {
		assertEquals("42", runtime.evaluate("40 + 2"))
	}

	@Test
	fun `a script that produces nothing yields null`() = runTest {
		assertNull(runtime.evaluate("var x = 1;"))
	}

	/** The actual anti-bot workload in miniature: a library, then a script that uses it. */
	@Test
	fun `slowAES-shaped computation runs`() = runTest {
		val library = """
			var slowAES = {
				decrypt: function (bytes, key) {
					var out = [];
					for (var i = 0; i < bytes.length; i++) {
						out.push((bytes[i] ^ key[i % key.length]) & 0xff);
					}
					return out;
				}
			};
		""".trimIndent()
		val siteScript = "return 'v=' + slowAES.decrypt([1,2,3,4],[9,9]).join('-');"
		assertEquals("v=8-11-10-13", runtime.evaluate(library + "\n\n" + siteScript))
	}

	@Test
	fun `modern syntax is available`() = runTest {
		assertEquals("a", runtime.evaluate("const m = new Map([[1,'a']]); return m.get(1);"))
		assertEquals("v=1", runtime.evaluate("let n = 1; return `v=${'$'}{n}`;"))
		assertEquals("3", runtime.evaluate("return [1,2,3].map(x => x).length;"))
	}

	@Test
	fun `JSON is available, since sites encode payloads with it`() = runTest {
		assertEquals("5", runtime.evaluate("""return String(JSON.parse('{"x":5}').x);"""))
	}

	// ------------------------------------------------------------------ the sandbox

	/**
	 * The one that matters most. With Rhino's ordinary scope this script executes a process.
	 */
	@Test
	fun `a script cannot reach java Runtime`() = runTest {
		val hostile = "return String(java.lang.Runtime.getRuntime());"
		// It fails as a script error, which this runtime reports as "no result" rather than
		// letting the exception escape -- a hostile script must not be able to crash Ageha either.
		assertNull(runtime.evaluate(hostile))
	}

	@Test
	fun `a script cannot reach the Packages bridge`() = runTest {
		assertNull(runtime.evaluate("return String(Packages.java.io.File);"))
		assertNull(runtime.evaluate("return String(new Packages.java.io.File('/'));"))
	}

	@Test
	fun `a script cannot reach getClass on an object`() = runTest {
		assertNull(runtime.evaluate("return String(({}).getClass());"))
	}

	@Test
	fun `a script cannot load a class by name`() = runTest {
		assertNull(runtime.evaluate("return String(JavaAdapter);"))
	}

	/**
	 * A script written to spin forever must not pin a core for as long as Ageha is open.
	 *
	 * The budget is deliberately not catchable as a script error: a hostile script that could
	 * `try`/`catch` its own termination would defeat the point.
	 */
	@Test
	fun `an endless loop is stopped by the time budget`() {
		val tight = RhinoJsRuntime(timeBudgetMillis = 250)
		val elapsed = kotlin.system.measureTimeMillis {
			assertThrows(ScriptTimedOut::class.java) {
				kotlinx.coroutines.runBlocking { tight.evaluate("while (true) {}") }
			}
		}
		// The budget must bound the wall clock, not merely be declared.
		assertTrue(elapsed < 5_000) { "an endless script ran for ${'$'}{elapsed}ms" }
	}

	@Test
	fun `a script cannot catch its own termination`() {
		val tight = RhinoJsRuntime(timeBudgetMillis = 250)
		assertThrows(ScriptTimedOut::class.java) {
			kotlinx.coroutines.runBlocking {
				tight.evaluate("try { while (true) {} } catch (e) { return 'escaped'; }")
			}
		}
	}

	/** A site serving broken JavaScript is a source problem, not an Ageha crash. */
	@Test
	fun `a syntax error yields null rather than throwing`() = runTest {
		assertNull(runtime.evaluate("this is not javascript ((("))
	}

	@Test
	fun `a thrown script error yields null rather than throwing`() = runTest {
		assertNull(runtime.evaluate("throw new Error('the site is unhappy');"))
	}

	// ------------------------------------------------------------------ capabilities

	@Test
	fun `only the plain script tier is offered`() {
		assertEquals(setOf(JsCapability.PLAIN_SCRIPT), runtime.capabilities)
	}

	/**
	 * A Rhino engine is not a browser, so it reports no User-Agent. Claiming to be Chrome while
	 * presenting a JVM TLS fingerprint is precisely the mismatch bot detection looks for.
	 */
	@Test
	fun `no browser user agent is claimed`() {
		assertNull(runtime.browserUserAgent)
	}

	/**
	 * The browser tiers refuse *through the recorder*, so the reason survives a parser that
	 * swallows the exception -- which `MangaReaderParser` does, by wrapping its call in
	 * `runCatchingCancellable`.
	 */
	@Test
	fun `refusing a browser tier leaves a breadcrumb`() = runTest {
		val recorder = JsAttemptRecorder()
		withContext(recorder) {
			// Swallowed exactly the way the parser swallows it.
			runCatching { runtime.evaluateInPage("https://example.test", "return 1;", 1000) }
		}
		assertTrue(JsCapability.PAGE_CONTEXT in recorder.missingCapabilities)
		assertEquals(JsCapability.PAGE_CONTEXT, recorder.primaryMissing)
	}

	@Test
	fun `every unsupported tier refuses with its own capability`() = runTest {
		val recorder = JsAttemptRecorder()
		withContext(recorder) {
			runCatching { runtime.evaluateInPage("u", "s", 1) }
			runCatching { runtime.interceptRequests("u", null, null, 1, 1) }
			runCatching { runtime.openInteractive("u", null) }
		}
		assertEquals(
			setOf(
				JsCapability.PAGE_CONTEXT,
				JsCapability.REQUEST_INTERCEPTION,
				JsCapability.INTERACTIVE_BROWSER,
			),
			recorder.missingCapabilities,
		)
		// Interactive is the most actionable of the three, so it is the one reported.
		assertEquals(JsCapability.INTERACTIVE_BROWSER, recorder.primaryMissing)
	}

	@Test
	fun `the refusal carries the capability so the UI can offer a remedy`() = runTest {
		val thrown = assertThrows(JsUnavailableException::class.java) {
			kotlinx.coroutines.runBlocking { runtime.openInteractive("u", null) }
		}
		assertEquals(JsCapability.INTERACTIVE_BROWSER, thrown.capability)
		assertNotNull(thrown.message)
	}
}
