package app.ageha.core.jvmcontext

import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaContentRating
import app.ageha.core.model.AgehaContentType
import app.ageha.core.model.AgehaFilter
import app.ageha.core.model.AgehaFilterCapabilities
import app.ageha.core.model.AgehaFilterOptions
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaMangaState
import app.ageha.core.model.AgehaPage
import app.ageha.core.model.AgehaSortOrder
import app.ageha.core.model.AgehaTag
import app.ageha.core.model.SourceDescriptor
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import java.util.Locale

/**
 * The single place where parser-library models become Ageha models, and back.
 *
 * The whole wall exists so that this file, and only this file, breaks when upstream moves. That is
 * not hypothetical: `Manga` is mid-migration right now, still carrying a deprecated constructor
 * plus deprecated `author`, `altTitle` and `isNsfw` accessors that are being replaced by `authors`,
 * `altTitles` and `contentRating` (docs/FINDINGS.md 5). When those are deleted, one file fails to
 * compile.
 *
 * Rules for anything added here:
 *
 *  - Read only the *new* accessors. Never touch a deprecated one, however convenient.
 *  - Unknown enum constants degrade to null or to a documented fallback, never to an exception.
 *    A parsers build can introduce a value we have never heard of at any time.
 *  - Sources are carried as strings. Never persist an enum or an ordinal (see [SourceDescriptor]).
 */
internal object ParserModelMapper {

	fun descriptor(source: MangaParserSource) = SourceDescriptor(
		name = source.name,
		title = source.title,
		// Upstream uses "" for multi-language sources; null models that more honestly.
		locale = source.locale.ifEmpty { null },
		contentType = AgehaContentType.of(source.contentType.name),
		isBroken = source.isBroken,
	)

	fun manga(manga: Manga) = AgehaManga(
		id = manga.id,
		title = manga.title,
		altTitles = manga.altTitles,
		url = manga.url,
		publicUrl = manga.publicUrl,
		// Upstream signals "no rating" with a sentinel; null is less error-prone downstream.
		rating = manga.rating.takeIf { manga.hasRating },
		contentRating = AgehaContentRating.ofOrNull(manga.contentRating?.name),
		coverUrl = manga.coverUrl,
		largeCoverUrl = manga.largeCoverUrl,
		tags = manga.tags.mapTo(LinkedHashSet(), ::tag),
		state = AgehaMangaState.ofOrNull(manga.state?.name),
		authors = manga.authors,
		description = manga.description,
		chapters = manga.chapters?.map(::chapter),
		sourceName = manga.source.name,
	)

	fun chapter(chapter: MangaChapter) = AgehaChapter(
		id = chapter.id,
		title = chapter.title,
		number = chapter.number.takeIf { it > 0f },
		volume = chapter.volume.takeIf { it > 0 },
		url = chapter.url,
		scanlator = chapter.scanlator,
		uploadDate = chapter.uploadDate.takeIf { it > 0L },
		branch = chapter.branch,
		sourceName = chapter.source.name,
	)

	fun page(page: MangaPage) = AgehaPage(
		id = page.id,
		url = page.url,
		preview = page.preview,
		sourceName = page.source.name,
	)

	fun tag(tag: MangaTag) = AgehaTag(
		title = tag.title,
		key = tag.key,
		sourceName = tag.source.name,
	)

	fun sortOrder(order: SortOrder): AgehaSortOrder? = AgehaSortOrder.ofOrNull(order.name)

	fun filterCapabilities(caps: MangaListFilterCapabilities) = AgehaFilterCapabilities(
		isSearchSupported = caps.isSearchSupported,
		isMultipleTagsSupported = caps.isMultipleTagsSupported,
		isTagsExclusionSupported = caps.isTagsExclusionSupported,
		isSearchWithFiltersSupported = caps.isSearchWithFiltersSupported,
		isYearSupported = caps.isYearSupported,
		isAuthorSearchSupported = caps.isSearchSupported,
	)

	fun filterOptions(options: MangaListFilterOptions) = AgehaFilterOptions(
		availableTags = options.availableTags.mapTo(LinkedHashSet(), ::tag),
		availableStates = options.availableStates.mapNotNullTo(LinkedHashSet()) {
			AgehaMangaState.ofOrNull(it.name)
		},
		availableContentRatings = options.availableContentRating.mapNotNullTo(LinkedHashSet()) {
			AgehaContentRating.ofOrNull(it.name)
		},
		availableLocales = options.availableLocales.mapTo(LinkedHashSet()) { it.toLanguageTag() },
	)
}

/**
 * The parser-ward direction: Ageha's request types become the library's.
 *
 * Separate object from [ParserModelMapper] only for readability; both are the same wall.
 */
internal object AgehaRequestMapper {

	/**
	 * Reverse-map a source's [SortOrder]. Returns null when the parsers build has no constant of
	 * that name, which happens if a user's saved preference outlives an upstream rename.
	 */
	fun sortOrder(order: AgehaSortOrder, supported: Set<SortOrder>): SortOrder? =
		supported.firstOrNull { it.name == order.name }

	/**
	 * Build the library's filter.
	 *
	 * Tags round-trip by [AgehaTag.key] against the options the source itself published, because
	 * `MangaTag` carries a `MangaSource` we deliberately do not hold on to. Passing a tag the
	 * source does not know is worse than dropping it: some parsers put unknown keys straight into
	 * a query string and get back an empty page with no error.
	 */
	fun filter(filter: AgehaFilter, knownTags: Set<MangaTag>): MangaListFilter {
		val byKey = knownTags.associateBy { it.key }
		return MangaListFilter(
			query = filter.query?.takeIf { it.isNotBlank() },
			tags = filter.includeTags.mapNotNullTo(LinkedHashSet()) { byKey[it.key] },
			tagsExclude = filter.excludeTags.mapNotNullTo(LinkedHashSet()) { byKey[it.key] },
			locale = filter.locale?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() },
			states = filter.states.mapNotNullTo(LinkedHashSet()) { state ->
				MangaState.entries.firstOrNull { it.name == state.name }
			},
			contentRating = filter.contentRatings.mapNotNullTo(LinkedHashSet()) { rating ->
				ContentRating.entries.firstOrNull { it.name == rating.name }
			},
			year = filter.year ?: YEAR_UNKNOWN,
			author = filter.author,
		)
	}
}
