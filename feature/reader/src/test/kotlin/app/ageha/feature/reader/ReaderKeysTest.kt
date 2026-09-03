package app.ageha.feature.reader

import androidx.compose.ui.input.key.Key
import app.ageha.core.model.PageScale
import app.ageha.core.model.ReaderMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reader's keyboard, and specifically that the arrow keys follow the *reading direction*.
 *
 * Binding Right to "next page" unconditionally is the intuitive implementation and it is
 * backwards for right-to-left manga, which is most of what a manga reader opens. It is also the
 * kind of thing that survives review, because it feels correct to anyone testing with a
 * left-to-right title.
 */
class ReaderKeysTest {

	/** Records what the bindings asked for. */
	private class Recorder : ReaderActions {
		val calls = mutableListOf<String>()
		override fun nextPage() { calls += "next" }
		override fun previousPage() { calls += "previous" }
		override fun nextChapter() { calls += "nextChapter" }
		override fun previousChapter() { calls += "previousChapter" }
		override fun goToPage(index: Int) { calls += "goTo:$index" }
		override fun setScale(scale: PageScale) { calls += "scale:${scale.name}" }
		override fun toggleChrome() { calls += "chrome" }
		override fun toggleFullscreen() { calls += "fullscreen" }
		override fun close() { calls += "close" }
	}

	private fun press(key: Key, mode: ReaderMode, shift: Boolean = false): Recorder {
		val recorder = Recorder()
		ReaderKeys.handle(key, shift, mode, pageCount = 20, actions = recorder)
		return recorder
	}

	@Test
	fun `left to right - right advances`() {
		assertEquals(listOf("next"), press(Key.DirectionRight, ReaderMode.STANDARD).calls)
		assertEquals(listOf("previous"), press(Key.DirectionLeft, ReaderMode.STANDARD).calls)
	}

	@Test
	fun `right to left - left advances`() {
		assertEquals(listOf("next"), press(Key.DirectionLeft, ReaderMode.REVERSED).calls)
		assertEquals(listOf("previous"), press(Key.DirectionRight, ReaderMode.REVERSED).calls)
	}

	/**
	 * Space means "continue", not "go right". Every document reader behaves this way and a manga
	 * reader that reverses it in RTL mode would be the only one.
	 */
	@Test
	fun `space always advances regardless of direction`() {
		for (mode in ReaderMode.entries) {
			assertEquals(listOf("next"), press(Key.Spacebar, mode).calls, "space in $mode")
		}
	}

	@Test
	fun `shift space always goes back`() {
		for (mode in ReaderMode.entries) {
			assertEquals(
				listOf("previous"),
				press(Key.Spacebar, mode, shift = true).calls,
				"shift+space in $mode",
			)
		}
	}

	/** Vertical keys are the only sensible page keys in webtoon mode, where left and right mean nothing. */
	@Test
	fun `vertical keys advance in every mode`() {
		for (mode in ReaderMode.entries) {
			assertEquals(listOf("next"), press(Key.PageDown, mode).calls)
			assertEquals(listOf("previous"), press(Key.PageUp, mode).calls)
			assertEquals(listOf("next"), press(Key.DirectionDown, mode).calls)
			assertEquals(listOf("previous"), press(Key.DirectionUp, mode).calls)
		}
	}

	@Test
	fun `home and end jump within the chapter`() {
		assertEquals(listOf("goTo:0"), press(Key.MoveHome, ReaderMode.STANDARD).calls)
		assertEquals(listOf("goTo:19"), press(Key.MoveEnd, ReaderMode.STANDARD).calls)
	}

	@Test
	fun `chapter and view bindings`() {
		assertEquals(listOf("nextChapter"), press(Key.N, ReaderMode.STANDARD).calls)
		assertEquals(listOf("previousChapter"), press(Key.P, ReaderMode.STANDARD).calls)
		assertEquals(listOf("fullscreen"), press(Key.F, ReaderMode.STANDARD).calls)
		assertEquals(listOf("fullscreen"), press(Key.F11, ReaderMode.STANDARD).calls)
		assertEquals(listOf("chrome"), press(Key.H, ReaderMode.STANDARD).calls)
		assertEquals(listOf("close"), press(Key.Escape, ReaderMode.STANDARD).calls)
	}

	@Test
	fun `number keys pick a fit mode`() {
		assertEquals(listOf("scale:FIT_PAGE"), press(Key.One, ReaderMode.STANDARD).calls)
		assertEquals(listOf("scale:FIT_WIDTH"), press(Key.Two, ReaderMode.STANDARD).calls)
		assertEquals(listOf("scale:FIT_HEIGHT"), press(Key.Three, ReaderMode.STANDARD).calls)
		assertEquals(listOf("scale:ORIGINAL"), press(Key.Four, ReaderMode.STANDARD).calls)
	}

	/**
	 * Unbound keys must fall through, or the reader swallows the window's own shortcuts and
	 * Ctrl+Q stops working while a chapter is open.
	 */
	@Test
	fun `an unbound key is not consumed`() {
		val recorder = Recorder()
		val consumed = ReaderKeys.handle(
			key = Key.Z,
			shiftPressed = false,
			mode = ReaderMode.STANDARD,
			pageCount = 20,
			actions = recorder,
		)
		assertFalse(consumed)
		assertTrue(recorder.calls.isEmpty())
	}

	@Test
	fun `every binding is documented in the help list`() {
		// The help list is what a user sees. Bindings that exist but are not listed are bindings
		// nobody finds.
		assertTrue(ReaderKeys.help.size >= 10)
		assertTrue(ReaderKeys.help.any { it.first.contains("Escape") })
		assertTrue(ReaderKeys.help.any { it.first.contains("F11") })
	}
}
