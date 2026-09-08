package app.ageha.feature.reader

import app.ageha.core.data.CatalogResult
import app.ageha.core.data.ReaderRepository
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaPage
import app.ageha.core.model.PageScale
import app.ageha.core.model.ReaderMode
import app.ageha.core.model.SourceFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One page, and the direct image url once it has been resolved. */
data class ReaderPage(
	val page: AgehaPage,
	val index: Int,
	val resolvedUrl: String? = null,
	val failure: SourceFailure? = null,
) {
	val key: String get() = "${page.sourceName}:${page.id}"
}

data class ReaderUiState(
	val manga: AgehaManga? = null,
	val chapter: AgehaChapter? = null,
	val chapterIndex: Int = 0,
	val chapterCount: Int = 0,
	val pages: List<ReaderPage> = emptyList(),
	val currentPage: Int = 0,
	val mode: ReaderMode = ReaderMode.DEFAULT,
	val scale: PageScale = PageScale.FIT_PAGE,
	val imageHeaders: Map<String, String> = emptyMap(),
	val isLoading: Boolean = false,
	val failure: SourceFailure? = null,
	/** Chrome is visible. Auto-hides while reading; any input brings it back. */
	val isChromeVisible: Boolean = true,
	/**
	 * Bumped every time something *asks* to move to a page, and never by scrolling.
	 *
	 * The webtoon strip needs to tell those two apart. It reports its own position as the user
	 * scrolls, so [currentPage] changes constantly under it -- a strip that scrolled to
	 * [currentPage] whenever that number moved would fight the hand that was moving it. But it
	 * also has to obey a seek from outside: before this, clicking a page tick in the bottom bar
	 * updated [currentPage], the strip ignored it, and the scroll listener promptly overwrote it
	 * with where the strip still was. The ticks did nothing at all in webtoon mode.
	 *
	 * A counter rather than a flag, because two seeks to the same page must both be honoured, and
	 * a boolean would need clearing afterwards -- which is a second state change racing the first.
	 */
	val seekId: Int = 0,
) {
	val pageCount: Int get() = pages.size
	val hasNextChapter: Boolean get() = chapterIndex < chapterCount - 1
	val hasPreviousChapter: Boolean get() = chapterIndex > 0
	val isAtLastPage: Boolean get() = pages.isNotEmpty() && currentPage >= pages.lastIndex
	val isAtFirstPage: Boolean get() = currentPage <= 0
}

/**
 * The reader.
 *
 * Three things here are not obvious and all three are about not abusing the source:
 *
 *  - **Page urls resolve lazily.** Some sources hand back a page list of interstitial urls that
 *    each need their own request to turn into an image. Resolving all of them up front would fire
 *    one request per page the moment a chapter opens, for pages the reader may never reach.
 *  - **Prefetch is bounded and directional.** A few pages ahead, not the chapter. The point is to
 *    hide latency for the next turn, not to download a chapter someone opened by accident.
 *  - **Position is saved on a debounce.** Turning pages quickly would otherwise be one database
 *    write per page, and the value being written is the same row every time.
 */
