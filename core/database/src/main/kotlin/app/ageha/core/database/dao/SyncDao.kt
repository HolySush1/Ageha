package app.ageha.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import app.ageha.core.database.entity.FavouriteCategoryEntity
import app.ageha.core.database.entity.FavouriteEntity
import app.ageha.core.database.entity.HistoryEntity

/**
 * Bulk read and tombstone collection, for the sync engine.
 *
 * A third bulk DAO next to [RestoreDao] and [ExportDao], and it exists because sync wants the one
 * thing both of those deliberately refuse to give: **the soft-deleted rows**.
 *
 * That is the whole difference, and it is not a detail. A backup is a snapshot of what a library
 * *is*, so a tombstone in one would restore a deletion as though it were data. A sync payload is a
 * statement of everything that has *happened*, and a tombstone is the only way to tell the server
 * "this was deleted here" apart from "this never existed here" -- without it, every device that
 * still holds the row would push it straight back on the next round. The `deleted_at` columns have
 * been in the schema since Milestone 4 carrying a comment saying sync needs them; this is the code
 * that finally does.
 *
 * Unpaged, unlike [ExportDao], because the protocol is not paged: the Android client sends its
 * entire history or favourites set in one POST body and the server answers with the merged set.
 * Windowing the read would not make the request any smaller, and matching the protocol matters
 * more than the shape of the query behind it.
 */
@Dao
interface SyncDao {

	/** Every history row, tombstones included. See the class comment for why that matters. */
	@Query("SELECT * FROM history ORDER BY updated_at DESC, manga_id ASC")
	suspend fun allHistory(): List<HistoryEntity>

	@Query("SELECT * FROM favourites ORDER BY created_at DESC, manga_id ASC, category_id ASC")
	suspend fun allFavourites(): List<FavouriteEntity>

	@Query("SELECT * FROM favourite_categories ORDER BY sort_key ASC, category_id ASC")
	suspend fun allCategories(): List<FavouriteCategoryEntity>

	/*
	 * Tombstone collection.
	 *
	 * A tombstone only has to outlive the slowest device that might still be holding the row it
	 * cancels. Keeping them forever grows the sync payload without bound -- every deleted entry
	 * is re-sent on every sync, for the life of the account -- so they are dropped once they are
	 * old enough that any device still absent has bigger problems. The window is the Android
	 * app's, and it is deliberately the same: two clients garbage-collecting on different
	 * schedules is how a deletion comes back from the dead.
	 *
	 * Only ever called *after* a successful exchange with the server. Collecting a tombstone the
	 * server has not seen deletes the deletion.
	 */
	@Query("DELETE FROM history WHERE deleted_at != 0 AND deleted_at < :before")
	suspend fun purgeHistoryTombstones(before: Long): Int

	@Query("DELETE FROM favourites WHERE deleted_at != 0 AND deleted_at < :before")
	suspend fun purgeFavouriteTombstones(before: Long): Int

	@Query("DELETE FROM favourite_categories WHERE deleted_at != 0 AND deleted_at < :before")
	suspend fun purgeCategoryTombstones(before: Long): Int
}
