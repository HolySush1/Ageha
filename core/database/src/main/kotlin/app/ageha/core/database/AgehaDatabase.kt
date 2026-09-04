package app.ageha.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import app.ageha.core.database.dao.ExportDao
import app.ageha.core.database.dao.FavouritesDao
import app.ageha.core.database.dao.HistoryDao
import app.ageha.core.database.dao.MangaDao
import app.ageha.core.database.dao.MangaPrefsDao
import app.ageha.core.database.dao.RestoreDao
import app.ageha.core.database.dao.SourcesDao
import app.ageha.core.database.dao.SyncDao
import app.ageha.core.database.entity.ChapterEntity
import app.ageha.core.database.entity.FavouriteCategoryEntity
import app.ageha.core.database.entity.FavouriteEntity
import app.ageha.core.database.entity.HistoryEntity
import app.ageha.core.database.entity.MangaEntity
import app.ageha.core.database.entity.MangaPrefsEntity
import app.ageha.core.database.entity.MangaSourceEntity
import app.ageha.core.database.entity.MangaTagsEntity
import app.ageha.core.database.entity.TagEntity

/**
 * Ageha's library database.
 *
 * Declared at version 28 to match the Android app's current schema rather than starting at 1. See
 * [AGEHA_DATABASE_VERSION] for why, and `docs/ARCHITECTURE.md` 1.3.
 *
 * The entity set here is the subset Milestone 4 needs -- library, favourites, history, sources.
 * The Android app has 17 entities at v28; the remaining nine cover tracking, scrobbling,
 * bookmarks, statistics, suggestions and local files, and arrive with the features that use them.
 * Adding an entity is a schema change, so each one lands with a migration rather than by
 * retroactively editing version 28.
 */
@Database(
	entities = [
		MangaEntity::class,
		TagEntity::class,
		MangaTagsEntity::class,
		ChapterEntity::class,
		HistoryEntity::class,
		FavouriteEntity::class,
		FavouriteCategoryEntity::class,
		MangaSourceEntity::class,
		MangaPrefsEntity::class,
	],
	version = AGEHA_DATABASE_VERSION,
	exportSchema = true,
	// Adding a table is a change Room can derive a migration for on its own, and a derived
	// migration is safer than a hand-written one -- it cannot disagree with the schema it was
	// generated from. Anything that renames or retypes a column will need a spec here instead.
	autoMigrations = [AutoMigration(from = 28, to = 29), AutoMigration(from = 29, to = 30)],
)
abstract class AgehaDatabase : RoomDatabase() {

	abstract fun mangaDao(): MangaDao

	abstract fun historyDao(): HistoryDao

	abstract fun favouritesDao(): FavouritesDao

	abstract fun sourcesDao(): SourcesDao

	abstract fun mangaPrefsDao(): MangaPrefsDao

	abstract fun restoreDao(): RestoreDao

	abstract fun exportDao(): ExportDao

	abstract fun syncDao(): SyncDao
}
