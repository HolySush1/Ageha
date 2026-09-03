package app.ageha.core.jvmcontext

import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaFilter
import app.ageha.core.model.AgehaFilterCapabilities
import app.ageha.core.model.AgehaFilterOptions
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaPage
import app.ageha.core.model.AgehaSortOrder
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.model.SourceFailure
import app.ageha.core.source.MangaSourceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN

/**
 * One source, seen through Ageha's types.
 *
 * Every method is wrapped in [runSourceCall], which is what turns parser exceptions into
 * [SourceFailure] and, crucially, what notices a swallowed JavaScript refusal.
 *
 * ## The re-hydration problem
 *
 * Parser calls such as `getDetails` and `getPages` take library models, not ids -- `getDetails`
 * is documented as "must return the same manga". But Ageha hands out [AgehaManga], and converting
 * back is lossy: `MangaTag` and `MangaChapter` both carry a `MangaSource` we deliberately do not
 * keep, and a `Manga` rebuilt from our fields is not the object the parser produced.
 *
 * So the client keeps a small identity cache of the library objects it has handed out, keyed by
 * the id the parser assigned. A cache miss reconstructs a minimal object, which is enough for
 * every parser that only reads `url` and `id` -- most of them -- and is why the miss path is a
 * degradation rather than a failure.
 */
internal class ParserMangaSourceClient(
	override val descriptor: SourceDescriptor,
	private val parserProvider: () -> MangaParser,
) : MangaSourceClient {

	private val parser: MangaParser get() = parserProvider()

	/**
	 * Bounded so a long browsing session cannot grow it without limit. Eviction only costs a
	 * reconstructed stub on the next call, never correctness.
	 */
	private val mangaCache = LruCache<Long, Manga>(MANGA_CACHE_SIZE)
	private val chapterCache = LruCache<Long, MangaChapter>(CHAPTER_CACHE_SIZE)
	private val pageCache = LruCache<Long, MangaPage>(PAGE_CACHE_SIZE)

	@Volatile
	private var knownTags: Set<MangaTag> = emptySet()

	override val domain: String get() = parser.domain

	override val availableSortOrders: Set<AgehaSortOrder>
		get() = parser.availableSortOrders.mapNotNullTo(LinkedHashSet(), ParserModelMapper::sortOrder)

	override val filterCapabilities: AgehaFilterCapabilities
		get() = ParserModelMapper.filterCapabilities(parser.filterCapabilities)

	override suspend fun list(
		offset: Int,
		order: AgehaSortOrder,
		filter: AgehaFilter,
	): List<AgehaManga> = runSourceCall(descriptor.name) {
		withContext(Dispatchers.IO) {
			val p = parser
			// A saved sort preference can outlive an upstream rename, so fall back to the source's
			// own first choice rather than failing the whole listing over a stale setting.
			val sortOrder = AgehaRequestMapper.sortOrder(order, p.availableSortOrders)
				?: p.availableSortOrders.first()
			if (filter.includeTags.isNotEmpty() || filter.excludeTags.isNotEmpty()) {
				ensureTagsLoaded(p)
			}
			p.getList(offset, sortOrder, AgehaRequestMapper.filter(filter, knownTags))
				.onEach { mangaCache.put(it.id, it) }
				.map(ParserModelMapper::manga)
		}
	}

	override suspend fun details(manga: AgehaManga): AgehaManga = runSourceCall(descriptor.name) {
		withContext(Dispatchers.IO) {
			val detailed = parser.getDetails(rehydrate(manga))
			mangaCache.put(detailed.id, detailed)
			detailed.chapters?.forEach { chapterCache.put(it.id, it) }
			ParserModelMapper.manga(detailed)
		}
	}

	override suspend fun pages(chapter: AgehaChapter): List<AgehaPage> = runSourceCall(descriptor.name) {
		withContext(Dispatchers.IO) {
			parser.getPages(rehydrate(chapter))
				.onEach { pageCache.put(it.id, it) }
				.map(ParserModelMapper::page)
		}
	}

	override suspend fun pageUrl(page: AgehaPage): String = runSourceCall(descriptor.name) {
		withContext(Dispatchers.IO) {
			parser.getPageUrl(rehydrate(page))
		}
	}

	override suspend fun filterOptions(): AgehaFilterOptions = runSourceCall(descriptor.name) {
		withContext(Dispatchers.IO) {
			val options = parser.getFilterOptions()
			knownTags = options.availableTags
			ParserModelMapper.filterOptions(options)
		}
	}

	override suspend fun relatedManga(seed: AgehaManga): List<AgehaManga> = runSourceCall(descriptor.name) {
		withContext(Dispatchers.IO) {
			parser.getRelatedManga(rehydrate(seed))
				.onEach { mangaCache.put(it.id, it) }
				.map(ParserModelMapper::manga)
		}
	}

	override fun imageRequestHeaders(): Map<String, String> =
		parser.getRequestHeaders().toMultimap().mapValues { (_, values) -> values.first() }

	private suspend fun ensureTagsLoaded(parser: MangaParser) {
		if (knownTags.isEmpty()) {
			knownTags = parser.getFilterOptions().availableTags
		}
	}

	// ---- re-hydration --------------------------------------------------------------------

	/**
	 * The cached object is the correct answer where we have it: `getDetails` is specified as
	 * "must return the same manga", and some parsers keep state on the instance they handed out.
	 * The reconstruction is the cache-miss fallback, faithful in the fields parsers are documented
	 * to rely on -- id, url and source.
	 */
	private fun rehydrate(manga: AgehaManga): Manga = mangaCache.get(manga.id) ?: Manga(
		id = manga.id,
		title = manga.title,
		altTitles = manga.altTitles,
		url = manga.url,
		publicUrl = manga.publicUrl,
		rating = manga.rating ?: RATING_UNKNOWN,
		contentRating = manga.contentRating?.let { r ->
			ContentRating.entries.firstOrNull { it.name == r.name }
		},
		coverUrl = manga.coverUrl,
		tags = manga.tags.mapTo(LinkedHashSet()) { MangaTag(it.title, it.key, parser.source) },
		state = manga.state?.let { st -> MangaState.entries.firstOrNull { it.name == st.name } },
		authors = manga.authors,
		largeCoverUrl = manga.largeCoverUrl,
		description = manga.description,
		chapters = manga.chapters?.map(::rehydrate),
		source = parser.source,
	)

	private fun rehydrate(chapter: AgehaChapter): MangaChapter = chapterCache.get(chapter.id)
		?: MangaChapter(
			id = chapter.id,
			title = chapter.title,
			number = chapter.number ?: 0f,
			volume = chapter.volume ?: 0,
			url = chapter.url,
			scanlator = chapter.scanlator,
			uploadDate = chapter.uploadDate ?: 0L,
			branch = chapter.branch,
			source = parser.source,
		)

	private fun rehydrate(page: AgehaPage): MangaPage = pageCache.get(page.id)
		?: MangaPage(
			id = page.id,
			url = page.url,
			preview = page.preview,
			source = parser.source,
		)
}

/**
 * A small synchronised LRU. Not worth a dependency, and the access pattern here is trivial:
 * put on the way out, get on the way back in.
 */
internal class LruCache<K : Any, V : Any>(private val maxSize: Int) {

	private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
	}

	@Synchronized
	fun get(key: K): V? = map[key]

	@Synchronized
	fun put(key: K, value: V) {
		map[key] = value
	}
}

private const val MANGA_CACHE_SIZE = 512
private const val CHAPTER_CACHE_SIZE = 2048
private const val PAGE_CACHE_SIZE = 1024
