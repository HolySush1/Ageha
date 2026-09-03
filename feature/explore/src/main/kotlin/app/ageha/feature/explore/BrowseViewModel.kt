package app.ageha.feature.explore

import app.ageha.core.data.CatalogRepository
import app.ageha.core.data.CatalogResult
import app.ageha.core.data.SourceRepository
import app.ageha.core.model.AgehaFilter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaSortOrder
import app.ageha.core.model.SourceFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
	val sourceName: String = "",
	val sourceTitle: String = "",
	val manga: List<AgehaManga> = emptyList(),
	val imageHeaders: Map<String, String> = emptyMap(),
	val sortOrders: List<AgehaSortOrder> = emptyList(),
	val sort: AgehaSortOrder? = null,
	val query: String = "",
	val isSearchSupported: Boolean = true,
	val isLoadingFirstPage: Boolean = false,
	val isLoadingMore: Boolean = false,
	val hasMore: Boolean = true,
	/** A failure that stopped the *next* page. What is already loaded stays on screen. */
	val failure: SourceFailure? = null,
)

/**
 * Browsing one source.
 *
 * Paging is offset-based because that is what the parser interface offers -- there are no cursors
 * and no total counts. "Is there more" is inferred from a page coming back empty rather than from
 * a short page, since some sources return uneven pages and treating short as final would truncate
 * a catalogue silently.
 *
 * A failure never clears the results already loaded. Sources fail constantly and partially; a user
 * three screens into a listing should not lose it because page four timed out.
 */
class BrowseViewModel(
	private val catalog: CatalogRepository,
	private val sources: SourceRepository,
	private val scope: CoroutineScope,
) {

	private val _state = MutableStateFlow(BrowseUiState())
	val state: StateFlow<BrowseUiState> = _state.asStateFlow()

	private var nextOffset = 0
	private var inFlight: Job? = null

	fun open(sourceName: String) {
		if (_state.value.sourceName == sourceName) return
		inFlight?.cancel()
		val descriptor = sources.descriptor(sourceName)
		val capabilities = catalog.capabilities(sourceName).valueOrNull()
		val orders = capabilities?.sortOrders.orEmpty().toList()
		_state.value = BrowseUiState(
			sourceName = sourceName,
			sourceTitle = descriptor?.title ?: sourceName,
			sortOrders = orders,
			// `availableSortOrders` is documented never to be empty, but the first entry is only a
			// sensible default if the source listed a sensible one first. UPDATED is the usual
			// intent when browsing a manga source, so prefer it when offered.
			sort = orders.firstOrNull { it == AgehaSortOrder.UPDATED } ?: orders.firstOrNull(),
			isSearchSupported = capabilities?.filters?.isSearchSupported ?: true,
			imageHeaders = catalog.imageHeaders(sourceName),
		)
		nextOffset = 0
		scope.launch { sources.markUsed(sourceName) }
		loadMore(reset = true)
	}

	fun setSort(order: AgehaSortOrder) {
		if (_state.value.sort == order) return
		_state.update { it.copy(sort = order) }
		restart()
	}

	fun search(text: String) {
		_state.update { it.copy(query = text) }
	}

	/** Search is explicit, not per-keystroke: every run is a request to somebody else's server. */
	fun submitSearch() = restart()

	fun retry() = loadMore(reset = _state.value.manga.isEmpty())

	private fun restart() {
		inFlight?.cancel()
		nextOffset = 0
		_state.update { it.copy(manga = emptyList(), hasMore = true, failure = null) }
		loadMore(reset = true)
	}

	fun loadMore(reset: Boolean = false) {
		val current = _state.value
		if (current.sourceName.isEmpty()) return
		if (!reset && (!current.hasMore || current.isLoadingMore || current.isLoadingFirstPage)) return
		if (inFlight?.isActive == true && !reset) return

		inFlight?.cancel()
		_state.update {
			it.copy(
				isLoadingFirstPage = reset,
				isLoadingMore = !reset,
				failure = null,
			)
		}
		val order = current.sort ?: AgehaSortOrder.UPDATED
		val filter = current.query.trim().takeIf { it.isNotEmpty() }
			?.let { AgehaFilter(query = it) }
			?: AgehaFilter.EMPTY

		inFlight = scope.launch {
			when (val result = catalog.list(current.sourceName, nextOffset, order, filter)) {
				is CatalogResult.Success -> {
					nextOffset = result.value.nextOffset
					_state.update { state ->
						// Deduplicate across pages. Some sources repeat entries between offsets
						// when the underlying listing shifts mid-scroll, and a duplicate key in a
						// lazy grid is a hard crash rather than a cosmetic problem.
						val seen = state.manga.mapTo(HashSet()) { it.id }
						val added = result.value.manga.filter { seen.add(it.id) }
						state.copy(
							manga = state.manga + added,
							hasMore = result.value.hasMore,
							isLoadingFirstPage = false,
							isLoadingMore = false,
						)
					}
				}

				is CatalogResult.Failure -> _state.update {
					it.copy(
						isLoadingFirstPage = false,
						isLoadingMore = false,
						// Stop asking for more after a non-transient failure. Continuing to
						// request pages from a source that is blocking us is how an app earns a
						// permanent ban for its whole user base.
						hasMore = result.failure.isTransient,
						failure = result.failure,
					)
				}
			}
		}
	}
}
