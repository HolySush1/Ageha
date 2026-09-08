package app.ageha.feature.explore

import app.ageha.core.data.CatalogRepository
import app.ageha.core.data.CatalogResult
import app.ageha.core.data.ChapterReadState
import app.ageha.core.data.HistoryRepository
import app.ageha.core.data.LibraryCategory
import app.ageha.core.data.LibraryRepository
import app.ageha.core.data.ReadingMarker
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.SourceFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class DetailsUiState(
	val manga: AgehaManga? = null,
	val imageHeaders: Map<String, String> = emptyMap(),
	/** Chapters of the branch currently selected. */
	val chapters: List<AgehaChapter> = emptyList(),
	val branches: List<String?> = emptyList(),
	val selectedBranch: String? = null,
	val categories: List<LibraryCategory> = emptyList(),
	val inCategories: Set<Int> = emptySet(),
	val isInLibrary: Boolean = false,
	val isLoading: Boolean = false,
	val failure: SourceFailure? = null,
	/**
	 * Where the reader left off, resolved against [chapters].
	 *
	 * Null means this manga has never been opened, which is what hides the Continue reading button
	 * and leaves every chapter row unmarked. Resolved against the *selected branch*, so switching
	 * branch moves the markers rather than leaving them pointing at a row that is no longer there.
	 */
	val marker: ReadingMarker? = null,
) {

	/**
	 * The position as one line: the chapter, then how far through the whole thing.
	 *
	 * Both, because neither answers the question alone. A percentage says how much is left and
	 * nothing about where you are; a chapter number says where you are and nothing about how much
	 * that is. Sources that publish no chapter number get the percentage on its own rather than
	 * "Chapter null".
	 */
	val positionLabel: String?
		get() {
			val marker = marker ?: return null
			val chapter = chapters.getOrNull(marker.index)
			val number = chapter?.number?.let { value ->
				val whole = value.toInt()
				// 12.5 is a real chapter number and has to survive; 12.0 must not print "12.0".
				if (value == whole.toFloat()) "Chapter $whole" else "Chapter $value"
			}
			val percent = marker.percent?.let { "${(it * 100).roundToInt()}%" }
			return listOfNotNull(number, percent).takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
		}
}

/**
 * One manga: what the source says about it, and what the library says about it.
 *
 * The screen opens with whatever the listing already knew -- title, cover, tags -- and fills in
 * description and chapters when they arrive. Showing a spinner over information already in hand
 * would be slower to *read* even though it is identical to fetch.
 */
