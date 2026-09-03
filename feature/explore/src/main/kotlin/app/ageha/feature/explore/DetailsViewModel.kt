package app.ageha.feature.explore

import app.ageha.core.data.CatalogRepository
import app.ageha.core.data.CatalogResult
import app.ageha.core.data.LibraryCategory
import app.ageha.core.data.LibraryRepository
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
)

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
	private val scope: CoroutineScope,
) {

	private val _state = MutableStateFlow(DetailsUiState())
	val state: StateFlow<DetailsUiState> = _state.asStateFlow()

	private var inFlight: Job? = null
	private var favouriteWatch: Job? = null

	fun open(manga: AgehaManga) {
		inFlight?.cancel()
		favouriteWatch?.cancel()
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
					_state.update {
						it.copy(
							manga = full,
							branches = byBranch.keys.toList(),
							selectedBranch = branch,
							chapters = byBranch[branch].orEmpty(),
							isLoading = false,
						)
					}
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
		_state.update {
			it.copy(selectedBranch = branch, chapters = manga.chaptersByBranch()[branch].orEmpty())
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
