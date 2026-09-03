package app.ageha.feature.explore

import app.ageha.core.data.CatalogRepository
import app.ageha.core.data.CatalogResult
import app.ageha.core.data.SourceListing
import app.ageha.core.data.SourceRepository
import app.ageha.core.model.AgehaFilter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaSortOrder
import app.ageha.core.model.SourceFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One source's answer to the query. */
data class SourceResults(
	val sourceName: String,
	val sourceTitle: String,
	val manga: List<AgehaManga> = emptyList(),
	val failure: SourceFailure? = null,
	val isLoading: Boolean = true,
) {
	val isEmpty: Boolean get() = !isLoading && failure == null && manga.isEmpty()
}

data class GlobalSearchUiState(
	val query: String = "",
	/** What the search was launched *for*, when it came from a Continue Reading entry. */
	val subject: String? = null,
	val results: List<SourceResults> = emptyList(),
	val hasSearched: Boolean = false,
) {
	val isSearching: Boolean get() = results.any { it.isLoading }
	val found: Int get() = results.sumOf { it.manga.size }
	val searchedCount: Int get() = results.size
	/** Sources with something to show come first; nobody scrolls past twenty empty headings. */
	val ordered: List<SourceResults>
		get() = results.sortedWith(
			compareByDescending<SourceResults> { it.manga.isNotEmpty() }
				.thenBy { it.isLoading }
				.thenBy { it.sourceTitle.lowercase() },
		)
}

/**
 * Searching every enabled source at once, by title.
 *
 * This exists for one job: a Continue Reading entry whose source is gone from the parsers build.
 * The reading history is still the user's, and "this site is no longer supported" is only half an
 * answer -- the other half is *the same manga is on four sources you already have enabled*.
 *
 * Three things keep it from being abusive, and all three are the point:
 *
 *  - **Enabled sources only.** Fanning out over all 1360 would be a denial-of-service attack
 *    launched from the user's own IP, and they would be the one blocked for it.
 *  - **Bounded concurrency.** [PARALLELISM] requests in flight, not one per source. The rest queue.
 *  - **Sources that cannot search are skipped**, rather than asked and made to return their
 *    front page as though it were a result.
 *
 * A source that fails is reported as having failed. It is not retried and it does not stop the
 * others -- one dead site must not cost the user the nineteen live ones.
 */
class GlobalSearchViewModel(
	private val catalog: CatalogRepository,
	private val sources: SourceRepository,
	private val scope: CoroutineScope,
) {

	private val _state = MutableStateFlow(GlobalSearchUiState())
	val state: StateFlow<GlobalSearchUiState> = _state.asStateFlow()

	private var inFlight: Job? = null

	fun setQuery(text: String) {
		_state.update { it.copy(query = text) }
	}

	/**
	 * Run the search.
	 *
	 * @param subject the title this was launched on behalf of, shown so the user can see which
	 *   entry they came from after the results have replaced the list they clicked in.
	 */
	fun search(query: String, subject: String? = null) {
		val needle = query.trim()
		inFlight?.cancel()
		if (needle.isEmpty()) {
			_state.value = GlobalSearchUiState(query = query, subject = subject)
			return
		}
		inFlight = scope.launch {
			val enabled = sources.observeEnabled().first()
			// Only the ones that can actually take a query. Asking the rest returns their default
			// listing, which looks like a result and is not one.
			val searchable = enabled.filter { canSearch(it) }
			_state.value = GlobalSearchUiState(
				query = needle,
				subject = subject,
				results = searchable.map { SourceResults(it.name, it.title) },
				hasSearched = true,
			)
			val gate = Semaphore(PARALLELISM)
			coroutineScope {
				for (listing in searchable) {
					launch { gate.withPermit { searchOne(listing, needle) } }
				}
			}
		}
	}

	private suspend fun searchOne(listing: SourceListing, query: String) {
		val order = orderFor(listing.name)
		when (val result = catalog.list(listing.name, 0, order, AgehaFilter.search(query))) {
			is CatalogResult.Success -> update(listing.name) {
				it.copy(manga = result.value.manga, isLoading = false)
			}
			is CatalogResult.Failure -> update(listing.name) {
				it.copy(failure = result.failure, isLoading = false)
			}
		}
	}

	private fun canSearch(listing: SourceListing): Boolean =
		catalog.capabilities(listing.name).valueOrNull()?.filters?.isSearchSupported ?: false

	/**
	 * Relevance where the source offers it, otherwise whatever it does offer.
	 *
	 * Passing a sort order a source does not support is how a search comes back empty for a manga
	 * the site definitely has.
	 */
	private fun orderFor(sourceName: String): AgehaSortOrder {
		val supported = catalog.capabilities(sourceName).valueOrNull()?.sortOrders.orEmpty()
		return when {
			AgehaSortOrder.RELEVANCE in supported -> AgehaSortOrder.RELEVANCE
			AgehaSortOrder.POPULARITY in supported -> AgehaSortOrder.POPULARITY
			else -> supported.firstOrNull() ?: AgehaSortOrder.UPDATED
		}
	}

	private fun update(sourceName: String, transform: (SourceResults) -> SourceResults) {
		_state.update { state ->
			state.copy(
				results = state.results.map { if (it.sourceName == sourceName) transform(it) else it },
			)
		}
	}

	fun cancel() {
		inFlight?.cancel()
		_state.update { state ->
			state.copy(results = state.results.map { it.copy(isLoading = false) })
		}
	}

	private companion object {
		/**
		 * How many sources are queried at once.
		 *
		 * Low deliberately. These are requests to other people's servers made without being asked
		 * for individually, and a burst of twenty is what gets an IP rate-limited.
		 */
		const val PARALLELISM = 4
	}
}
