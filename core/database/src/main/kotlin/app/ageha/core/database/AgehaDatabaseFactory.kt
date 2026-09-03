package app.ageha.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Opens the library database.
 *
 * Room 2.7+ is a Kotlin Multiplatform library with JVM as a first-class target, so none of this
 * needs Android. Two things do need saying out loud, because both are easy to get wrong and
 * neither fails at compile time:
 *
 *  - **The driver is bundled**, not the platform's. Desktop JVMs have no SQLite, and a
 *    system-installed one would vary by machine and by user. `sqlite-bundled` ships a known build.
 *  - **Foreign keys must be switched on per connection.** SQLite disables them by default, and the
 *    schema leans on `ON DELETE CASCADE` to keep tags, chapters, history and favourites from
 *    outliving the manga they belong to. Without the pragma the constraints are decorative and the
 *    database quietly accumulates orphans.
 */
object AgehaDatabaseFactory {

	fun open(databaseFile: File, queryDispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO): AgehaDatabase {
		databaseFile.parentFile?.mkdirs()
		return Room.databaseBuilder<AgehaDatabase>(name = databaseFile.absolutePath)
			.setDriver(BundledSQLiteDriver())
			.setQueryCoroutineContext(queryDispatcher)
			.addCallback(ForeignKeysCallback)
			.build()
	}

	/** An in-memory database, for tests. Same schema, same driver, no file. */
	fun openInMemory(queryDispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO): AgehaDatabase =
		Room.inMemoryDatabaseBuilder<AgehaDatabase>()
			.setDriver(BundledSQLiteDriver())
			.setQueryCoroutineContext(queryDispatcher)
			.addCallback(ForeignKeysCallback)
			.build()

	private object ForeignKeysCallback : androidx.room.RoomDatabase.Callback() {
		override fun onOpen(connection: androidx.sqlite.SQLiteConnection) {
			connection.execSQL("PRAGMA foreign_keys = ON")
		}
	}
}
