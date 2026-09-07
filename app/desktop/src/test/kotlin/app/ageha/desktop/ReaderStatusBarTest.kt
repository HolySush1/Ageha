package app.ageha.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.ReaderBackground
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaPage
import app.ageha.feature.reader.PAGE_COUNTER_TAG
import app.ageha.feature.reader.ReaderPage
import app.ageha.feature.reader.ReaderScreen
import app.ageha.feature.reader.ReaderUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The reader's status bar tells the truth or says nothing.
 *
 * Everything it shows -- `n / m`, `Chapter i of j` -- is derived from a page list, so before that
 * list arrives it has nothing to report. It used to report anyway: a chapter opened from a live
 * source displayed **"1 / 0"** and **"Chapter 1 of 0"** for as long as the source took to answer,
 * which on a slow site is several seconds of the app stating something false with confidence.
 *
 * Found by [AgehaJourneyTest], which asked a live MangaDex chapter how many pages it had the
 * moment the reader appeared and was told zero. Pinned here rather than there because this is a
 * question about one screen in two states, and it should not need the network to answer.
 */
@OptIn(ExperimentalTestApi::class)
class ReaderStatusBarTest {

	@Test
	fun `hides the counter until pages arrive`() = runComposeUiTest {
		setContent { Reader(ReaderUiState(manga = manga, chapter = chapter, isLoading = true)) }

		assertEquals(
			0,
			onAllNodesWithTag(PAGE_COUNTER_TAG).fetchSemanticsNodes().size,
			"a reader with no pages yet must not claim to be on page 1 of 0",
		)
	}

	@Test
	fun `shows the counter once pages arrive`() = runComposeUiTest {
		setContent {
			Reader(
				ReaderUiState(
					manga = manga,
					chapter = chapter,
					chapterCount = 1,
					pages = List(12) { index -> page(index) },
				),
			)
		}

		onNodeWithTag(PAGE_COUNTER_TAG).assertIsDisplayed()
	}

	/**
	 * [ReaderScreen] with every callback stubbed.
	 *
	 * This test is about what the screen draws, not what it does, and a screen with seventeen
	 * parameters needs that noise in one place rather than in every test.
	 */
	@Composable
	private fun Reader(state: ReaderUiState) {
		AgehaTheme(mode = AgehaThemeMode.EMBER) {
			ReaderScreen(
				state = state,
				background = ReaderBackground.BLACK,
				doublePage = false,
				coverOffset = true,
				onPageChange = {},
				onScroll = { _, _ -> },
				onNextPage = {},
				onPreviousPage = {},
				onSetMode = {},
				onSetScale = {},
				onSetBackground = {},
				onToggleDoublePage = {},
				onToggleCoverOffset = {},
				onToggleChrome = {},
				onRetry = {},
				onClose = {},
				modifier = Modifier.fillMaxSize(),
			)
		}
	}

	private fun page(index: Int) = ReaderPage(
		page = AgehaPage(
			id = index.toLong(),
			url = "file:///page$index.png",
			preview = null,
			sourceName = "LOCAL",
		),
		index = index,
		resolvedUrl = "file:///page$index.png",
	)

	private val manga = AgehaManga(
		id = 1L,
		title = "Test",
		altTitles = emptySet(),
		url = "test",
		publicUrl = "test",
		rating = null,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = null,
		sourceName = "LOCAL",
	)

	private val chapter = AgehaChapter(
		id = 1L,
		title = "Chapter 1",
		number = 1f,
		volume = null,
		url = "test",
		scanlator = null,
		uploadDate = null,
		branch = null,
		sourceName = "LOCAL",
	)
}
