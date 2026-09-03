package app.ageha.feature.library

import app.ageha.core.data.CatalogRepository
import app.ageha.core.data.ContinueEntry
import app.ageha.core.data.HistoryRepository
import app.ageha.core.data.ResumePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Continue Reading screen draws. */
data class ContinueUiState(
	val entries: List<ContinueEntry> = emptyList(),
	val query: String = "",
	/** Before the filter, so the screen can say "no matches" rather than "nothing read". */
	val totalCount: Int = 0,
	val isLoading: Boolean = true,
) {
	val isEmpty: Boolean get() = !isLoading && totalCount == 0
	val hasNoMatches: Boolean get() = !isLoading && totalCount > 0 && entries.isEmpty()
}

/**
 * Continue Reading.
 *
 * The list is a live subscription to the history table, so finishing a chapter in the reader
 * reorders this screen behind it with nothing telling it to.
 *
 * **The filter runs in memory and that is the feature, not an implementation detail.** This is the
 * one search in Ageha that answers instantly, because it is the one that is not a request to
 * somebody else's website. Pushing it down to SQL would make it a database round trip per
 * keystroke to filter a list already sitting in memory.
 */
class ContinueViewModel(
	private val history: HistoryRepository,
	private val catalog: CatalogRepository,
	private val scope: CoroutineScope,
) {

	private val query = MutableStateFlow("")

	val state: StateFlow<ContinueUiState> = combine(
		history.observeAll(),
		query,
	) { entries, text ->
		ContinueUiState(
			entries = history.filter(entries, text),
			query = text,
			totalCount = entries.size,
			isLoading = false,
		)
	}.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ContinueUiState())

	/**
	 * Where the last opened entry wants to go, once it has been worked out.
	 *
	 * Published rather than handed to a callback because resolving it is a suspending database
	 * read and navigation is the shell's job, not this class's. The shell collects this, acts, and
	 * calls [consumeResume].
	 */
	private val _resume = MutableStateFlow<ResumePoint?>(null)
	val resume: StateFlow<ResumePoint?> = _resume.asStateFlow()

	private val headerCache = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
	val imageHeaders: StateFlow<Map<String, Map<String, String>>> = headerCache.asStateFlow()

	fun search(text: String) {
		query.value = text
	}

	/** Work out where this entry resumes. The answer arrives on [resume]. */
	fun open(mangaId: Long) {
		scope.launch { _resume.value = history.resume(mangaId) }
	}

	fun consumeResume() {
		_resume.value = null
	}

	fun remove(mangaId: Long) {
		scope.launch { history.remove(mangaId) }
	}

	/**
	 * Per-source image headers, resolved once per source.
	 *
	 * A source missing from this parsers build has no parser to ask, so it is skipped rather than
	 * attempted -- its covers come from a cache or not at all, and asking would be one failed
	 * lookup per row for a source that by definition is not there.
	 */
	fun ensureHeaders(sourceName: String) {
		if (headerCache.value.containsKey(sourceName)) return
		scope.launch {
			val headers = catalog.imageHeaders(sourceName)
			headerCache.value = headerCache.value + (sourceName to headers)
		}
	}

	private companion object {
		/** See `LibraryViewModel`: long enough to survive a tab switch, short enough to let go. */
		const val STOP_TIMEOUT_MS = 5_000L
	}
}
