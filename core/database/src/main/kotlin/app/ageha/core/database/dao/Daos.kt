package app.ageha.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.ageha.core.database.entity.ChapterEntity
import app.ageha.core.database.entity.FavouriteCategoryEntity
import app.ageha.core.database.entity.FavouriteEntity
import app.ageha.core.database.entity.HistoryEntity
import app.ageha.core.database.entity.MangaEntity
import app.ageha.core.database.entity.MangaSourceEntity
import app.ageha.core.database.entity.MangaTagsEntity
import app.ageha.core.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaDao {

	@Query("SELECT * FROM manga WHERE manga_id = :id")
	suspend fun find(id: Long): MangaEntity?

	@Query("SELECT * FROM manga WHERE source = :source")
	suspend fun findBySource(source: String): List<MangaEntity>

	@Upsert
	suspend fun upsert(manga: MangaEntity)

	@Upsert
	suspend fun upsertTags(tags: List<TagEntity>)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun linkTags(links: List<MangaTagsEntity>)

	@Query("DELETE FROM manga WHERE manga_id = :id")
	suspend fun delete(id: Long)

	@Query("SELECT tags.* FROM tags INNER JOIN manga_tags ON tags.tag_id = manga_tags.tag_id WHERE manga_tags.manga_id = :mangaId")
	suspend fun tagsOf(mangaId: Long): List<TagEntity>

	@Upsert
	suspend fun upsertChapters(chapters: List<ChapterEntity>)

	@Query("SELECT * FROM chapters WHERE manga_id = :mangaId ORDER BY `index` ASC")
	suspend fun chaptersOf(mangaId: Long): List<ChapterEntity>

	/**
	 * Store a manga with its tags in one transaction.
	 *
	 * Not a convenience: a manga row with no tag rows, or tag links pointing at a manga that was
	 * never written, is a half-saved library entry. The foreign keys would reject the second half
	 * and leave the first.
	 */
	@Transaction
	suspend fun upsertWithTags(manga: MangaEntity, tags: List<TagEntity>) {
		upsert(manga)
		if (tags.isNotEmpty()) {
			upsertTags(tags)
			linkTags(tags.map { MangaTagsEntity(manga.id, it.id) })
		}
	}
}

@Dao
interface HistoryDao {

	/**
	 * Reading history, most recent first.
	 *
	 * Soft-deleted rows are excluded here rather than removed from the table: the sync protocol
	 * needs the tombstone to tell "deleted on another device" from "never existed here".
	 */
	@Query("SELECT * FROM history WHERE deleted_at = 0 ORDER BY updated_at DESC LIMIT :limit")
	fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

	@Query("SELECT * FROM history WHERE manga_id = :mangaId AND deleted_at = 0")
	suspend fun find(mangaId: Long): HistoryEntity?

	@Upsert
	suspend fun upsert(entry: HistoryEntity)

	/** Soft delete, so the row can still be reconciled with other devices. */
	@Query("UPDATE history SET deleted_at = :now WHERE manga_id = :mangaId")
	suspend fun markDeleted(mangaId: Long, now: Long)

	@Query("DELETE FROM history WHERE deleted_at != 0 AND deleted_at < :before")
	suspend fun purgeDeletedBefore(before: Long): Int

	/** Includes soft-deleted rows, so tests and sync can see tombstones the reads hide. */
	@Query("SELECT COUNT(*) FROM history")
	suspend fun countIncludingDeleted(): Int
}

@Dao
interface FavouritesDao {

	@Query("SELECT * FROM favourite_categories WHERE deleted_at = 0 ORDER BY sort_key ASC")
	fun observeCategories(): Flow<List<FavouriteCategoryEntity>>

	@Upsert
	suspend fun upsertCategory(category: FavouriteCategoryEntity): Long

	@Query("SELECT * FROM favourites WHERE category_id = :categoryId AND deleted_at = 0 ORDER BY sort_key ASC")
	suspend fun inCategory(categoryId: Int): List<FavouriteEntity>

	@Upsert
	suspend fun upsert(favourite: FavouriteEntity)

	@Query("UPDATE favourites SET deleted_at = :now WHERE manga_id = :mangaId AND category_id = :categoryId")
	suspend fun markDeleted(mangaId: Long, categoryId: Int, now: Long)

	/** Includes soft-deleted rows. See [HistoryDao.countIncludingDeleted]. */
	@Query("SELECT COUNT(*) FROM favourites")
	suspend fun countIncludingDeleted(): Int
}

@Dao
interface SourcesDao {

	@Query("SELECT * FROM sources ORDER BY sort_key ASC")
	suspend fun all(): List<MangaSourceEntity>

	@Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY sort_key ASC")
	fun observeEnabled(): Flow<List<MangaSourceEntity>>

	@Upsert
	suspend fun upsert(source: MangaSourceEntity)

	@Query("SELECT * FROM sources WHERE source = :name")
	suspend fun find(name: String): MangaSourceEntity?
}
