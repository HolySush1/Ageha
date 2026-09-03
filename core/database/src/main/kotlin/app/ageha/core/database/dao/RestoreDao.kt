package app.ageha.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.Upsert
import app.ageha.core.database.entity.FavouriteCategoryEntity
import app.ageha.core.database.entity.FavouriteEntity
import app.ageha.core.database.entity.HistoryEntity
import app.ageha.core.database.entity.MangaEntity
import app.ageha.core.database.entity.MangaSourceEntity
import app.ageha.core.database.entity.MangaTagsEntity
import app.ageha.core.database.entity.TagEntity

/**
 * Bulk restore, in one transaction.
 *
 * Separate from the feature DAOs because it has different priorities. Those are shaped for reading
 * one thing at a time; this exists to write a whole library at once, and it has two properties
 * they do not need:
 *
 *  - **Everything or nothing.** A half-restored library is worse than none, because the user has
 *    no way to tell which half is real. The `@Transaction` covers every table.
 *  - **Batched.** A real backup holds thousands of manga. Restoring row by row means thousands of
 *    round trips; passing lists lets Room bind them in single statements.
 *
 * The call order inside [restore] is load-bearing, not stylistic: the schema's foreign keys are
 * enforced, so manga must exist before the history and favourites that point at them, and
 * categories before favourites. Getting it wrong gives a constraint violation on a real archive
 * and a green test on an empty one.
 */
@Dao
interface RestoreDao {

	@Upsert
	suspend fun putSources(items: List<MangaSourceEntity>)

	@Upsert
	suspend fun putCategories(items: List<FavouriteCategoryEntity>)

	@Upsert
	suspend fun putManga(items: List<MangaEntity>)

	@Upsert
	suspend fun putTags(items: List<TagEntity>)

	/**
	 * Ignore on conflict: the same manga-tag pair legitimately arrives more than once, because a
	 * manga can appear in both the history and the favourites section of one archive.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun putTagLinks(items: List<MangaTagsEntity>)

	@Upsert
	suspend fun putHistory(items: List<HistoryEntity>)

	@Upsert
	suspend fun putFavourites(items: List<FavouriteEntity>)

	@Transaction
	suspend fun restore(payload: RestorePayload) {
		putSources(payload.sources)
		putCategories(payload.categories)
		putManga(payload.manga)
		putTags(payload.tags)
		putTagLinks(payload.tagLinks)
		putHistory(payload.history)
		putFavourites(payload.favourites)
	}
}

/**
 * Everything a restore writes, already mapped to entities.
 *
 * Deliberately entity-shaped rather than backup-shaped: the database module knows nothing about
 * the archive format, and the archive format knows nothing about SQL. Whoever produces one of
 * these has already done the interpreting.
 */
data class RestorePayload(
	val sources: List<MangaSourceEntity> = emptyList(),
	val categories: List<FavouriteCategoryEntity> = emptyList(),
	val manga: List<MangaEntity> = emptyList(),
	val tags: List<TagEntity> = emptyList(),
	val tagLinks: List<MangaTagsEntity> = emptyList(),
	val history: List<HistoryEntity> = emptyList(),
	val favourites: List<FavouriteEntity> = emptyList(),
)
