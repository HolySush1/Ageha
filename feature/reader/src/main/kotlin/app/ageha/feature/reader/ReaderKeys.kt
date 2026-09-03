package app.ageha.feature.reader

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import app.ageha.core.model.PageScale
import app.ageha.core.model.ReaderMode

/** What a key press can ask the reader to do. */
interface ReaderActions {
	fun nextPage()
	fun previousPage()
	fun nextChapter()
	fun previousChapter()
	fun goToPage(index: Int)
	fun setScale(scale: PageScale)
	fun toggleChrome()
	fun toggleFullscreen()
	fun close()
}

/**
 * The reader's keyboard.
 *
 * Full keyboard control is a brief requirement and on a desktop it is the primary interface, not
 * an accessibility afterthought: someone reading two hundred pages is holding a keyboard, not
 * reaching for a mouse three hundred times.
 *
 * **Arrow keys follow the reading direction, not the screen.** In right-to-left mode the next page
 * is to the *left*, so Left advances and Right goes back. Binding Right to "next" unconditionally
 * is the intuitive implementation and it is backwards for most of what a manga reader opens.
 *
 * Space and Page Down always mean "forward" regardless of direction, because they are not
 * directional keys -- they mean "continue", the way they do in a document.
 */
object ReaderKeys {

	/**
	 * Dispatch a key press.
	 *
	 * Takes the decoded key and modifier rather than a Compose `KeyEvent`, so the binding table is
	 * ordinary logic that can be tested directly. Compose's `KeyEvent` is a value class over an
	 * internal type that cannot be constructed outside the framework, and a binding table only
	 * reachable through a running window is a binding table that never gets tested.
	 */
	fun handle(
		key: Key,
		shiftPressed: Boolean,
		mode: ReaderMode,
		pageCount: Int,
		actions: ReaderActions,
	): Boolean {
		val rightToLeft = mode.isRightToLeft
		return when (key) {
			Key.DirectionRight -> {
				if (rightToLeft) actions.previousPage() else actions.nextPage()
				true
			}

			Key.DirectionLeft -> {
				if (rightToLeft) actions.nextPage() else actions.previousPage()
				true
			}

			// Vertical keys are unambiguous in every mode, and are the only sensible page keys in
			// webtoon mode where "left" and "right" mean nothing.
			Key.DirectionDown, Key.PageDown -> {
				actions.nextPage()
				true
			}

			Key.DirectionUp, Key.PageUp -> {
				actions.previousPage()
				true
			}

			// Shift+Space goes back, matching every document reader ever written.
			Key.Spacebar -> {
				if (shiftPressed) actions.previousPage() else actions.nextPage()
				true
			}

			Key.MoveHome -> {
				actions.goToPage(0)
				true
			}

			Key.MoveEnd -> {
				actions.goToPage(pageCount - 1)
				true
			}

			Key.N -> {
				actions.nextChapter()
				true
			}

			Key.P -> {
				actions.previousChapter()
				true
			}

			Key.F, Key.F11 -> {
				actions.toggleFullscreen()
				true
			}

			Key.H -> {
				actions.toggleChrome()
				true
			}

			Key.One -> {
				actions.setScale(PageScale.FIT_PAGE)
				true
			}

			Key.Two -> {
				actions.setScale(PageScale.FIT_WIDTH)
				true
			}

			Key.Three -> {
				actions.setScale(PageScale.FIT_HEIGHT)
				true
			}

			Key.Four -> {
				actions.setScale(PageScale.ORIGINAL)
				true
			}

			Key.Escape -> {
				actions.close()
				true
			}

			else -> false
		}
	}

	/** Adapts a Compose event to [handle]. The only Compose-aware line in the file. */
	fun handle(event: KeyEvent, mode: ReaderMode, pageCount: Int, actions: ReaderActions): Boolean =
		handle(event.key, event.isShiftPressed, mode, pageCount, actions)

	/** The bindings, for a help overlay and for the manual. */
	val help: List<Pair<String, String>> = listOf(
		"Left / Right" to "Turn the page, in reading order",
		"Space / Shift+Space" to "Forward / back",
		"Page Up / Page Down" to "Forward / back",
		"Home / End" to "First / last page of the chapter",
		"N / P" to "Next / previous chapter",
		"1 / 2 / 3 / 4" to "Fit page / width / height / original size",
		"F or F11" to "Fullscreen",
		"H" to "Show or hide the controls",
		"Ctrl + wheel" to "Zoom about the pointer",
		"Double-click" to "Toggle zoom",
		"Escape" to "Close the reader",
	)
}
