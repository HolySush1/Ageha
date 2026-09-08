package app.ageha.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.ReaderBackground
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaPage
import app.ageha.feature.reader.CHAPTER_LIST_TAG
import app.ageha.feature.reader.NEXT_CHAPTER_TAG
import app.ageha.feature.reader.PAGE_COUNTER_TAG
import app.ageha.feature.reader.PREV_CHAPTER_TAG
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
	 * The chapter buttons are up before the pages are.
	 *
	 * The bar used to hide itself entirely until a page list arrived, which was right about the
	 * readouts and wrong about the way out: a chapter that is loading slowly, or that has failed,
	 * is exactly when someone wants to skip it or go back to the list. Hiding the numbers is
	 * honesty; hiding the buttons with them is a dead end.
	 */
	@Test
	fun `the chapter controls are available before any page has loaded`() = runComposeUiTest {
		setContent { Reader(ReaderUiState(manga = manga, chapter = chapter, isLoading = true)) }

		onNodeWithTag(CHAPTER_LIST_TAG).assertIsDisplayed()
		onNodeWithTag(NEXT_CHAPTER_TAG).assertIsDisplayed()
		onNodeWithTag(PREV_CHAPTER_TAG).assertIsDisplayed()
	}

	/**
	 * At the ends of a manga the buttons are disabled, not missing.
	 *
	 * A control that vanishes takes the other two with it as the row reflows, and the first
	 * chapter is precisely where someone is still learning where these buttons are.
	 */
	@Test
	fun `chapter buttons are disabled at the ends and enabled in the middle`() = runComposeUiTest {
		setContent {
			Reader(
				ReaderUiState(
					manga = manga,
					chapter = chapter,
					chapterIndex = 0,
					chapterCount = 3,
					pages = List(4) { index -> page(index) },
				),
			)
		}

		onNodeWithTag(PREV_CHAPTER_TAG).assertIsNotEnabled()
		onNodeWithTag(NEXT_CHAPTER_TAG).assertIsEnabled()
		onNodeWithTag(CHAPTER_LIST_TAG).assertIsEnabled()
	}

	/** Each chapter button asks for the thing it is named after. */
	@Test
	fun `each chapter button drives its own action`() = runComposeUiTest {
		val calls = mutableListOf<String>()
		setContent {
			Reader(
				ReaderUiState(
					manga = manga,
					chapter = chapter,
					chapterIndex = 1,
					chapterCount = 3,
					pages = List(4) { index -> page(index) },
				),
				calls,
			)
		}

		onNodeWithTag(PREV_CHAPTER_TAG).performClick()
		onNodeWithTag(CHAPTER_LIST_TAG).performClick()
		onNodeWithTag(NEXT_CHAPTER_TAG).performClick()

		assertEquals(listOf("previous", "chapters", "next"), calls)
	}

	/**
	 * [ReaderScreen] with every callback stubbed.
	 *
	 * This test is about what the screen draws, not what it does, and a screen with seventeen
	 * parameters needs that noise in one place rather than in every test.
	 */
	@Composable
	private fun Reader(state: ReaderUiState, calls: MutableList<String> = mutableListOf()) {
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
				onNextChapter = { calls += "next" },
				onPreviousChapter = { calls += "previous" },
				onOpenChapterList = { calls += "chapters" },
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
