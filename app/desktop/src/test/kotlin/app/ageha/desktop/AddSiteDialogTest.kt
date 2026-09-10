package app.ageha.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.model.AgehaContentType
import app.ageha.core.model.SourceDescriptor
import app.ageha.feature.explore.ADD_SITE_BUTTON_TAG
import app.ageha.feature.explore.ADD_SITE_FIELD_TAG
import app.ageha.feature.explore.AddSiteDialog
import app.ageha.feature.explore.AddSiteState
import app.ageha.feature.explore.AddSiteStatus
import app.ageha.feature.explore.ExploreUiState
import app.ageha.feature.explore.SourcePickerScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The Add site button and dialog, as a person would drive them.
 *
 * The state machine behind them is covered in `AddSiteTest`. What is pinned here is what only a
 * composition can show: that the button is absent without a handler rather than inert, that the
 * dialog's actions reach their callbacks, and that the keyboard alone can finish the job.
 */
@OptIn(ExperimentalTestApi::class)
class AddSiteDialogTest {

	private val comix = SourceDescriptor(
		name = "COMIX",
		title = "Comix",
		locale = "en",
		contentType = AgehaContentType.MANGA,
		isBroken = false,
	)

	@Test
	fun `the Sources screen offers Add site when something will answer it`() = runComposeUiTest {
		var opened = 0
		setContent {
			AgehaTheme(mode = AgehaThemeMode.EMBER) {
				SourcePickerScreen(
					state = ExploreUiState(),
					onOpenSource = {},
					onSearch = {},
					onFilter = {},
					onLocale = {},
					onHideBroken = {},
					onShowAdult = {},
					onSetEnabled = { _, _ -> },
					onEnableDefaults = {},
					onAddSite = { opened++ },
				)
			}
		}
		onNodeWithTag(ADD_SITE_BUTTON_TAG).assertIsDisplayed().performClick()
		assertEquals(1, opened)
	}

	/** No handler, no button -- the lesson of the failure panel's dead remedy buttons. */
	@Test
	fun `without a handler there is no Add site button at all`() = runComposeUiTest {
		setContent {
			AgehaTheme(mode = AgehaThemeMode.EMBER) {
				SourcePickerScreen(
					state = ExploreUiState(),
					onOpenSource = {},
					onSearch = {},
					onFilter = {},
					onLocale = {},
					onHideBroken = {},
					onShowAdult = {},
					onSetEnabled = { _, _ -> },
					onEnableDefaults = {},
				)
			}
		}
		onNodeWithTag(ADD_SITE_BUTTON_TAG).assertDoesNotExist()
	}

	@Test
	fun `a found site opens from the dialog`() = runComposeUiTest {
		var openedSource = 0
		setContent {
			AgehaTheme(mode = AgehaThemeMode.EMBER) {
				AddSiteDialog(
					state = AddSiteState.Open("https://comix.to/", AddSiteStatus.Found("comix.to", comix, null)),
					onInput = {},
					onFind = {},
					onOpenSource = { openedSource++ },
					onOpenManga = {},
					onRequestUpstream = {},
					onDismiss = {},
				)
			}
		}
		onNodeWithText("comix.to  →  Comix").assertIsDisplayed()
		onNodeWithText("Open source").performClick()
		assertEquals(1, openedSource)
	}

	/** Paste, Enter: the lookup should not need the mouse. */
	@Test
	fun `Enter in the field finds the site`() = runComposeUiTest {
		var finds = 0
		setContent {
			AgehaTheme(mode = AgehaThemeMode.EMBER) {
				AddSiteDialog(
					state = AddSiteState.Open("comix.to"),
					onInput = {},
					onFind = { finds++ },
					onOpenSource = {},
					onOpenManga = {},
					onRequestUpstream = {},
					onDismiss = {},
				)
			}
		}
		onNodeWithTag(ADD_SITE_FIELD_TAG).performClick().performKeyInput { pressKey(Key.Enter) }
		assertEquals(1, finds)
	}

	@Test
	fun `a site no source reads offers the upstream request, not a dead end`() = runComposeUiTest {
		var requested = 0
		setContent {
			AgehaTheme(mode = AgehaThemeMode.EMBER) {
				AddSiteDialog(
					state = AddSiteState.Open(
						"https://example.com",
						AddSiteStatus.NotFound("example.com", "434030d481"),
					),
					onInput = {},
					onFind = {},
					onOpenSource = {},
					onOpenManga = {},
					onRequestUpstream = { requested++ },
					onDismiss = {},
				)
			}
		}
		onNodeWithText("Request it upstream").performClick()
		assertEquals(1, requested)
	}
}
