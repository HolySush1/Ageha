package app.ageha.core.data

import app.ageha.core.database.dao.HistoryDao
import app.ageha.core.database.dao.MangaDao
import app.ageha.core.database.dao.MangaPrefsDao
import app.ageha.core.database.entity.HistoryEntity
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
	 * `created_at` is preserved across updates: it is when this manga was *first* opened, and
	 * overwriting it on every page turn would make "reading since" meaningless and would confuse
	 * a sync server that treats it as an identity.
	 *
	 * `deleted_at` is cleared, because reading something again un-deletes it. A user who removed
	 * a manga and then opened it from search has resumed it, and a tombstone left in place would
	 * have the next sync delete it out from under them.
	 *
	 * **The manga row is written first, and that is not optional.** `history.manga_id` is a real,
	 * enforced foreign key, and until this was added, recording a position for anything that was
	 * not already *favourited* failed with a constraint violation -- reading from search, reading
	 * from a browse listing, and opening a local file all wrote no history at all. Favouriting was
	 * the only path that happened to insert the row. Found by rendering the reader against a real
	 * CBZ, which is exactly the case with no library entry behind it.
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
		this.manga.upsertWithTags(
			MangaMapping.toEntity(manga),
			manga.tags.map { MangaMapping.toEntity(it) },
		)
		val existing = history.find(manga.id)
		val chapters = manga.chapters
		// Store the chapter list too, but not on every page turn.
		//
		// This is what makes Continue Reading answerable offline: the last chapter's number and
		// name, and the identity of the chapter after it, both come from these rows rather than
		// from the source. Until now nothing wrote to the `chapters` table at all.
		//
		// Guarded because `savePosition` runs on a debounce behind every page turn, and rewriting
		// four hundred chapter rows each time would turn a page turn into a bulk upsert. The three
		// conditions are the only ones that can change what is stored: nothing stored yet, a move
		// to a different chapter, or a chapter list that has grown since.
		if (!chapters.isNullOrEmpty() &&
			(existing == null ||
				existing.chapterId != chapter.id ||
				existing.chaptersCount != chapters.size)
		) {
			this.manga.upsertChapters(
				chapters.mapIndexed { index, item -> MangaMapping.toEntity(item, manga.id, index) },
			)
		}
		history.upsert(
			HistoryEntity(
				mangaId = manga.id,
				createdAt = existing?.createdAt ?: now,
				updatedAt = now,
				chapterId = chapter.id,
				page = page,
				// Zero means "not known", so a caller that cannot say keeps whatever was known
				// before rather than overwriting a real count with a claim of nothing.
				pageCount = pageCount.takeIf { it > 0 }
					?: existing?.takeIf { it.chapterId == chapter.id }?.pageCount
					?: 0,
				scroll = scroll,
				percent = percent,
				deletedAt = 0,
				chaptersCount = chapters?.size ?: existing?.chaptersCount ?: 0,
			),
		)
	}

	fun observeMode(mangaId: Long): Flow<ReaderMode> =
		prefs.observe(mangaId).map { row ->
			row?.mode?.let(ReaderMode::fromId) ?: ReaderMode.DEFAULT
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
