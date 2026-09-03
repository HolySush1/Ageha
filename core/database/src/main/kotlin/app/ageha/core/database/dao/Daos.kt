package app.ageha.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
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

	@Query("SELECT COUNT(*) FROM manga")
	suspend fun count(): Int

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

	/**
	 * Recent reading, with the manga attached.
	 *
	 * A join rather than N lookups. The continue-reading row is the first thing the library
	 * screen draws, so it is on the path between launching Ageha and seeing anything at all.
	 */
	@Query(
		"""
		SELECT manga.*, history.updated_at AS h_updated_at, history.chapter_id AS h_chapter_id,
		       history.page AS h_page, history.scroll AS h_scroll, history.percent AS h_percent,
		       history.chapters AS h_chapters
		FROM history
		INNER JOIN manga ON manga.manga_id = history.manga_id
		WHERE history.deleted_at = 0
		ORDER BY history.updated_at DESC
		LIMIT :limit
		""",
	)
	fun observeRecentWithManga(limit: Int): Flow<List<MangaWithHistory>>
}

/**
 * A manga and where the reader left off in it.
 *
 * `chaptersAtLastRead` is what makes an unread count possible without a per-chapter read table:
 * the Android app records how many chapters existed when the manga was last opened, so anything
 * beyond that is new. Ageha keeps the same column for the same reason, and because backup import
 * depends on the schemas matching.
 */
/** One row of [FavouritesDao.observeCategorySizes]. */
data class CategorySize(
	val categoryId: Int,
	val size: Int,
)

data class MangaWithHistory(
	@Embedded val manga: MangaEntity,
	@ColumnInfo(name = "h_updated_at") val updatedAt: Long,
	@ColumnInfo(name = "h_chapter_id") val chapterId: Long,
	@ColumnInfo(name = "h_page") val page: Int,
	@ColumnInfo(name = "h_scroll") val scroll: Float,
	@ColumnInfo(name = "h_percent") val percent: Float,
	@ColumnInfo(name = "h_chapters") val chaptersAtLastRead: Int,
)

@Dao
interface FavouritesDao {

	@Query("SELECT * FROM favourite_categories WHERE deleted_at = 0 ORDER BY sort_key ASC")
	fun observeCategories(): Flow<List<FavouriteCategoryEntity>>

	@Query("SELECT * FROM favourite_categories WHERE deleted_at = 0 ORDER BY sort_key ASC")
	suspend fun categories(): List<FavouriteCategoryEntity>

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

	/** Every manga in one category, newest first. */
	@Query(
		"""
		SELECT manga.* FROM favourites
		INNER JOIN manga ON manga.manga_id = favourites.manga_id
		WHERE favourites.category_id = :categoryId AND favourites.deleted_at = 0
		ORDER BY favourites.pinned DESC, favourites.created_at DESC
		""",
	)
	fun observeMangaInCategory(categoryId: Int): Flow<List<MangaEntity>>

	/**
	 * Every favourited manga, deduplicated.
	 *
	 * DISTINCT is load-bearing: a manga may sit in several categories at once, and the "all"
	 * view would otherwise show it once per category.
	 */
	@Query(
		"""
		SELECT DISTINCT manga.* FROM favourites
		INNER JOIN manga ON manga.manga_id = favourites.manga_id
		WHERE favourites.deleted_at = 0
		ORDER BY favourites.created_at DESC
		""",
	)
	fun observeAllFavouriteManga(): Flow<List<MangaEntity>>

	@Query("SELECT COUNT(*) FROM favourites WHERE manga_id = :mangaId AND deleted_at = 0")
	fun observeFavouriteCount(mangaId: Long): Flow<Int>

	@Query("SELECT category_id FROM favourites WHERE manga_id = :mangaId AND deleted_at = 0")
	suspend fun categoriesOf(mangaId: Long): List<Int>

	/**
	 * Every category's size in one query.
	 *
	 * One grouped query rather than one per category. The rail redraws whenever anything in the
	 * library changes, and a query per shelf turns a nine-shelf library into nine subscriptions
	 * that all invalidate together.
	 */
	@Query(
		"""
		SELECT category_id AS categoryId, COUNT(*) AS size FROM favourites
		WHERE deleted_at = 0 GROUP BY category_id
		""",
	)
	fun observeCategorySizes(): Flow<List<CategorySize>>

	@Query("UPDATE favourite_categories SET deleted_at = :now WHERE category_id = :categoryId")
	suspend fun markCategoryDeleted(categoryId: Int, now: Long)

	@Query("SELECT COALESCE(MAX(sort_key), -1) + 1 FROM favourite_categories")
	suspend fun nextCategorySortKey(): Int
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

	@Query("SELECT * FROM sources ORDER BY sort_key ASC")
	fun observeAll(): Flow<List<MangaSourceEntity>>

	@Query("UPDATE sources SET enabled = :enabled WHERE source = :name")
	suspend fun setEnabled(name: String, enabled: Boolean)

	@Query("UPDATE sources SET used_at = :now WHERE source = :name")
	suspend fun markUsed(name: String, now: Long)

	@Query("SELECT COALESCE(MAX(sort_key), -1) + 1 FROM sources")
	suspend fun nextSortKey(): Int
}
