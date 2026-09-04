package app.ageha.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import app.ageha.core.database.entity.FavouriteCategoryEntity
import app.ageha.core.database.entity.FavouriteEntity
import app.ageha.core.database.entity.HistoryEntity
import app.ageha.core.database.entity.MangaEntity
import app.ageha.core.database.entity.MangaSourceEntity
import app.ageha.core.database.entity.TagEntity

/**
 * Bulk read, for writing a backup. The mirror of [RestoreDao].
 *
 * Separate from the feature DAOs for the same reason [RestoreDao] is: those are shaped for reading
 * one thing at a time, and this exists to walk the whole library once. Three properties matter
 * here that nowhere else needs.
 *
 *  - **Paged, not whole-table.** A real library is thousands of manga, each carrying its tags and
 *    embedded once per history row and again per favourite row. Loading that into a list before
 *    writing anything would put the entire backup in the heap at its most expanded. Every dump
 *    here takes an offset and a window so the exporter can stream.
 *  - **Tombstones stay behind.** Soft-deleted history and favourites are excluded, exactly as the
 *    Android app's own `dump()` queries exclude them. A tombstone is a local record that something
 *    *was* deleted; exporting it would restore a deletion onto another device as though it were
 *    data.
 *  - **Only enabled sources.** Upstream's `dumpEnabled()` does the same. Ageha ships 1360 sources
 *    disabled, and a backup that carried all of them would be mostly a copy of the source list.
 *
 * Ordering matches the Android app's dumps -- history by `updated_at` descending, favourites by
 * `created_at` descending -- so an archive written here holds its rows in the same order as one
 * written there.
 *
 * **Every paged query then adds a primary-key tiebreaker, which upstream's do not.** `updated_at`,
 * `created_at` and `sort_key` are all non-unique: two chapters read in the same millisecond, or
 * two sources never sorted apart, leave `LIMIT`/`OFFSET` free to return the tied rows in a
 * different order for each window. A row can then appear in two windows and another in none, so
 * the export silently duplicates one entry and loses another. It is invisible in testing --
 * SQLite is consistent enough in practice that it takes a changed query plan to show it -- and it
 * corrupts exactly the backup nobody checks until they need it.
 */
@Dao
interface ExportDao {

	@Query("SELECT * FROM history WHERE deleted_at = 0 ORDER BY updated_at DESC, manga_id ASC LIMIT :limit OFFSET :offset")
	suspend fun dumpHistory(offset: Int, limit: Int): List<HistoryEntity>

	@Query("SELECT * FROM favourites WHERE deleted_at = 0 ORDER BY created_at DESC, manga_id ASC, category_id ASC LIMIT :limit OFFSET :offset")
	suspend fun dumpFavourites(offset: Int, limit: Int): List<FavouriteEntity>

	@Query("SELECT * FROM favourite_categories WHERE deleted_at = 0 ORDER BY sort_key ASC")
	suspend fun dumpCategories(): List<FavouriteCategoryEntity>

	@Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY sort_key ASC, source ASC LIMIT :limit OFFSET :offset")
	suspend fun dumpEnabledSources(offset: Int, limit: Int): List<MangaSourceEntity>

	@Query("SELECT * FROM manga WHERE manga_id IN (:mangaIds)")
	suspend fun mangaByIds(mangaIds: Collection<Long>): List<MangaEntity>

	/**
	 * Every tag of every manga in [mangaIds], in one query rather than one per manga.
	 *
	 * The owning id is projected alongside the tag because the join loses it otherwise, and the
	 * caller needs to know which manga each tag belongs to. `owner_manga_id` is aliased rather
	 * than selected bare: `tags.*` and `manga_tags.manga_id` would both be unprefixed column
	 * names in the result, and Room binds `@Embedded` fields by name.
	 */
	@Query(
		"""
		SELECT manga_tags.manga_id AS owner_manga_id, tags.* FROM tags
		INNER JOIN manga_tags ON tags.tag_id = manga_tags.tag_id
		WHERE manga_tags.manga_id IN (:mangaIds)
		""",
	)
	suspend fun tagsByMangaIds(mangaIds: Collection<Long>): List<TagOfManga>
}

/** One row of [ExportDao.tagsByMangaIds]: a tag, and the manga it hangs off. */
data class TagOfManga(
	@ColumnInfo(name = "owner_manga_id") val mangaId: Long,
	@Embedded val tag: TagEntity,
)
