package app.ageha.core.source

import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaFilter
import app.ageha.core.model.AgehaFilterCapabilities
import app.ageha.core.model.AgehaFilterOptions
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaPage
import app.ageha.core.model.AgehaSortOrder
import app.ageha.core.model.SourceDescriptor

/**
 * Everything Ageha can ask of one manga source.
 *
 * Deliberately small. It covers the parser verbs that upstream is not actively migrating -- the
 * deprecated `getList(query: MangaSearchQuery)` and `searchQueryCapabilities` pair is absent on
 * purpose, since upstream marked both "Too complex" and will delete them (docs/FINDINGS.md 5).
 *
 * Every method fails as an [app.ageha.core.model.SourceFailure], never as a parser-library
 * exception and never as a bare IOException. Callers can therefore branch on *why* something
 * failed without knowing anything about the parsers library.
 */
interface MangaSourceClient {

	val descriptor: SourceDescriptor

	/** The domain currently configured for this source, e.g. after the user picks a mirror. */
	val domain: String

	/** Sort orders this source actually supports. Never empty. */
	val availableSortOrders: Set<AgehaSortOrder>

	/** Which filter controls are worth showing for this source. */
	val filterCapabilities: AgehaFilterCapabilities

	/**
	 * One page of a listing.
	 *
	 * @param offset how many items to skip. Sources paginate differently; the parser translates.
	 */
	suspend fun list(
		offset: Int,
		order: AgehaSortOrder,
		filter: AgehaFilter = AgehaFilter.EMPTY,
	): List<AgehaManga>

	/**
	 * Fill in description, large cover and, importantly, the chapter list.
	 *
	 * Returns the same manga with fields populated. Id, url and source are guaranteed unchanged.
	 */
	suspend fun details(manga: AgehaManga): AgehaManga

	suspend fun pages(chapter: AgehaChapter): List<AgehaPage>

	/** Resolve a page to a direct image url. Some sources need a request per page to do this. */
	suspend fun pageUrl(page: AgehaPage): String

	/** The tags, states and locales this source offers. Usually one network call, worth caching. */
	suspend fun filterOptions(): AgehaFilterOptions

	suspend fun relatedManga(seed: AgehaManga): List<AgehaManga>

	/**
	 * Headers to use when fetching images from this source directly, bypassing the parser.
	 *
	 * The image loader needs these: many sources serve pages only with a matching Referer.
	 */
	fun imageRequestHeaders(): Map<String, String>
}