class ReaderViewModel(
	private val reader: ReaderRepository,
	private val scope: CoroutineScope,
	/**
	 * Settings' "Reading mode", for a manga that has never been given one of its own.
	 *
	 * A constructor parameter rather than something read on every open, because the shell already
	 * `remember`s this view model against the preferences it was built from -- changing the
	 * setting rebuilds it, which is the path every other preference here takes.
	 */
	private val defaultMode: ReaderMode = ReaderMode.DEFAULT,
	/** Settings' "Page fit". Applies on open; the reader's own chip still changes it live. */
	private val defaultScale: PageScale = PageScale.FIT_PAGE,
	/**
	 * Settings' "Preload next pages": how far ahead urls are resolved.
	 *
	 * Zero means the whole chapter. This is the *url* half of preloading -- some sources hand back
	 * a page list of interstitial urls that each need their own request before there is an image
	 * to fetch at all, so the depth has to be honoured here as well as in the image cache, or the
	 * setting would prefetch twelve images out of the four urls it had bothered to resolve.
	 */
	private val preloadAhead: Int = AHEAD,
) {

	private val _state = MutableStateFlow(ReaderUiState())
	val state: StateFlow<ReaderUiState> = _state.asStateFlow()

	private var loadJob: Job? = null
	private var saveJob: Job? = null
	private var chapters: List<AgehaChapter> = emptyList()

	/**
	 * Open a manga at a chapter.
	 *
	 * @param startPage where to resume. -1 means "wherever the history says", which is the normal
	 *   case; an explicit page is used when the user picked a chapter from the list, where
	 *   resuming mid-chapter would be wrong.
	 */
	fun open(manga: AgehaManga, chapter: AgehaChapter, startPage: Int = -1) {
		loadJob?.cancel()
		chapters = manga.chaptersByBranch()[chapter.branch].orEmpty()
		val index = chapters.indexOfFirst { it.id == chapter.id }.coerceAtLeast(0)
		_state.value = ReaderUiState(
			manga = manga,
			chapter = chapter,
			chapterIndex = index,
			chapterCount = chapters.size,
			imageHeaders = reader.imageHeaders(manga.sourceName),
			mode = defaultMode,
			scale = defaultScale,
			isLoading = true,
		)
		scope.launch {
			reader.observeMode(manga.id, defaultMode)
				.collect { mode -> _state.update { it.copy(mode = mode) } }
		}
		loadChapter(chapter, startPage)
	}

	private fun loadChapter(chapter: AgehaChapter, startPage: Int) {
		loadJob?.cancel()
		loadJob = scope.launch {
			_state.update { it.copy(isLoading = true, failure = null) }
			when (val result = reader.pages(chapter)) {
				is CatalogResult.Success -> {
					val pages = result.value.mapIndexed { index, page -> ReaderPage(page, index) }
					val resume = if (startPage >= 0) {
						startPage
					} else {
						val manga = _state.value.manga
						val position = manga?.let { reader.positionFor(it.id) }
						// Only resume within the chapter the history actually refers to. Resuming
						// to page 40 of a different chapter because that is where the number
						// landed is worse than starting at the beginning.
						if (position?.chapterId == chapter.id) position.page else 0
					}
					_state.update {
						it.copy(
							pages = pages,
							currentPage = resume.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
							isLoading = false,
						)
					}
					resolveAround(_state.value.currentPage)
				}

				is CatalogResult.Failure -> _state.update {
					it.copy(isLoading = false, failure = result.failure, pages = emptyList())
				}
			}
		}
	}

	/** Move to an absolute page within the current chapter. */
	fun goToPage(index: Int) {
		val pages = _state.value.pages
		if (pages.isEmpty()) return
		val target = index.coerceIn(0, pages.lastIndex)
		if (target == _state.value.currentPage) return
		_state.update { it.copy(currentPage = target, seekId = it.seekId + 1) }
		resolveAround(target)
		schedulePositionSave()
	}

	/**
	 * The next page, or the next chapter if there is no next page.
	 *
	 * Rolling into the next chapter rather than stopping is what a reader expects; stopping at the
	 * last page and requiring a separate action is the behaviour that makes people close the app.
	 */
	fun nextPage() {
		val state = _state.value
		if (state.isAtLastPage) nextChapter() else goToPage(state.currentPage + 1)
	}

	fun previousPage() {
		val state = _state.value
		if (state.isAtFirstPage) previousChapter(landOnLastPage = true) else goToPage(state.currentPage - 1)
	}

	fun nextChapter() {
		val state = _state.value
		if (!state.hasNextChapter) return
		val next = chapters[state.chapterIndex + 1]
		_state.update { it.copy(chapter = next, chapterIndex = it.chapterIndex + 1, currentPage = 0) }
		loadChapter(next, startPage = 0)
		schedulePositionSave()
	}

	fun previousChapter(landOnLastPage: Boolean = false) {
		val state = _state.value
		if (!state.hasPreviousChapter) return
		val previous = chapters[state.chapterIndex - 1]
		_state.update { it.copy(chapter = previous, chapterIndex = it.chapterIndex - 1, currentPage = 0) }
		// -1 would mean "resume from history", which is not what going *back* a chapter means.
		// Landing on the last page is what makes reading backwards through a chapter boundary
		// continuous rather than a jump to the start of the chapter you just left.
		loadChapter(previous, startPage = if (landOnLastPage) Int.MAX_VALUE else 0)
		schedulePositionSave()
	}

	fun setMode(mode: ReaderMode) {
		val manga = _state.value.manga ?: return
		_state.update { it.copy(mode = mode) }
		// The whole manga, not its id: the preferences row has a foreign key onto it, and the
		// reader is reachable for manga the database has never stored. See ReaderRepository.
		scope.launch { reader.setMode(manga, mode) }
	}

	fun setScale(scale: PageScale) {
		_state.update { it.copy(scale = scale) }
	}

	fun setChromeVisible(visible: Boolean) {
		_state.update { it.copy(isChromeVisible = visible) }
	}

	fun toggleChrome() {
		_state.update { it.copy(isChromeVisible = !it.isChromeVisible) }
	}

	fun retry() {
		_state.value.chapter?.let { loadChapter(it, startPage = _state.value.currentPage) }
	}

	/** Flush the position immediately. Called when the reader closes, where a debounce would lose it. */
	fun savePositionNow() {
		saveJob?.cancel()
		// Uncancellable, because this runs while the reader is being torn down -- often because the
		// window is closing, which cancels the scope a moment later. A cancellable write would lose
		// exactly the page turn most worth remembering: the last one.
		//
		// Every part of this line is load-bearing.
		//
		// `withContext(NonCancellable)` *inside* a launch, never `launch(NonCancellable)`. They
		// read the same and are not: `NonCancellable` is a `Job`, so passing it to `launch`
		// replaces the parent job and detaches the coroutine from `scope` altogether. The write
		// then races `AgehaApplication.close`, which joins the scope, legitimately finds nothing
		// to wait for, and closes the database out from under a write still in flight.
		//
		// `UNDISPATCHED` because the opposite window is just as real: a coroutine that has been
		// launched but not yet dispatched is cancelled before its body runs at all, so on a fast
		// quit the write would simply never happen. Starting undispatched runs it on this thread
		// until it suspends into Room's dispatcher -- by which point it is inside
		// `NonCancellable` and registered as a child of the scope, so it both survives the
		// cancellation and is covered by the join.
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			withContext(NonCancellable) { writePosition() }
		}
	}

	/**
	 * Resolve the urls for the current page and the next few.
	 *
	 * Both directions, but asymmetrically: more ahead than behind, because that is the direction
	 * reading goes. Going back a page should still be instant, which is why it is not zero behind.
	 */
	private fun resolveAround(centre: Int) {
		val pages = _state.value.pages
		// Zero is the settings screen's "Whole chapter". Expressed as the page count rather than
		// as a special case in the loop, so the range stays one expression.
		val ahead = if (preloadAhead <= 0) pages.size else preloadAhead
		val range = (centre - BEHIND)..(centre + ahead)
		for (index in range) {
			val page = pages.getOrNull(index) ?: continue
			if (page.resolvedUrl != null || page.failure != null) continue
			scope.launch {
				when (val result = reader.pageUrl(page.page)) {
					is CatalogResult.Success -> updatePage(index) { it.copy(resolvedUrl = result.value) }
					// A page that will not resolve is one broken page, not a broken chapter. The
					// reader shows a placeholder for it and the rest stays readable.
					is CatalogResult.Failure -> updatePage(index) { it.copy(failure = result.failure) }
				}
			}
		}
	}

	private fun updatePage(index: Int, transform: (ReaderPage) -> ReaderPage) {
		_state.update { state ->
			val pages = state.pages.toMutableList()
			val existing = pages.getOrNull(index) ?: return@update state
			pages[index] = transform(existing)
			state.copy(pages = pages)
		}
	}

	private fun schedulePositionSave() {
		saveJob?.cancel()
		saveJob = scope.launch {
			delay(SAVE_DEBOUNCE_MS)
			writePosition()
		}
	}

	private suspend fun writePosition() {
		val state = _state.value
		val manga = state.manga ?: return
		val chapter = state.chapter ?: return
		reader.savePosition(
			manga = manga,
			chapter = chapter,
			page = state.currentPage,
			// Within-page scroll belongs to the webtoon strip and is written by the scroll
			// handler; a page turn leaves it at the top of the new page.
			scroll = 0f,
			percent = reader.progressOf(
				chapterIndex = state.chapterIndex,
				chapterCount = state.chapterCount,
				page = state.currentPage,
				pageCount = state.pageCount,
			),
			// Recorded so Continue Reading can tell "stopped on the last page" from "stopped in
			// the middle", which is what decides whether reopening resumes this chapter or starts
			// the next one. The reader is the only place that knows this number.
			pageCount = state.pageCount,
		)
	}

	/**
	 * The furthest page the viewport reaches, reported by whichever reader is drawn.
	 *
	 * Url resolution follows the *bottom* edge of the window, not the top. In webtoon mode those
	 * are different pages -- often several apart on a tall window or a zoomed-out strip -- and
	 * resolving from the top edge meant the preload depth was partly spent on pages already on
	 * screen. The image cache is warmed from the same edge; see `ReaderScreen.PreloadPages`.
	 *
	 * Safe to call on every frame of a scroll: [resolveAround] skips anything already resolved or
	 * already failed, so this is a no-op except at the moving edge.
	 */
	fun recordVisible(through: Int) {
		if (_state.value.pages.isEmpty()) return
		resolveAround(through)
	}

	/**
	 * Record a webtoon strip's exact scroll offset.
	 *
	 * The brief calls restoring this exactly non-negotiable, and it is the one number a page index
	 * cannot carry: a webtoon "page" can be twelve thousand pixels tall, so page 3 of 40 is not a
	 * position, it is a neighbourhood.
	 */
	fun recordScroll(page: Int, fraction: Float) {
		_state.update { it.copy(currentPage = page) }
		// Resolve around the new position, exactly as a page turn does.
		//
		// Without this the webtoon strip resolves the first few pages when the chapter opens and
		// then never resolves another one, because scrolling is the *only* way to move through a
		// strip and it does not go through `goToPage`. Every page past the opening window stayed a
		// spinner for as long as the chapter was open. Measured, not guessed: the 200-page profile
		// reported 5 pages resolved and 195 still pending after scrolling the whole strip.
		//
		// Safe to call on every scroll emission -- `resolveAround` skips anything already resolved
		// or already failed, so this is a no-op except at the moving edge.
		resolveAround(page)
		saveJob?.cancel()
		saveJob = scope.launch {
			delay(SAVE_DEBOUNCE_MS)
			val state = _state.value
			val manga = state.manga ?: return@launch
			val chapter = state.chapter ?: return@launch
			reader.savePosition(
				manga = manga,
				chapter = chapter,
				page = page,
				scroll = fraction,
				percent = reader.progressOf(state.chapterIndex, state.chapterCount, page, state.pageCount),
				pageCount = state.pageCount,
			)
		}
	}

	private companion object {
		/**
		 * How many pages ahead to resolve urls for.
		 *
		 * Enough to cover a fast reader turning pages, small enough that opening a chapter and
		 * closing it again costs the source three requests rather than sixty.
		 */
		const val AHEAD = 4
		const val BEHIND = 1

		/** Long enough to coalesce a burst of page turns, short enough to survive a hard close. */
		const val SAVE_DEBOUNCE_MS = 600L
	}
}
