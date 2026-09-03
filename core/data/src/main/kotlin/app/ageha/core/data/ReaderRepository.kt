package app.ageha.core.data

import app.ageha.core.database.dao.HistoryDao
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
	private val history: HistoryDao,
	private val prefs: MangaPrefsDao,
) {

	suspend fun pages(chapter: AgehaChapter): CatalogResult<List<AgehaPage>> =
		catalog.pages(chapter)

	suspend fun pageUrl(page: AgehaPage): CatalogResult<String> = catalog.pageUrl(page)

	fun imageHeaders(sourceName: String): Map<String, String> = catalog.imageHeaders(sourceName)

	suspend fun positionFor(mangaId: Long): ReadingPosition? =
		history.find(mangaId)?.let { row ->
			ReadingPosition(
				chapterId = row.chapterId,
				page = row.page,
				scroll = row.scroll,
				percent = row.percent,
				chaptersAtLastRead = row.chaptersCount,
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
	 */
	suspend fun savePosition(
		manga: AgehaManga,
		chapter: AgehaChapter,
		page: Int,
		scroll: Float,
		percent: Float,
		now: Long = System.currentTimeMillis(),
	) {
		val existing = history.find(manga.id)
		history.upsert(
			HistoryEntity(
				mangaId = manga.id,
				createdAt = existing?.createdAt ?: now,
				updatedAt = now,
				chapterId = chapter.id,
				page = page,
				scroll = scroll,
				percent = percent,
				deletedAt = 0,
				chaptersCount = manga.chapters?.size ?: existing?.chaptersCount ?: 0,
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
