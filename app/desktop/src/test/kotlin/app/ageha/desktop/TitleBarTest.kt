package app.ageha.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The window caption Ageha draws instead of the one Windows would have.
 *
 * Drawing your own caption means owning every behaviour the real one had, and the failure mode is
 * always the same: it *looks* right in a screenshot and is wrong under a pointer. These are the
 * properties that were actually broken, pinned so they cannot come back.
 */
@OptIn(ExperimentalTestApi::class)
class TitleBarTest {

	@Composable
	private fun TitleBar(
		isMaximized: Boolean = false,
		onMinimize: () -> Unit = {},
		onToggleMaximize: () -> Unit = {},
		onClose: () -> Unit = {},
	) {
		AgehaTheme(mode = AgehaThemeMode.EMBER) {
			AgehaTitleBar(
				context = "Library - 12 titles - 312 chapters cached",
				theme = AgehaThemeMode.EMBER,
				onTheme = {},
				onMinimize = onMinimize,
				onToggleMaximize = onToggleMaximize,
				onClose = onClose,
				modifier = Modifier.width(900.dp).fillMaxWidth(),
				isMaximized = isMaximized,
			)
		}
	}

	/**
	 * The controls sit against the right edge.
	 *
	 * The regression this exists for: the context line carried `weight(1f, fill = false)` and was
	 * followed by a `Spacer(Modifier.weight(1f))`. A `Row` divides its leftover space *between*
	 * weighted children, so the spacer only ever received half of it -- and under the default
	 * `Arrangement.Start` the half the text declined to fill collected after the last child
	 * instead. The skin switcher and the three window buttons floated in the middle of the bar
	 * with a dead gap between them and the corner, which no screenshot of a long context line
	 * would have shown.
	 */
	@Test
	fun `the window controls are pinned to the right edge`() = runComposeUiTest {
		setContent { TitleBar() }

		val bar = onRoot().getUnclippedBoundsInRoot()
		val close = onNodeWithTag(WINDOW_CLOSE_TAG).getUnclippedBoundsInRoot()

		assertEquals(
			bar.right.value,
			close.right.value,
			0.5f,
			"the close button must reach the window's own edge, as it does in every Windows caption",
		)
		// And the row is in Windows' order, so the corner is close and nothing else.
		val maximize = onNodeWithTag(WINDOW_MAXIMIZE_TAG).getUnclippedBoundsInRoot()
		val minimize = onNodeWithTag(WINDOW_MINIMIZE_TAG).getUnclippedBoundsInRoot()
		assertTrue(minimize.right.value <= maximize.left.value + 0.5f, "minimise sits left of maximise")
		assertTrue(maximize.right.value <= close.left.value + 0.5f, "maximise sits left of close")
	}

	/** The skin switcher is pinned too -- it is the control immediately left of the buttons. */
	@Test
	fun `the skin switcher sits beside the window buttons`() = runComposeUiTest {
		setContent { TitleBar() }

		val glass = onNodeWithTag(SKIN_GLASS_TAG).getUnclippedBoundsInRoot()
		val minimize = onNodeWithTag(WINDOW_MINIMIZE_TAG).getUnclippedBoundsInRoot()
		val bar = onRoot().getUnclippedBoundsInRoot()

		assertTrue(glass.right.value <= minimize.left.value, "the switcher sits left of the buttons")
		// Within a switcher's own width of the buttons rather than adrift in the middle of the bar.
		assertTrue(
			minimize.left.value - glass.right.value < 32f,
			"the skin switcher has drifted away from the window buttons",
		)
		assertTrue(glass.right.value > bar.right.value / 2f, "the switcher is in the right half of the bar")
	}

	/** Each button asks for the thing it is named after, and only that. */
	@Test
	fun `each window button drives its own action`() = runComposeUiTest {
		val calls = mutableListOf<String>()
		setContent {
			TitleBar(
				onMinimize = { calls += "minimize" },
				onToggleMaximize = { calls += "maximize" },
				onClose = { calls += "close" },
			)
		}

		onNodeWithTag(WINDOW_MINIMIZE_TAG).performClick()
		onNodeWithTag(WINDOW_MAXIMIZE_TAG).performClick()
		onNodeWithTag(WINDOW_CLOSE_TAG).performClick()

		assertEquals(listOf("minimize", "maximize", "close"), calls)
	}

	/**
	 * The middle button is two buttons, and says which one it currently is.
	 *
	 * A caption that keeps offering to maximise a window already filling the screen is the clearest
	 * tell that it was drawn rather than provided -- and for anyone using a screen reader it is not
	 * a tell, it is the button being mislabelled.
	 */
	@Test
	fun `the middle button offers to maximise a floating window`() = runComposeUiTest {
		setContent { TitleBar(isMaximized = false) }
		onNodeWithContentDescription("Maximise").assertIsDisplayed()
	}

	@Test
	fun `the middle button offers to restore a maximised window`() = runComposeUiTest {
		setContent { TitleBar(isMaximized = true) }
		onNodeWithContentDescription("Restore").assertIsDisplayed()
	}
}
