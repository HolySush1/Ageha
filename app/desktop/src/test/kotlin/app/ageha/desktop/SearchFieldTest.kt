package app.ageha.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import app.ageha.core.designsystem.AgehaSearchField
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test

/**
 * The contract of [AgehaSearchField].
 *
 * **Read this before trusting it.** These tests pin the component's behaviour -- text arrives in
 * order, typing continues from the caret, an outside change is adopted -- but they do **not**
 * reproduce the defect the component was written to fix, and they pass against the naive
 * `OutlinedTextField(value = query, onValueChange = ...)` it replaced. That was checked, not
 * assumed.
 *
 * The reason is [performTextInput]: it drives the field through the `InsertTextAtCursor`
 * semantics action, which edits the field's internal value directly. The caret desync happens a
 * layer below that, in the platform text-input session, when the field recomposes with a `value`
 * that is one dispatch behind the keystroke and `BasicTextField`'s selection-sync `SideEffect`
 * writes the clamped caret back over the real one. No input path reachable from a Compose UI test
 * goes through that code, so the bug cannot be expressed here.
 *
 * So these are regression tests for the component, not proof that the original bug is fixed. That
 * fix is by construction: [AgehaSearchField] owns its own `TextFieldValue` and never composes from
 * a stale external string, so the sequence that clamps the caret cannot occur. Confirming it end
 * to end means typing into the running app -- there is no substitute here.
 */
@OptIn(ExperimentalTestApi::class)
class SearchFieldTest {

	@Test
	fun `text arrives in the order it was typed`() = runComposeUiTest {
		setContent { SearchFieldHarness() }

		onNodeWithTag(FIELD).performTextInput("abc")
		waitForIdle()

		onNodeWithTag(FIELD).assertTextEquals("abc")
	}

	/** Typing into a box that already holds text appends rather than prepends. */
	@Test
	fun `typing continues from the caret rather than from the start`() = runComposeUiTest {
		setContent { SearchFieldHarness() }

		onNodeWithTag(FIELD).performTextInput("one")
		waitForIdle()
		onNodeWithTag(FIELD).performTextInput(" two")
		waitForIdle()

		onNodeWithTag(FIELD).assertTextEquals("one two")
	}

	/**
	 * The other direction still has to work. A field that simply ignored its `value` parameter
	 * would pass both tests above and then fail to notice a query cleared from anywhere else --
	 * a "clear search" button, a restored screen state.
	 */
	@Test
	fun `an outside change to the query is adopted`() = runComposeUiTest {
		val query = MutableStateFlow("")
		setContent { SearchFieldHarness(query) }

		onNodeWithTag(FIELD).performTextInput("something")
		waitForIdle()
		query.value = "replaced"
		waitForIdle()

		onNodeWithTag(FIELD).assertTextEquals("replaced")
	}

	/**
	 * Upstream running a beat behind must not eat what was typed.
	 *
	 * This is the defect that reached a user as *"every time I type a letter the cursor goes behind
	 * the letter"*, and unlike the caret-clamping bug in the class comment above, it **is**
	 * reproducible here -- because it is not about the platform text session at all. It is about
	 * how the component reacts to a `value` that is one step stale, which is an ordinary parameter
	 * this test can supply directly.
	 *
	 * The harness echoes each emission back *one behind*, which is exactly what a view model does
	 * when a recomposition lands between the keystroke and the state update. Against the previous
	 * implementation the field adopted that stale string, wiped the character and reset the caret;
	 * this asserts it no longer does.
	 */
	@Test
	fun `a stale echo from upstream does not wipe what was typed`() = runComposeUiTest {
		val seen = mutableListOf("")
		// What the field is handed: always the *previous* thing it emitted.
		val lagging = MutableStateFlow("")
		setContent {
			AgehaTheme(mode = AgehaThemeMode.EMBER) {
				val value by lagging.collectAsState()
				AgehaSearchField(
					value = value,
					onValueChange = { emitted ->
						// Publish the previous value, then remember the new one -- a one-step lag.
						lagging.value = seen.last()
						seen += emitted
					},
					placeholder = "Search",
					modifier = Modifier.testTag(FIELD),
				)
			}
		}

		onNodeWithTag(FIELD).performTextInput("overlord")
		waitForIdle()

		onNodeWithTag(FIELD).assertTextEquals("overlord")
	}

	/**
	 * A search field wired the way every screen in Ageha wires one: the query is hoisted out to a
	 * `StateFlow` and read back through `collectAsState`, rather than held in a local `remember`.
	 */
	@Composable
	private fun SearchFieldHarness(query: MutableStateFlow<String> = remember { MutableStateFlow("") }) {
		AgehaTheme(mode = AgehaThemeMode.EMBER) {
			val value by query.collectAsState()
			AgehaSearchField(
				value = value,
				onValueChange = { query.value = it },
				placeholder = "Search",
				modifier = Modifier.testTag(FIELD),
			)
		}
	}

	private companion object {
		const val FIELD = "search-field"
	}
}
