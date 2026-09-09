package app.ageha.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.SourceFailure
import app.ageha.feature.explore.DetailsScreen
import app.ageha.feature.explore.DetailsUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * "Find another source", from the chapter header.
 *
 * The behaviour worth pinning is not that the button exists -- it is *when* it exists. It sits in
 * a row whose other button, Download all, is correctly hidden when there are no chapters, and the
 * obvious tidy-up is to fold this one under the same guard. That would remove it from the two
 * screens where it is the only useful control left: a source that returned an empty chapter list,
 * and one that failed outright. Both are tested below so the guard cannot be added back.
 */
@OptIn(ExperimentalTestApi::class)
class FindAnotherSourceTest {

	private val manga = AgehaManga(
		id = 1L,
		title = "Vagabond",
		altTitles = emptySet(),
		url = "/m/1",
		publicUrl = "https://test/m/1",
		rating = null,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = null,
		sourceName = "TEST",
	)

	private fun chapter(id: Long) = AgehaChapter(
		id = id,
		title = "Chapter $id",
		number = id.toFloat(),
		volume = null,
		url = "/c/$id",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		sourceName = "TEST",
	)

	@Test
	fun `the chapter header offers a cross-source search`() = runComposeUiTest {
		var searched = 0
		setContent {
			AgehaTheme(mode = AgehaThemeMode.EMBER) {
				DetailsScreen(
					state = DetailsUiState(manga = manga, chapters = listOf(chapter(1), chapter(2))),
					onOpenChapter = {},
					onDownloadChapter = {},
					onDownloadAll = {},
					onToggleCategory = {},
					onAddToLibrary = {},
					onRemoveFromLibrary = {},
					onSelectBranch = {},
					onRetry = {},
					onFindElsewhere = { searched++ },
				)
			}
		}
		onNodeWithText("Find another source").assertIsDisplayed().performClick()
		assertEquals(1, searched)
	}

	@Test
	fun `it survives a source that returned no chapters`() = runComposeUiTest {
		setContent {
			AgehaTheme(mode = AgehaThemeMode.EMBER) {
				DetailsScreen(
					state = DetailsUiState(manga = manga, chapters = emptyList()),
					onOpenChapter = {},
					onDownloadChapter = {},
					onDownloadAll = {},
					onToggleCategory = {},
					onAddToLibrary = {},
					onRemoveFromLibrary = {},
					onSelectBranch = {},
					onRetry = {},
				)
			}
		}
		// Download all is gone, as it should be -- there is nothing to download.
		onNodeWithText("Download all").assertDoesNotExist()
		onNodeWithText("Find another source").assertIsDisplayed()
	}

	@Test
	fun `it survives a source that failed outright`() = runComposeUiTest {
		setContent {
			AgehaTheme(mode = AgehaThemeMode.EMBER) {
				DetailsScreen(
					state = DetailsUiState(
						manga = manga,
						chapters = emptyList(),
						failure = SourceFailure.Network("TEST", java.io.IOException("connection reset")),
					),
					onOpenChapter = {},
					onDownloadChapter = {},
					onDownloadAll = {},
					onToggleCategory = {},
					onAddToLibrary = {},
					onRemoveFromLibrary = {},
					onSelectBranch = {},
					onRetry = {},
				)
			}
		}
		onNodeWithText("Find another source").assertIsDisplayed()
	}
}
