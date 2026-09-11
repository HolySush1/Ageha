package app.ageha.core.js

import app.ageha.core.model.JsCapability
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The composite's routing, and its refusals.
 *
 * The refusal is the part with history. A parser that swallows what its fallback throws -- which
 * the anti-bot paths routinely do -- then fails later for a vaguer reason, and the only record of
 * the real cause is the breadcrumb a refusal leaves on the coroutine context. The composite used to
 * throw without leaving one, so a missing browser could surface as "returned something unexpected".
 */
class CompositeJsRuntimeTest {

	private val withoutBrowser = CompositeJsRuntime(script = RhinoJsRuntime(), browser = null)

	@Test
	fun `a tier nobody provides leaves a breadcrumb even when the refusal is swallowed`() = runTest {
		val recorder = JsAttemptRecorder()
		withContext(recorder) {
			// Swallowed, as MangaReaderParser's NetShield path swallows it.
			runCatching { withoutBrowser.evaluateInPage("https://example.test", "1", 1_000) }
		}
		assertEquals(JsCapability.PAGE_CONTEXT, recorder.primaryMissing)
	}

	@Test
	fun `plain scripts go to the script engine, not a browser`() = runTest {
		assertEquals("2", withoutBrowser.evaluate("1 + 1"))
	}

	@Test
	fun `the capability set is the union of both backends`() {
		assertTrue(JsCapability.PLAIN_SCRIPT in withoutBrowser.capabilities)
		assertTrue(withoutBrowser.capabilities.none { it.requiresBrowser })
	}
}
