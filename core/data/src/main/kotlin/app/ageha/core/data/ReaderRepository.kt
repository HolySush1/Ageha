package app.ageha.core.data

import app.ageha.core.database.dao.HistoryDao
import app.ageha.core.database.dao.MangaDao
import app.ageha.core.database.dao.MangaPrefsDao
import app.ageha.core.database.entity.MangaPrefsEntity
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaPage
import app.ageha.core.model.ReaderMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Where the reader left off in one manga. */
data class ReadingPosition(
	val chapterId: Long,
	val page: Int,
	/** Fraction through the current page, for the webtoon strip. Restored exactly. */
	val scroll: Float,
	/** Fraction through the whole manga, for the library's progress bar. */
	val percent: Float,
	val chaptersAtLastRead: Int,
	/** Pages in that chapter, or 0 when unknown. See `HistoryEntity.pageCount`. */
	val pageCount: Int = 0,
)

/**
 * Reading: pages, position, and the per-manga settings the reader honours.
 *
 * Position is stored per *manga*, not per chapter -- the Android schema has one history row per
 * manga carrying the chapter id -- so "resume" means one place, which is what a reader actually
 * wants. Ageha keeps the same shape because backup import depends on it.
 */
class ReaderRepository(
	private val catalog: CatalogRepository,
	private val manga: MangaDao,
	private val history: HistoryDao,
	private val prefs: MangaPrefsDao,
) {

	/**
	 * The pages of a chapter, from a source or from a local archive.
	 *
	 * The local branch is here rather than behind a fake `MangaSourceClient` because a CBZ is not
	 * a source: it has no listing, no search, no filters and no domain, and implementing eleven
	 * methods that all throw in order to reach the one that does not would be worse than a branch
	 * that says what it is.
	 */
	suspend fun pages(chapter: AgehaChapter): CatalogResult<List<AgehaPage>> =
		if (chapter.sourceName == LocalArchive.LOCAL_SOURCE) {
			val file = java.io.File(chapter.url)
			when {
				!file.isFile -> CatalogResult.Failure(
					app.ageha.core.model.SourceFailure.NotFound(LocalArchive.LOCAL_SOURCE),
				)
				!LocalArchive.isSupported(file) -> CatalogResult.Failure(
					app.ageha.core.model.SourceFailure.ContentUnavailable(
						LocalArchive.LOCAL_SOURCE,
						LocalArchive.unsupportedReason(file),
					),
				)
				else -> CatalogResult.Success(LocalArchive.pages(file))
			}
		} else {
			catalog.pages(chapter)
		}

	/**
	 * A page's image url.
	 *
	 * Archive pages are already addressable -- `LocalArchive` gives them a `cbz://` url that
	 * `:core:image`'s fetcher understands -- so there is nothing to resolve and no request to
	 * make. Sending them through the catalog would ask a source registry about a source that
	 * does not exist.
	 */
	suspend fun pageUrl(page: AgehaPage): CatalogResult<String> =
		if (page.sourceName == LocalArchive.LOCAL_SOURCE) {
			CatalogResult.Success(page.url)
		} else {
			catalog.pageUrl(page)
		}

	fun imageHeaders(sourceName: String): Map<String, String> =
		if (sourceName == LocalArchive.LOCAL_SOURCE) emptyMap() else catalog.imageHeaders(sourceName)

	/**
	 * A manga standing for one archive file, so the reader can open it like anything else.
	 *
	 * Reading position works for these too: the id is derived from the absolute path and is
	 * stable, so closing and reopening a local file resumes where it left off.
	 */
	fun localManga(file: java.io.File): Pair<AgehaManga, AgehaChapter> {
		val chapter = LocalArchive.chapterFor(file)
		val manga = AgehaManga(
			id = chapter.id,
			title = file.nameWithoutExtension,
			altTitles = emptySet(),
			url = file.absolutePath,
			publicUrl = file.toURI().toString(),
			rating = null,
			contentRating = null,
			coverUrl = null,
			largeCoverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			description = null,
			chapters = listOf(chapter),
			sourceName = LocalArchive.LOCAL_SOURCE,
		)
		return manga to chapter
	}

	suspend fun positionFor(mangaId: Long): ReadingPosition? =
		history.find(mangaId)?.let { row ->
			ReadingPosition(
				chapterId = row.chapterId,
				page = row.page,
				scroll = row.scroll,
				percent = row.percent,
				chaptersAtLastRead = row.chaptersCount,
				pageCount = row.pageCount,
			)
		}

	/**
	 * Record where the reader is.
	 *
	 * The row itself is written by [HistoryWriter], which the chapter list's mark-read actions
	 * share. Everything unobvious about that write -- the foreign key on the manga row, the
	 * guarded chapter upsert, the preserved `created_at` -- is documented there.
	 */
	suspend fun savePosition(
		manga: AgehaManga,
		chapter: AgehaChapter,
		page: Int,
		scroll: Float,
		percent: Float,
		pageCount: Int = 0,
		now: Long = System.currentTimeMillis(),
	) {
		HistoryWriter.write(
			mangaDao = this.manga,
			historyDao = history,
			manga = manga,
			chapter = chapter,
			page = page,
			scroll = scroll,
			percent = percent,
			pageCount = pageCount,
			now = now,
		)
	}

	/**
	 * The reader mode for one manga.
	 *
	 * @param fallback what a manga with no stored mode of its own gets. Settings' "Reading mode"
	 *   row writes this, and it stays a *fallback* rather than an override: a per-manga row always
	 *   wins, because a webtoon and a scanlated tankoubon want different modes and one global
	 *   setting would make one of them wrong every time.
	 */
	fun observeMode(mangaId: Long, fallback: ReaderMode = ReaderMode.DEFAULT): Flow<ReaderMode> =
		prefs.observe(mangaId).map { row ->
			row?.mode?.let(ReaderMode::fromId) ?: fallback
		}

	/**
	 * Set the reader mode for one manga.
	 *
	 * Read-modify-write rather than a targeted `UPDATE`, because the row carries eight other
	 * columns Ageha does not use yet but the Android app does. Writing a fresh row with defaults
	 * would silently reset a user's colour-filter settings on their next backup round trip.
	 *
	 * **Takes the manga, not just its id, and writes the manga row first.** `preferences.manga_id`
	 * is an enforced foreign key, exactly like `history.manga_id`, so switching to webtoon mode on
	 * something that was never favourited threw a constraint violation instead of changing the
	 * mode -- which is every manga opened from search, from a listing, or from a local file, and
	 * the reader's mode menu is reachable from all of them. The same defect was fixed for
	 * [savePosition]; this is its twin, found by the webtoon profile, which opens a local archive
	 * and sets a mode before anything has saved a position.
	 */
	suspend fun setMode(manga: AgehaManga, mode: ReaderMode) {
		this.manga.upsertWithTags(
			MangaMapping.toEntity(manga),
			manga.tags.map { MangaMapping.toEntity(it) },
		)
		setMode(manga.id, mode)
	}

	/**
	 * Set the mode for a manga already known to be stored.
	 *
	 * Prefer the overload taking the manga. This one is correct only when the row is already
	 * there, which the caller has to know.
	 */
	suspend fun setMode(mangaId: Long, mode: ReaderMode) {
		val existing = prefs.find(mangaId)
		prefs.upsert(
			existing?.copy(mode = mode.id) ?: MangaPrefsEntity(
				mangaId = mangaId,
				mode = mode.id,
				cfBrightness = 0f,
				cfContrast = 0f,
				cfInvert = false,
				cfGrayscale = false,
				cfBookEffect = false,
				titleOverride = null,
				coverUrlOverride = null,
				contentRatingOverride = null,
			),
		)
	}

	/**
	 * How far through the manga the reader is, as a fraction.
	 *
	 * Chapter-weighted rather than page-weighted: chapters differ in length and Ageha does not
	 * know the page count of chapters it has not opened, so weighting by pages would need the
	 * whole manga fetched to answer. Position within the current chapter contributes its share of
	 * one chapter, which is accurate enough for a 3dp progress bar and costs nothing.
	 */
	fun progressOf(chapterIndex: Int, chapterCount: Int, page: Int, pageCount: Int): Float {
		if (chapterCount <= 0) return 0f
		val withinChapter = if (pageCount <= 1) 0f else page.toFloat() / (pageCount - 1)
		return ((chapterIndex + withinChapter) / chapterCount).coerceIn(0f, 1f)
	}
}