class DetailsViewModel(
	private val catalog: CatalogRepository,
	private val library: LibraryRepository,
	/**
	 * Reading history, for the read markers and the Continue reading button.
	 *
	 * The chapter list is the one screen where "which of these have I read" is the question being
	 * asked, and the answer is a comparison against the saved position -- see [ChapterReadState].
	 */
	private val history: HistoryRepository,
	private val scope: CoroutineScope,
) {

	private val _state = MutableStateFlow(DetailsUiState())
	val state: StateFlow<DetailsUiState> = _state.asStateFlow()

	private var inFlight: Job? = null
	private var favouriteWatch: Job? = null
	private var markerWatch: Job? = null

	fun open(manga: AgehaManga) {
		inFlight?.cancel()
		favouriteWatch?.cancel()
		markerWatch?.cancel()
		_state.value = DetailsUiState(
			manga = manga,
			imageHeaders = catalog.imageHeaders(manga.sourceName),
			isLoading = true,
		)
		favouriteWatch = scope.launch {
			library.observeIsFavourite(manga.id).collect { isFavourite ->
				_state.update { it.copy(isInLibrary = isFavourite) }
			}
		}
		// Started with the chapters already in hand -- usually none -- and restarted below once a
		// list arrives. Starting it now rather than waiting means a manga whose details fail to
		// load still shows the position it was left at, which is precisely when someone most wants
		// to know it.
		watchMarker(manga.id, _state.value.chapters)
		scope.launch {
			library.observeCategories().collect { categories ->
				_state.update { it.copy(categories = categories) }
			}
		}
		scope.launch {
			_state.update { it.copy(inCategories = library.categoriesOf(manga.id)) }
		}
		inFlight = scope.launch {
			when (val result = catalog.details(manga)) {
				is CatalogResult.Success -> {
					val full = result.value
					val byBranch = full.chaptersByBranch()
					// Branch is the source's word for "scanlation group or language". A manga can
					// carry several, with different chapter counts, and showing them merged makes
					// the numbering look broken. Default to the largest -- usually the most
					// complete translation.
					val branch = byBranch.entries.maxByOrNull { it.value.size }?.key
					val chapters = byBranch[branch].orEmpty()
					_state.update {
						it.copy(
							manga = full,
							branches = byBranch.keys.toList(),
							selectedBranch = branch,
							chapters = chapters,
							isLoading = false,
						)
					}
					watchMarker(full.id, chapters)
				}

				is CatalogResult.Failure -> _state.update {
					// The manga stays: whatever the listing knew is still true and still useful.
					it.copy(isLoading = false, failure = result.failure)
				}
			}
		}
	}

	fun selectBranch(branch: String?) {
		val manga = _state.value.manga ?: return
		val chapters = manga.chaptersByBranch()[branch].orEmpty()
		_state.update { it.copy(selectedBranch = branch, chapters = chapters) }
		// Re-resolved rather than left alone: the position has not moved, but the list it is an
		// index into has, and a stale index would mark the wrong row.
		watchMarker(manga.id, chapters)
	}

	/**
	 * Mark this chapter and everything before it read.
	 *
	 * No local state update follows it. The write lands in the history table, which
	 * [watchMarker] is subscribed to, so the markers redraw from the row that was actually
	 * written rather than from an optimistic guess that could disagree with it.
	 */
	fun markReadThrough(chapter: AgehaChapter) {
		val manga = _state.value.manga ?: return
		scope.launch { history.markReadThrough(manga, chapter) }
	}

	/** Mark this chapter and everything after it unread. See [markReadThrough]. */
	fun markUnreadFrom(chapter: AgehaChapter) {
		val manga = _state.value.manga ?: return
		scope.launch { history.markUnreadFrom(manga, chapter) }
	}

	private fun watchMarker(mangaId: Long, chapters: List<AgehaChapter>) {
		markerWatch?.cancel()
		markerWatch = scope.launch {
			history.observeMarker(mangaId, chapters).collect { marker ->
				_state.update { it.copy(marker = marker) }
			}
		}
	}

	fun retry() {
		_state.value.manga?.let(::open)
	}

	/**
	 * Add to a shelf, or remove from it.
	 *
	 * Toggling rather than two calls, because the button is a toggle. The manga row is written by
	 * the repository as part of adding -- see the foreign key note there.
	 */
	fun toggleCategory(categoryId: Int) {
		val manga = _state.value.manga ?: return
		scope.launch {
			if (categoryId in _state.value.inCategories) {
				library.removeFromCategory(manga.id, categoryId)
			} else {
				library.addToCategory(manga, categoryId)
			}
			_state.update { it.copy(inCategories = library.categoriesOf(manga.id)) }
		}
	}

	fun removeFromLibrary() {
		val manga = _state.value.manga ?: return
		scope.launch {
			library.removeFromLibrary(manga.id)
			_state.update { it.copy(inCategories = emptySet()) }
		}
	}

	/**
	 * Save with no category chosen.
	 *
	 * A first-time user has no categories, and demanding they make one before they can save
	 * anything is a wall in front of the app's core action. One is created on their behalf, named
	 * the same as the Android app's default so an import lands in it rather than beside it.
	 */
	fun addToDefaultCategory() {
		val manga = _state.value.manga ?: return
		scope.launch {
			val categories = _state.value.categories
			val categoryId = categories.firstOrNull()?.id ?: library.createCategory(DEFAULT_CATEGORY)
			library.addToCategory(manga, categoryId)
			_state.update { it.copy(inCategories = library.categoriesOf(manga.id)) }
		}
	}

	private companion object {
		const val DEFAULT_CATEGORY = "Reading"
	}
}
