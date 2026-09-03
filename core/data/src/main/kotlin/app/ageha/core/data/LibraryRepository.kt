package app.ageha.core.data

import app.ageha.core.database.AgehaDatabase
import app.ageha.core.database.entity.FavouriteCategoryEntity
import app.ageha.core.database.entity.FavouriteEntity
import app.ageha.core.model.AgehaManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** One of the user's shelves. */
data class LibraryCategory(
	val id: Int,
	val title: String,
	val sortKey: Int,
	val isVisibleInLibrary: Boolean,
)

/**
 * The user's collection: categories, what is in them, and what they have been reading.
 *
 * Everything the library screen renders comes back as a [Flow], so adding a manga on the details
 * screen updates the grid behind it without either screen knowing about the other. Room turns a
 * `Flow` query into a subscription on the underlying tables, so this is a genuine invalidation
 * rather than polling.
 */
class LibraryRepository(private val database: AgehaDatabase) {

	private val favourites get() = database.favouritesDao()
	private val history get() = database.historyDao()
	private val manga get() = database.mangaDao()

	/**
	 * The pseudo-category standing for "everything".
	 *
	 * -1 rather than 0, because 0 is a real category id in the Android schema and a backup could
	 * legitimately contain one.
	 */
	val allCategoryId: Int get() = ALL_CATEGORY_ID

	fun observeCategories(): Flow<List<LibraryCategory>> =
		favourites.observeCategories().map { rows ->
			rows.map { LibraryCategory(it.categoryId, it.title, it.sortKey, it.isVisibleInLibrary) }
		}

	/**
	 * Live size of each category, plus the "all" pseudo-category. Drives the counts in the rail.
	 *
	 * "All" is not the sum of the categories: a manga can sit on several shelves at once, so
	 * adding the counts double-counts it. It comes from the deduplicated query instead.
	 */
	fun observeCategorySizes(): Flow<Map<Int, Int>> = combine(
		favourites.observeCategorySizes(),
		favourites.observeAllFavouriteManga(),
	) { sizes, all ->
		sizes.associate { it.categoryId to it.size } + (ALL_CATEGORY_ID to all.size)
	}

	/**
	 * The manga on one shelf, each with its reading state.
	 *
	 * The reading state is joined in memory rather than in SQL. A three-way join across manga,
	 * favourites and history with the right null handling is harder to read and harder to keep
	 * correct than two indexed queries and a map lookup, and a library is thousands of rows, not
	 * millions -- the join is not where the time goes.
	 */
	fun observeLibrary(categoryId: Int): Flow<List<LibraryEntry>> {
		val mangaFlow = if (categoryId == ALL_CATEGORY_ID) {
			favourites.observeAllFavouriteManga()
		} else {
			favourites.observeMangaInCategory(categoryId)
		}
		return combine(mangaFlow, history.observeRecentWithManga(HISTORY_JOIN_LIMIT)) { shelf, recent ->
			val byId = recent.associateBy { it.manga.id }
			shelf.map { entity ->
				val row = byId[entity.id]
				if (row == null) {
					LibraryEntry.unread(MangaMapping.toModel(entity))
				} else {
					LibraryEntry.of(row, currentChapterCount = null)
				}
			}
		}
	}

	/** Continue reading. The first thing the library screen draws. */
	fun observeRecent(limit: Int = 20): Flow<List<LibraryEntry>> =
		history.observeRecentWithManga(limit).map { rows ->
			rows.map { LibraryEntry.of(it, currentChapterCount = null) }
		}

	fun observeIsFavourite(mangaId: Long): Flow<Boolean> =
		favourites.observeFavouriteCount(mangaId).map { it > 0 }

	suspend fun categoriesOf(mangaId: Long): Set<Int> = favourites.categoriesOf(mangaId).toSet()

	/**
	 * Put a manga on a shelf.
	 *
	 * The manga row is written first and in the same call, because `favourites.manga_id` is a
	 * foreign key: favouriting something the library has never stored would otherwise fail with a
	 * constraint violation rather than with anything a user could act on.
	 */
	suspend fun addToCategory(manga: AgehaManga, categoryId: Int, now: Long = System.currentTimeMillis()) {
		val tags = manga.tags.map { MangaMapping.toEntity(it) }
		database.mangaDao().upsertWithTags(MangaMapping.toEntity(manga), tags)
		favourites.upsert(
			FavouriteEntity(
				mangaId = manga.id,
				categoryId = categoryId,
				sortKey = 0,
				createdAt = now,
				deletedAt = 0,
				isPinned = false,
			),
		)
	}

	/**
	 * Take a manga off a shelf.
	 *
	 * Soft delete, like the Android app: the tombstone is how sync tells "removed elsewhere" from
	 * "never existed". Hard-deleting here would make a removal look like an addition to whichever
	 * device syncs next.
	 */
	suspend fun removeFromCategory(mangaId: Long, categoryId: Int, now: Long = System.currentTimeMillis()) {
		favourites.markDeleted(mangaId, categoryId, now)
	}

	suspend fun removeFromLibrary(mangaId: Long, now: Long = System.currentTimeMillis()) {
		for (categoryId in favourites.categoriesOf(mangaId)) {
			favourites.markDeleted(mangaId, categoryId, now)
		}
	}

	suspend fun createCategory(title: String, now: Long = System.currentTimeMillis()): Int {
		val sortKey = favourites.nextCategorySortKey()
		favourites.upsertCategory(
			FavouriteCategoryEntity(
				categoryId = 0, // autogenerated
				createdAt = now,
				sortKey = sortKey,
				title = title,
				// The Android app stores its sort order as a string enum name. Ageha writes the
				// same default so a category made here reads correctly if the backup goes back.
				order = DEFAULT_CATEGORY_ORDER,
				track = true,
				isVisibleInLibrary = true,
				deletedAt = 0,
			),
		)
		return favourites.categories().first { it.title == title }.categoryId
	}

	suspend fun deleteCategory(categoryId: Int, now: Long = System.currentTimeMillis()) {
		favourites.markCategoryDeleted(categoryId, now)
	}

	suspend fun mangaCount(): Int = manga.count()

	private companion object {
		const val ALL_CATEGORY_ID = -1

		/** Matches the Android app's `ListSortOrder.NEWEST`. */
		const val DEFAULT_CATEGORY_ORDER = "NEWEST"

		/**
		 * How much history to consult when decorating a shelf.
		 *
		 * A shelf entry only needs its reading state if the manga was read *recently enough to
		 * matter*; older progress still shows on the details screen. Capping this keeps the
		 * library query from dragging the entire history table into memory on every emission.
		 */
		const val HISTORY_JOIN_LIMIT = 500
	}
}
