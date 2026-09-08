package app.ageha.core.data

import app.ageha.core.database.dao.HistoryDao
import app.ageha.core.database.dao.MangaDao
import app.ageha.core.database.entity.HistoryEntity
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga

/**
 * The one place a history row is written on a live path.
 *
 * Two callers reach it and they sit in different repositories: the reader, saving where somebody
 * has actually got to, and the chapter list, marking chapters read or unread. Both have to do the
 * same three unobvious things -- write the manga row first, write the chapter rows sometimes, and
 * preserve `created_at` -- and two of those have already been a bug once. A second copy of them
 * would be a second copy to get wrong.
 *
 * Backup import and sync restore write this table too and deliberately do not come through here:
 * they are replaying rows that already carry their own timestamps rather than recording something
 * that just happened.
 */
internal object HistoryWriter {

	/**
	 * Record a position.
	 *
	 * **The manga row is written first, and that is not optional.** `history.manga_id` is a real,
	 * enforced foreign key, and until this was added, recording a position for anything that was
	 * not already *favourited* failed with a constraint violation -- reading from search, reading
	 * from a browse listing, and opening a local file all wrote no history at all. Favouriting was
	 * the only path that happened to insert the row.
	 *
	 * `created_at` is preserved across updates: it is when this manga was *first* opened, and
	 * overwriting it on every page turn would make "reading since" meaningless and would confuse
	 * a sync server that treats it as an identity.
	 *
	 * `deleted_at` is cleared, because reading something again un-deletes it. A user who removed
	 * a manga and then opened it from search has resumed it, and a tombstone left in place would
	 * have the next sync delete it out from under them.
	 */
	suspend fun write(
		mangaDao: MangaDao,
		historyDao: HistoryDao,
		manga: AgehaManga,
		chapter: AgehaChapter,
		page: Int,
		scroll: Float,
		percent: Float,
		pageCount: Int,
		now: Long,
	) {
		mangaDao.upsertWithTags(
			MangaMapping.toEntity(manga),
			manga.tags.map { MangaMapping.toEntity(it) },
		)
		val existing = historyDao.find(manga.id)
		val chapters = manga.chapters
		// Store the chapter list too, but not on every page turn.
		//
		// This is what makes Continue Reading answerable offline: the last chapter's number and
		// name, and the identity of the chapter after it, both come from these rows rather than
		// from the source.
		//
		// Guarded because this runs on a debounce behind every page turn, and rewriting four
		// hundred chapter rows each time would turn a page turn into a bulk upsert. The three
		// conditions are the only ones that can change what is stored: nothing stored yet, a move
		// to a different chapter, or a chapter list that has grown since.
		if (!chapters.isNullOrEmpty() &&
			(existing == null ||
				existing.chapterId != chapter.id ||
				existing.chaptersCount != chapters.size)
		) {
			mangaDao.upsertChapters(
				chapters.mapIndexed { index, item -> MangaMapping.toEntity(item, manga.id, index) },
			)
		}
		historyDao.upsert(
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
}
