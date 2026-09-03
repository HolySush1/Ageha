package app.ageha.core.model

/**
 * A source listing request.
 *
 * Maps onto the parsers library's *current* filter API (`getList(offset, order, filter)`). We
 * never touch the deprecated `getList(query: MangaSearchQuery)` / `searchQueryCapabilities` pair:
 * upstream marked them `@Deprecated("Too complex")` and they will be removed
 * (docs/FINDINGS.md 5).
 */
data class AgehaFilter(
	val query: String? = null,
	val includeTags: Set<AgehaTag> = emptySet(),
	val excludeTags: Set<AgehaTag> = emptySet(),
	val states: Set<AgehaMangaState> = emptySet(),
	val contentRatings: Set<AgehaContentRating> = emptySet(),
	val locale: String? = null,
	val author: String? = null,
	val year: Int? = null,
) {
	val isEmpty: Boolean
		get() = query.isNullOrEmpty() &&
			includeTags.isEmpty() &&
			excludeTags.isEmpty() &&
			states.isEmpty() &&
			contentRatings.isEmpty() &&
			locale == null &&
			author == null &&
			year == null

	companion object {
		val EMPTY = AgehaFilter()

		fun search(query: String) = AgehaFilter(query = query)
	}
}

/** What a given source actually supports, so the UI can hide controls that would do nothing. */
data class AgehaFilterCapabilities(
	val isSearchSupported: Boolean,
	val isMultipleTagsSupported: Boolean,
	val isTagsExclusionSupported: Boolean,
	val isSearchWithFiltersSupported: Boolean,
	val isYearSupported: Boolean,
	val isAuthorSearchSupported: Boolean,
)

/** The concrete values a source offers for each filter dimension. */
data class AgehaFilterOptions(
	val availableTags: Set<AgehaTag>,
	val availableStates: Set<AgehaMangaState>,
	val availableContentRatings: Set<AgehaContentRating>,
	val availableLocales: Set<String>,
)
