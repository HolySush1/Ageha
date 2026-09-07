package app.ageha.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.MANGA_CARD_TAG
import app.ageha.core.model.AgehaManga
import app.ageha.feature.explore.BrowseScreen
import app.ageha.feature.explore.BrowseUiState
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A source listing has to keep paging as the user scrolls.
 *
 * A regression test for a specific, shipped bug: every source stopped dead after its first page, so
 * browsing anything showed twenty titles and then nothing, however far you scrolled.
 *
 * The cause was entirely in the Compose layer, which is why it survived a view model that was
 * correct and covered. `BrowseScreen` hoisted a `derivedStateOf` out of a
 * `remember(state.manga.size, state.hasMore)` and collected it from a `LaunchedEffect` keyed only
 * on the source name. Recomposition built a fresh derived state for every page; the coroutine went
 * on observing the *first* one, which had captured `manga.size == 0`. `snapshotFlow` emits only on
 * change, so that stale predicate emitted `true` exactly once -- while the first page was already
 * in flight and `loadMore` returned early -- and never again.
 *
 * So the test has to be a real composition, really scrolled. A view model test cannot see this,
 * and neither could reading the code, which is how it shipped.
 */
@OptIn(ExperimentalTestApi::class)
class BrowsePagingTest {

	@Test
	fun `scrolling keeps asking for pages`() = runComposeUiTest {
		var pagesLoaded = 1
		setContent { PagingHarness(onPageLoaded = { pagesLoaded = it }) }
		waitForIdle()

		// Not asserted as a fixed number. One page of twenty covers nearly fills a test window, so
		// the prefetch threshold is already met before anything is scrolled and a second page
		// arrives on its own. That is the intended behaviour -- paging early is the whole point of
		// PREFETCH_DISTANCE -- and pinning it to a number here would make this test a hostage to
		// the default window size.
		val beforeScrolling = pagesLoaded
		assertTrue(beforeScrolling >= 1) { "no page was ever requested" }

		// Scroll to the end of what is loaded, several times over. Each pass should bring in the
		// next page, which moves the end further away, which is the loop the bug broke.
		//
		// Driven through the grid's own `ScrollToIndex` semantics rather than with a mouse wheel:
		// a wheel gesture has to be tuned to the window size and the row height to be sure it
		// actually reaches the end, and a test that silently under-scrolls would report the bug as
		// fixed for the wrong reason. Asking for the last loaded index says what is meant.
		repeat(SCROLLS) {
			val lastLoaded = pagesLoaded * PAGE_SIZE - 1
			onNode(hasScrollToIndexAction()).performScrollToIndex(lastLoaded)
			waitForIdle()
		}

		val cards = onAllNodesWithTag(MANGA_CARD_TAG).fetchSemanticsNodes().size
		// Two *further* pages, and that bound is the point of the test rather than a round number.
		// The old code's stale predicate went true the moment the grid was laid out, fired once,
		// and -- being stale -- never changed value or emitted again. So the bug's signature is
		// not "no pages" but "exactly one more page, then silence", and only a strictly growing
		// count tells the fix apart from it.
		assertTrue(pagesLoaded >= beforeScrolling + 2) {
			"the listing stopped paging: $beforeScrolling page(s) before scrolling, " +
				"$pagesLoaded after, $cards cards composed"
		}
	}

	/**
	 * A harness that behaves the way a source does: hand back a page, then wait to be asked again.
	 *
	 * Synchronous rather than suspending, deliberately. The bug was that the request was never
	 * *made*; adding latency here would only introduce a way for the test to be flaky about a race
	 * it is not testing.
	 */
	@Composable
	private fun PagingHarness(onPageLoaded: (Int) -> Unit) {
		var state by remember {
			mutableStateOf(
				BrowseUiState(
					sourceName = "test",
					sourceTitle = "Test source",
					manga = (0 until PAGE_SIZE).map(::sampleManga),
				),
			)
		}
		var pages by remember { mutableStateOf(1) }
		AgehaTheme(mode = AgehaThemeMode.EMBER) {
			BrowseScreen(
				state = state,
				onOpenManga = {},
				onSort = {},
				onSearch = {},
				onSubmitSearch = {},
				onLoadMore = {
					val loaded = state.manga.size
					val next = (0 until PAGE_SIZE).map { index -> sampleManga(loaded + index) }
					state = state.copy(manga = state.manga + next, hasMore = true)
					pages += 1
					onPageLoaded(pages)
				},
				onRetry = {},
				modifier = Modifier.fillMaxSize(),
			)
		}
	}

	private fun sampleManga(index: Int) = AgehaManga(
		id = index.toLong(),
		title = "Title $index",
		altTitles = emptySet(),
		url = "/manga/$index",
		publicUrl = "https://example.invalid/manga/$index",
		rating = null,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = null,
		sourceName = "test",
	)

	private companion object {
		/** What a source hands back per request. Twenty is the usual page size, and the reported one. */
		const val PAGE_SIZE = 20

		/** Enough passes that a listing which pages will visibly overtake one that does not. */
		const val SCROLLS = 6
	}
}
