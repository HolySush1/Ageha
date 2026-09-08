package app.ageha.feature.library

import app.ageha.core.data.CatalogRepository
import app.ageha.core.data.ContinueEntry
import app.ageha.core.data.HistoryRepository
import app.ageha.core.data.LibraryCategory
import app.ageha.core.data.LibraryEntry
import app.ageha.core.data.LibraryRepository
import app.ageha.core.model.AgehaManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How the library grid is ordered. */
enum class LibrarySort(val label: String) {
	RECENTLY_ADDED("Recently added"),
	RECENTLY_READ("Recently read"),
	TITLE("Title"),
	UNREAD("Unread first"),
	PROGRESS("Progress"),
}

/** Everything the library screen draws. */
data class LibraryUiState(
	val categories: List<LibraryCategory> = emptyList(),
	val sizes: Map<Int, Int> = emptyMap(),
	val selectedCategoryId: Int = ALL_CATEGORY,
	val entries: List<LibraryEntry> = emptyList(),
	/** The Continue Reading shelf. Same rows as the Continue screen, most recent first. */
	val recent: List<ContinueEntry> = emptyList(),
	val query: String = "",
	val sort: LibrarySort = LibrarySort.RECENTLY_ADDED,
	val isLoading: Boolean = true,
) {
	val isEmpty: Boolean get() = !isLoading && entries.isEmpty() && query.isEmpty()

	companion object {
		const val ALL_CATEGORY = -1
	}
}

/**
 * The library screen's state.
 *
 * A plain class holding a [CoroutineScope] rather than an Android `ViewModel`. There is no
 * lifecycle to survive on a desktop and no configuration change to outlive; the window owns the
 * scope and cancelling it is the whole teardown story. Bringing in `androidx.lifecycle` for the
 * name would add a dependency that does nothing here.
 *
 * Filtering and sorting happen in memory, deliberately. A library is thousands of rows -- rebuilding
 * a SQL query per keystroke would mean a new Room subscription per keystroke, and the flow would
 * spend more time being torn down and rebuilt than querying. It also keeps sorting rules like
 * "unread first, then by title" in Kotlin where they can be read.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryViewModel(
	private val library: LibraryRepository,
	private val catalog: CatalogRepository,
	// The shelf comes from the history repository rather than from a second query of the same
	// table, so the shelf and the Continue screen cannot disagree about what "recent" means.
	private val history: HistoryRepository,
	private val scope: CoroutineScope,
) {

	private val selectedCategory = MutableStateFlow(LibraryUiState.ALL_CATEGORY)
	private val query = MutableStateFlow("")
	private val sort = MutableStateFlow(LibrarySort.RECENTLY_ADDED)

	private val shelf = selectedCategory.flatMapLatest { library.observeLibrary(it) }

	val state: StateFlow<LibraryUiState> = combine(
		library.observeCategories(),
		library.observeCategorySizes(),
		shelf,
		history.observeRecent(RECENT_LIMIT),
		combine(selectedCategory, query, sort) { category, text, order -> Triple(category, text, order) },
	) { categories, sizes, entries, recent, (category, text, order) ->
		LibraryUiState(
			categories = categories,
			sizes = sizes,
			selectedCategoryId = category,
			entries = entries.filtered(text).sortedBy(order),
			recent = recent,
			query = text,
			sort = order,
			isLoading = false,
		)
	}.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

	/** Per-source image headers, resolved once. Covers 403 without the right Referer. */
	private val headerCache = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
	val imageHeaders: StateFlow<Map<String, Map<String, String>>> = headerCache.asStateFlow()

	fun selectCategory(categoryId: Int) {
		selectedCategory.value = categoryId
	}

	fun search(text: String) {
		query.value = text
	}

	fun setSort(order: LibrarySort) {
		sort.value = order
	}

	fun removeFromLibrary(mangaId: Long) {
		scope.launch { library.removeFromLibrary(mangaId) }
	}

	/**
	 * Something the grid could not do, waiting to be said out loud.
	 *
	 * Published rather than thrown or swallowed. Marking a never-opened title read is a request
	 * this schema genuinely cannot honour -- there is no chapter to point a position at -- and a
	 * right-click that quietly does nothing is the worst of the three available outcomes.
	 */
	private val _notice = MutableStateFlow<String?>(null)
	val notice: StateFlow<String?> = _notice.asStateFlow()

	fun consumeNotice() {
		_notice.value = null
	}

	/** Mark a whole title read, from the grid's right-click menu. */
	fun markRead(manga: AgehaManga) {
		scope.launch {
			if (!history.markAllRead(manga.id)) {
				_notice.value = "Ageha has no chapter list for \"${manga.title}\" yet. " +
					"Open it once so the chapters are stored, then try again."
			}
		}
	}

	/** Mark a whole title unread, clearing its progress. See `HistoryRepository.markAllUnread`. */
	fun markUnread(manga: AgehaManga) {
		scope.launch { history.markAllUnread(manga.id) }
	}

	fun createCategory(title: String) {
		scope.launch { library.createCategory(title) }
	}

	fun deleteCategory(categoryId: Int) {
		scope.launch {
			library.deleteCategory(categoryId)
			if (selectedCategory.value == categoryId) selectedCategory.value = LibraryUiState.ALL_CATEGORY
		}
	}

	/**
	 * Resolve image headers for a source, once.
	 *
	 * Called as covers scroll into view. Reaching the parser is cheap but not free, and a grid
	 * would otherwise ask for the same source's headers once per visible cell.
	 */
	fun ensureHeaders(sourceName: String) {
		if (headerCache.value.containsKey(sourceName)) return
		scope.launch {
			val headers = catalog.imageHeaders(sourceName)
			headerCache.value = headerCache.value + (sourceName to headers)
		}
	}

	private fun List<LibraryEntry>.filtered(text: String): List<LibraryEntry> {
		val needle = text.trim().lowercase()
		if (needle.isEmpty()) return this
		return filter { entry ->
			entry.manga.title.lowercase().contains(needle) ||
				// Alternative titles matter more here than anywhere else: a user who saved
				// something under its Japanese title will search for it that way.
				entry.manga.altTitles.any { it.lowercase().contains(needle) } ||
				entry.manga.authors.any { it.lowercase().contains(needle) }
		}
	}

	private fun List<LibraryEntry>.sortedBy(order: LibrarySort): List<LibraryEntry> = when (order) {
		// The repository already returns shelves newest-first, so this is the identity ordering.
		LibrarySort.RECENTLY_ADDED -> this
		LibrarySort.RECENTLY_READ -> sortedByDescending { it.lastReadAt ?: 0L }
		LibrarySort.TITLE -> sortedBy { it.manga.title.lowercase() }
		LibrarySort.UNREAD -> sortedWith(
			compareByDescending<LibraryEntry> { it.newChapters }
				.thenBy { it.manga.title.lowercase() },
		)
		LibrarySort.PROGRESS -> sortedByDescending { it.progressPercent ?: -1f }
	}

	private companion object {
		const val RECENT_LIMIT = 12

		/**
		 * How long the library query stays subscribed after the last collector goes away.
		 *
		 * Long enough to survive switching tabs and coming back without re-querying, short enough
		 * that a window left on the reader for an hour is not holding a live database
		 * subscription open for nothing.
		 */
		const val STOP_TIMEOUT_MS = 5_000L
	}
}
