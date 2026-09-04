package app.ageha.feature.explore

import app.ageha.core.data.SourceListing
import app.ageha.core.data.SourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which slice of the catalogue the source picker is showing. */
enum class SourceFilter(val label: String) {
	ENABLED("Enabled"),
	ALL("All sources"),
	BROKEN("Known broken"),
}

data class ExploreUiState(
	val sources: List<SourceListing> = emptyList(),
	val query: String = "",
	val filter: SourceFilter = SourceFilter.ENABLED,
	val locale: String? = null,
	val availableLocales: List<String> = emptyList(),
	val parsersVersion: String = "",
	val totalCount: Int = 0,
	val enabledCount: Int = 0,
	/**
	 * How many sources the same search would find with the filter set to [SourceFilter.ALL].
	 *
	 * Carried so the screen can tell "nothing is called that" apart from "nothing you have turned
	 * on is called that". They look identical to a user and they need opposite advice, and on a
	 * fresh installation -- where nothing is enabled yet -- it is always the second one.
	 */
	val matchesInAllSources: Int = 0,
	val isLoading: Boolean = true,
)

/**
 * The source picker.
 *
 * The catalogue is around 1360 entries, which is the fact that shapes this screen. It is far too
 * many to scroll and far too many to enable by default, so the picker opens on *enabled* sources
 * and treats browsing the full list as a deliberate act. Filtering runs in memory over a list the
 * parsers library already holds -- there is no query to push down to.
 */
class ExploreViewModel(
	private val sources: SourceRepository,
	private val scope: CoroutineScope,
) {

	private val query = MutableStateFlow("")
	private val filter = MutableStateFlow(SourceFilter.ENABLED)
	private val locale = MutableStateFlow<String?>(null)

	val state: StateFlow<ExploreUiState> = combine(
		sources.observeAll(),
		query,
		filter,
		locale,
	) { all, text, which, lang ->
		val visible = when (which) {
			SourceFilter.ENABLED -> all.filter { it.isEnabled }
			SourceFilter.ALL -> all
			// Upstream flags sources it knows are broken. Surfacing them as their own view is
			// better than hiding them: a user whose favourite source stopped working gets an
			// answer instead of assuming Ageha broke.
			SourceFilter.BROKEN -> all.filter { it.descriptor.isBroken }
		}
		val found = sources.search(visible, text, lang).sortedWith(SOURCE_ORDER)
		ExploreUiState(
			sources = found,
			query = text,
			filter = which,
			locale = lang,
			availableLocales = sources.availableLocales(),
			parsersVersion = sources.parsersVersion,
			totalCount = all.size,
			enabledCount = all.count { it.isEnabled },
			// Only computed when this filter came back with nothing, which is the only time the
			// screen asks. Running the same search over 1360 entries on every keystroke to answer
			// a question nobody asked would be the one expensive thing on this screen.
			matchesInAllSources = if (found.isNotEmpty() || which == SourceFilter.ALL) {
				0
			} else {
				sources.search(all, text, lang).size
			},
			isLoading = false,
		)
	}.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ExploreUiState())

	fun search(text: String) {
		query.value = text
	}

	fun setFilter(which: SourceFilter) {
		filter.value = which
	}

	fun setLocale(tag: String?) {
		locale.value = tag
	}

	fun setEnabled(name: String, enabled: Boolean) {
		scope.launch { sources.setEnabled(name, enabled) }
	}

	fun markUsed(name: String) {
		scope.launch { sources.markUsed(name) }
	}

	private companion object {
		/** Pinned first, then most recently used, then alphabetical. */
		val SOURCE_ORDER = compareByDescending<SourceListing> { it.isPinned }
			.thenByDescending { it.lastUsedAt }
			.thenBy { it.title.lowercase() }

		const val STOP_TIMEOUT_MS = 5_000L
	}
}
