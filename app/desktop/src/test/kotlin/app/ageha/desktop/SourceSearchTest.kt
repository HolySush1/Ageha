package app.ageha.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.feature.explore.DEFAULTS_BUTTON_TAG
import app.ageha.feature.explore.ExploreUiState
import app.ageha.feature.explore.SOURCE_SEARCH_TAG
import app.ageha.feature.explore.SourcePickerScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Typing into the source picker's search box.
 *
 * This exists because the bug it covers has now shipped twice. `AgehaSearchField` was written to
 * fix it, every screen adopted it, and then the source picker was redrawn with the handoff's glass
 * chrome -- a bare `BasicTextField` handed `state.query` and `onSearch`, which is the original
 * broken call in a new coat. Searching sources went back to dropping and reordering characters
 * while `SearchFieldTest` stayed green, because that test only ever composes the component the
 * picker had stopped using.
 *
 * So this drives the real screen. Whatever chrome the picker grows next, this fails if its field
 * is wired straight to the hoisted query again.
 */
@OptIn(ExperimentalTestApi::class)
class SourceSearchTest {

	/**
	 * The view model echo lands a beat behind the keystroke, as `stateIn` always does. The field
	 * must keep what was typed rather than compose from the stale string.
	 */
	@Test
	fun `typing survives a view model that is one step behind`() = runComposeUiTest {
		val typed = mutableListOf<String>()
		val seen = mutableListOf("")
		// What the screen is handed: always the *previous* thing the field emitted.
		val lagging = MutableStateFlow("")

		setContent {
			Picker(lagging) { emitted ->
				typed += emitted
				lagging.value = seen.last()
				seen += emitted
			}
		}

		onNodeWithTag(SOURCE_SEARCH_TAG).performTextInput("naver")
		waitForIdle()

		onNodeWithTag(SOURCE_SEARCH_TAG).assertTextEquals("naver")
		// And the view model was told -- a field that kept its own text on screen while never
		// publishing it would leave the list unfiltered, which looks like a broken search from
		// the other side of the same box.
		assertEquals("naver", typed.last())
	}

	/**
	 * Clearing the box has to reach the view model even when it lands before the previous
	 * keystroke has been echoed back. Otherwise the field reads empty while the list stays
	 * filtered by a query the user can no longer see -- the worst version of this bug, because
	 * there is nothing left on screen to explain the results.
	 */
	@Test
	fun `deleting the query before the echo arrives still clears the search`() = runComposeUiTest {
		val typed = mutableListOf<String>()
		val query = MutableStateFlow("")

		setContent { Picker(query) { emitted -> typed += emitted } }

		onNodeWithTag(SOURCE_SEARCH_TAG).performTextInput("a")
		// No `waitForIdle` between the two: the deletion is made while the emission of "a" is
		// still outstanding, which is the case that used to be swallowed.
		onNodeWithTag(SOURCE_SEARCH_TAG).performTextClearance()
		waitForIdle()

		onNodeWithTag(SOURCE_SEARCH_TAG).assertTextEquals("")
		assertEquals("", typed.last())
	}

	/**
	 * The button that turns on the default English sources.
	 *
	 * It is the one control on this screen that changes what you *have* rather than what you are
	 * looking at, so the two things worth pinning are that pressing it reaches the view model, and
	 * that it is not drawn once there is nothing left for it to do.
	 */
	@Test
	fun `the default sources button says how many it would enable, and fires`() = runComposeUiTest {
		var pressed = 0
		setContent {
			Picker(MutableStateFlow(""), defaultsOff = 37, onEnableDefaults = { pressed += 1 }) {}
		}

		onNodeWithTag(DEFAULTS_BUTTON_TAG).assertTextContains("+37")
		onNodeWithTag(DEFAULTS_BUTTON_TAG).performClick()
		waitForIdle()

		assertEquals(1, pressed)
	}

	@Test
	fun `the default sources button is gone once every default is on`() = runComposeUiTest {
		setContent { Picker(MutableStateFlow(""), defaultsOff = 0) {} }

		onNodeWithTag(DEFAULTS_BUTTON_TAG).assertDoesNotExist()
	}

	@Composable
	private fun Picker(
		query: MutableStateFlow<String>,
		defaultsOff: Int = 0,
		onEnableDefaults: () -> Unit = {},
		onSearch: (String) -> Unit,
	) {
		val value by query.collectAsState()
		AgehaTheme(mode = AgehaThemeMode.EMBER) {
			SourcePickerScreen(
				state = ExploreUiState(query = value, isLoading = false, defaultsOff = defaultsOff),
				onOpenSource = {},
				onSearch = onSearch,
				onFilter = {},
				onLocale = {},
				onHideBroken = {},
				onShowAdult = {},
				onSetEnabled = { _, _ -> },
				onEnableDefaults = onEnableDefaults,
			)
		}
	}
}
