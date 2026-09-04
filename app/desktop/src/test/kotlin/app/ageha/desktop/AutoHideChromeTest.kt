package app.ageha.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import app.ageha.feature.reader.AutoHideChrome
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When the reader's chrome is allowed to hide itself.
 *
 * The bug this pins is a timing one, and it was reported the way timing bugs always are -- "the
 * top bar disappears after a click, it should stay until I move the cursor". The old timer was
 * keyed on page turns alone, so a bar someone had deliberately clicked to reveal counted down and
 * vanished while they were still moving the mouse towards it. Nothing about that is visible in the
 * code; it needs a clock.
 *
 * `mainClock.autoAdvance = false` is what makes these deterministic: the idle delay is advanced
 * explicitly, so the assertions are about *whether* the timer fires rather than about whether the
 * test machine was fast enough.
 */
@OptIn(ExperimentalTestApi::class)
class AutoHideChromeTest {

	/** Comfortably past the 2.5s idle period. */
	private val pastIdle = 4_000L

	@Test
	fun `hides once the pointer has moved and then gone quiet`() = runComposeUiTest {
		var hidden = false
		mainClock.autoAdvance = false
		setContent {
			AutoHideChrome(activity = 1, isVisible = true, onHide = { hidden = true }, suppressed = false)
		}

		mainClock.advanceTimeBy(pastIdle)
		assertTrue(hidden, "an idle reader should eventually get its artwork back")
	}

	/**
	 * The reported bug, stated directly: the pointer has not moved since the chrome appeared, so
	 * the countdown must not have started.
	 */
	@Test
	fun `stays up until the pointer has moved`() = runComposeUiTest {
		var hidden = false
		mainClock.autoAdvance = false
		setContent {
			AutoHideChrome(activity = 0, isVisible = true, onHide = { hidden = true }, suppressed = true)
		}

		mainClock.advanceTimeBy(pastIdle)
		assertFalse(hidden, "a bar that was just revealed must not vanish before the pointer moves")
	}

	/** Reaching the bar with the mouse and holding it there must not time the bar out. */
	@Test
	fun `does not hide while it is being hovered`() = runComposeUiTest {
		var hidden = false
		var hovering by mutableStateOf(false)
		mainClock.autoAdvance = false
		setContent {
			AutoHideChrome(
				activity = 1,
				isVisible = true,
				onHide = { hidden = true },
				suppressed = hovering,
			)
		}

		// Halfway through the idle period the pointer arrives on the bar.
		mainClock.advanceTimeBy(1_000L)
		hovering = true
		mainClock.advanceTimeBy(pastIdle)
		assertFalse(hidden, "the countdown must be suspended while the pointer is on the chrome")

		// And resumes -- from the start -- once the pointer leaves.
		hovering = false
		mainClock.advanceTimeBy(pastIdle)
		assertTrue(hidden, "leaving the chrome should let it fade again")
	}

	/** Each movement restarts the clock, so a moving pointer never loses the chrome under it. */
	@Test
	fun `pointer movement restarts the countdown`() = runComposeUiTest {
		var hidden = false
		var moves by mutableIntStateOf(0)
		mainClock.autoAdvance = false
		setContent {
			AutoHideChrome(activity = moves, isVisible = true, onHide = { hidden = true }, suppressed = false)
		}

		repeat(5) {
			mainClock.advanceTimeBy(2_000L)
			assertFalse(hidden, "2s of waiting between movements is not 2.5s of stillness")
			moves++
		}
	}

	@Test
	fun `hidden chrome does not try to hide itself again`() = runComposeUiTest {
		var hides = 0
		mainClock.autoAdvance = false
		setContent {
			AutoHideChrome(activity = 1, isVisible = false, onHide = { hides++ }, suppressed = false)
		}

		mainClock.advanceTimeBy(pastIdle)
		assertTrue(hides == 0, "chrome that is already hidden has nothing to do")
	}
}
