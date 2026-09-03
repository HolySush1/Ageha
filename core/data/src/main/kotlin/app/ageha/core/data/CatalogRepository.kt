package app.ageha.core.data

import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaFilter
import app.ageha.core.model.AgehaFilterCapabilities
import app.ageha.core.model.AgehaFilterOptions
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaSortOrder
import app.ageha.core.model.SourceFailure
import app.ageha.core.source.MangaSourceRegistry
import kotlinx.coroutines.CancellationException

/**
 * The outcome of asking a source for something.
 *
 * A sealed result rather than exceptions crossing into the UI. A source failing is *ordinary* --
 * sites go down, block datacentre IPs, rate-limit, and break their own markup weekly -- so it is
 * modelled as a value the screen renders, not as an error that unwinds it. The screen keeps the
 * results it already has and shows why the next page did not arrive.
 */
sealed interface CatalogResult<out T> {

	data class Success<T>(val value: T) : CatalogResult<T>

	data class Failure(val failure: SourceFailure) : CatalogResult<Nothing>

	fun valueOrNull(): T? = (this as? Success)?.value
}

/** One page of a source listing, and whether asking for another is worth it. */
data class CatalogPage(
	val manga: List<AgehaManga>,
	val nextOffset: Int,
	val hasMore: Boolean,
)

/** What a source will let the user filter and sort by. */
data class CatalogCapabilities(
	val sortOrders: Set<AgehaSortOrder>,
    val filters: AgehaFilterCapabilities,
	val domain: String,
)

/**
 * Reading *from* sources, as opposed to from the library.
 *
 * Every method returns [CatalogResult] and none of them throw for a source-side problem. The one
 * exception deliberately allowed through is [CancellationException]: swallowing that would break
 * structured concurrency, so a scrolled-past request that is cancelled would keep running and keep
 * hitting the site.
 */
class CatalogRepository(private val registry: MangaSourceRegistry) {

	/**
	 * How a page size is guessed.
	 *
	 * Sources paginate differently and the interface does not report a page size, so "did we reach
	 * the end" has to be inferred: a page that comes back empty is the end. A page that comes back
	 * *short* is not necessarily -- some sources return uneven pages -- so Ageha only stops on
	 * empty, and accepts one wasted request at the end of a listing over truncating a catalogue.
	 */
	suspend fun list(
		sourceName: String,
		offset: Int,
		order: AgehaSortOrder,
		filter: AgehaFilter = AgehaFilter.EMPTY,
	): CatalogResult<CatalogPage> = attempt(sourceName) {
		val client = registry.clientFor(sourceName)
		val page = client.list(offset = offset, order = order, filter = filter)
		CatalogPage(
			manga = page,
			nextOffset = offset + page.size,
			hasMore = page.isNotEmpty(),
		)
	}

	suspend fun details(manga: AgehaManga): CatalogResult<AgehaManga> = attempt(manga.sourceName) {
		registry.clientFor(manga.sourceName).details(manga)
	}

	suspend fun chapters(manga: AgehaManga): CatalogResult<List<AgehaChapter>> =
		when (val result = details(manga)) {
			is CatalogResult.Failure -> result
			is CatalogResult.Success -> CatalogResult.Success(result.value.chapters.orEmpty())
		}

	suspend fun filterOptions(sourceName: String): CatalogResult<AgehaFilterOptions> =
		attempt(sourceName) { registry.clientFor(sourceName).filterOptions() }

	suspend fun related(manga: AgehaManga): CatalogResult<List<AgehaManga>> =
		attempt(manga.sourceName) { registry.clientFor(manga.sourceName).relatedManga(manga) }

	fun capabilities(sourceName: String): CatalogResult<CatalogCapabilities> = try {
		val client = registry.clientFor(sourceName)
		CatalogResult.Success(
			CatalogCapabilities(
				sortOrders = client.availableSortOrders,
				filters = client.filterCapabilities,
				domain = client.domain,
			),
		)
	} catch (failure: SourceFailure) {
		CatalogResult.Failure(failure)
	}

	/** Headers the image loader must send for this source. Many gate images behind a Referer. */
	fun imageHeaders(sourceName: String): Map<String, String> = try {
		registry.clientFor(sourceName).imageRequestHeaders()
	} catch (_: SourceFailure) {
		emptyMap()
	}

	private inline fun <T> attempt(sourceName: String, block: () -> T): CatalogResult<T> = try {
		CatalogResult.Success(block())
	} catch (cancellation: CancellationException) {
		// Never swallowed. A cancelled scroll must actually stop hitting the site.
		throw cancellation
	} catch (failure: SourceFailure) {
		CatalogResult.Failure(failure)
	} catch (unexpected: Throwable) {
		// The facade promises every failure arrives as a SourceFailure, and it is tested. This
		// catch is the belt to that braces: one parser throwing something unforeseen must degrade
		// to "this source failed", never take the window down. CLAUDE.md, docs/ARCHITECTURE.md 8.
		CatalogResult.Failure(SourceFailure.Unknown(sourceName, unexpected))
	}
}
