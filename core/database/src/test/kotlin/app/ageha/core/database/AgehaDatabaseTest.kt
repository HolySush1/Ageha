package app.ageha.core.database

import app.ageha.core.database.entity.ChapterEntity
import app.ageha.core.database.entity.FavouriteCategoryEntity
import app.ageha.core.database.entity.FavouriteEntity
import app.ageha.core.database.entity.HistoryEntity
import app.ageha.core.database.entity.MangaEntity
import app.ageha.core.database.entity.MangaPrefsEntity
import app.ageha.core.database.entity.MangaSourceEntity
import app.ageha.core.database.entity.TagEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Room, running on a desktop JVM.
 *
 * The point of the first tests is not the CRUD, which is unremarkable -- it is that Room 2.8 with
 * the bundled SQLite driver genuinely works off Android, which was the open risk in choosing Room
 * over SQLDelight. The rest cover constraints the schema depends on that would otherwise only be
 * discovered as data loss.
 */
class AgehaDatabaseTest {

	private lateinit var db: AgehaDatabase

	@BeforeEach
	fun open() {
		db = AgehaDatabaseFactory.openInMemory()
	}

	@AfterEach
	fun close() {
		db.close()
	}

	private fun manga(id: Long, title: String = "Test Manga") = MangaEntity(
		id = id,
		title = title,
		altTitles = null,
		url = "/manga/" + id,
		publicUrl = "https://example.org/manga/" + id,
		rating = -1f,
		isNsfw = false,
		contentRating = null,
		coverUrl = "https://example.org/cover/" + id + ".jpg",
		largeCoverUrl = null,
		state = "ONGOING",
		authors = null,
		source = "MANGADEX",
	)

	@Test
	@DisplayName("a manga round-trips through a real SQLite file on disk")
	fun roundTripOnDisk(@TempDir dir: File) = runBlocking {
		// On disk rather than in memory, because the file path, the bundled driver and the
		// directory creation are all part of what had to be proven to work on desktop.
		val fileDb = AgehaDatabaseFactory.open(File(dir, "nested/ageha.db"))
		try {
			fileDb.mangaDao().upsert(manga(1L, "Frieren"))
			assertEquals("Frieren", fileDb.mangaDao().find(1L)?.title)
		} finally {
			fileDb.close()
		}
		assertTrue(File(dir, "nested/ageha.db").isFile, "the database file should exist on disk")
	}

	@Test
	@DisplayName("a manga and its tags are stored together")
	fun mangaWithTags() = runBlocking {
		val tags = listOf(
			TagEntity(10L, "Fantasy", "fantasy", "MANGADEX", isPinned = false),
			TagEntity(11L, "Adventure", "adventure", "MANGADEX", isPinned = false),
		)
		db.mangaDao().upsertWithTags(manga(1L), tags)

		assertEquals(
			listOf("Adventure", "Fantasy"),
			db.mangaDao().tagsOf(1L).map { it.title }.sorted(),
		)
	}

	@Test
	@DisplayName("deleting a manga cascades to its tags, chapters and history")
	fun cascadeOnDelete() = runBlocking {
		// The pragma this depends on is off by default in SQLite. Without it the foreign keys are
		// decorative and the database silently accumulates orphans for every removed manga.
		db.mangaDao().upsertWithTags(
			manga(1L),
			listOf(TagEntity(10L, "Fantasy", "fantasy", "MANGADEX", isPinned = false)),
		)
		db.mangaDao().upsertChapters(
			listOf(ChapterEntity(100L, 1L, "Chapter 1", 1f, 0, "/c/1", null, 0L, null, "MANGADEX", 0)),
		)
		db.historyDao().upsert(HistoryEntity(1L, 0L, 0L, 100L, 3, 0f, 0.2f, 0L, 1))

		assertEquals(1, db.mangaDao().chaptersOf(1L).size)
		assertNotNull(db.historyDao().find(1L))

		db.mangaDao().delete(1L)

		assertTrue(db.mangaDao().tagsOf(1L).isEmpty(), "tag links should cascade")
		assertTrue(db.mangaDao().chaptersOf(1L).isEmpty(), "chapters should cascade")
		assertEquals(0, db.historyDao().countIncludingDeleted(), "history should cascade")
	}

	@Test
	@DisplayName("history is soft deleted, so another device can still reconcile it")
	fun historyIsSoftDeleted() = runBlocking {
		db.mangaDao().upsert(manga(1L))
		db.historyDao().upsert(HistoryEntity(1L, 0L, 5L, 100L, 3, 0f, 0.2f, 0L, 1))

		db.historyDao().markDeleted(1L, now = 1_000L)

		assertNull(db.historyDao().find(1L), "a deleted entry should not be read back")
		assertTrue(db.historyDao().observeRecent(10).first().isEmpty())
		// The row itself survives. Sync needs the tombstone to tell "deleted on another device"
		// from "never existed here"; hard-deleting would make a deletion look like an absence and
		// the entry would come straight back on the next sync.
		assertEquals(1, db.historyDao().countIncludingDeleted())

		assertEquals(1, db.historyDao().purgeDeletedBefore(before = 2_000L))
		assertEquals(0, db.historyDao().countIncludingDeleted())
	}

	@Test
	@DisplayName("history reads newest first")
	fun historyOrderIsMostRecentFirst() = runBlocking {
		db.mangaDao().upsert(manga(1L, "Older"))
		db.mangaDao().upsert(manga(2L, "Newer"))
		db.historyDao().upsert(HistoryEntity(1L, 0L, 100L, 0L, 0, 0f, 0f, 0L, 1))
		db.historyDao().upsert(HistoryEntity(2L, 0L, 200L, 0L, 0, 0f, 0f, 0L, 1))

		assertEquals(listOf(2L, 1L), db.historyDao().observeRecent(10).first().map { it.mangaId })
	}

	@Test
	@DisplayName("reading position survives a re-open, exactly")
	fun readingPositionIsPreserved(@TempDir dir: File) = runBlocking {
		// The brief calls this non-negotiable: position restored exactly, not approximately. The
		// scroll offset is a float and is the part most likely to be quietly rounded away.
		val file = File(dir, "ageha.db")
		val first = AgehaDatabaseFactory.open(file)
		try {
			first.mangaDao().upsert(manga(1L))
			first.historyDao().upsert(HistoryEntity(1L, 0L, 0L, 100L, 17, 0.3456789f, 0.62f, 0L, 42))
		} finally {
			first.close()
		}

		val reopened = AgehaDatabaseFactory.open(file)
		try {
			val entry = requireNotNull(reopened.historyDao().find(1L)) { "history entry vanished across a re-open" }
			assertEquals(100L, entry.chapterId)
			assertEquals(17, entry.page)
			assertEquals(0.3456789f, entry.scroll)
		} finally {
			reopened.close()
		}
	}

	@Test
	@DisplayName("favourites live in categories and are soft deleted too")
	fun favouritesInCategories() = runBlocking {
		db.mangaDao().upsert(manga(1L))
		val categoryId = db.favouritesDao().upsertCategory(
			FavouriteCategoryEntity(
				categoryId = 0,
				createdAt = 0L,
				sortKey = 0,
				title = "Reading",
				order = "NEWEST",
				track = true,
				isVisibleInLibrary = true,
				deletedAt = 0L,
			),
		).toInt()

		db.favouritesDao().upsert(FavouriteEntity(1L, categoryId, 0, 0L, 0L, isPinned = false))
		assertEquals(1, db.favouritesDao().inCategory(categoryId).size)

		db.favouritesDao().markDeleted(1L, categoryId, now = 1_000L)
		assertTrue(db.favouritesDao().inCategory(categoryId).isEmpty())
		assertEquals(1, db.favouritesDao().countIncludingDeleted(), "the tombstone should remain")
	}

	@Test
	@DisplayName("sources are keyed by name, never by ordinal")
	fun sourcesAreKeyedByName() = runBlocking {
		// The same rule the parser layer follows, for the same reason: the source list is
		// KSP-generated per parsers build, so an ordinal would silently repoint a user's rows at a
		// different site the day upstream reorders anything.
		db.sourcesDao().upsert(MangaSourceEntity("MANGADEX", true, 0, 28, 0L, isPinned = false, cfState = 0))
		db.sourcesDao().upsert(MangaSourceEntity("WEEBCENTRAL", false, 1, 28, 0L, isPinned = false, cfState = 0))

		assertEquals("MANGADEX", db.sourcesDao().find("MANGADEX")?.source)
		assertEquals(listOf("MANGADEX"), db.sourcesDao().observeEnabled().first().map { it.source })
		assertNull(db.sourcesDao().find("A_SOURCE_THAT_NEVER_EXISTED"))
	}

	@Test
	@DisplayName("the schema starts at the Android app's version 28 and only moves forward")
	fun schemaVersionBaseline() {
		// 28 is the Android app's version and the floor Ageha started from, not a number to
		// return to. Backup import depends on the *tables* matching; the version number only has
		// to be at or above the baseline so a migration path exists from an Android-shaped
		// database. Starting at 1 would have made that impossible, which is what this guards.
		assertTrue(AGEHA_DATABASE_VERSION >= 28) {
			"the schema must never fall below the Android baseline of 28"
		}
		val schemas = File("schemas/app.ageha.core.database.AgehaDatabase")
		assertTrue(File(schemas, "28.json").exists()) {
			"the 28 baseline schema must stay exported; migrations are derived from it"
		}
		assertTrue(File(schemas, "$AGEHA_DATABASE_VERSION.json").exists()) {
			"the current schema must be exported, or Room cannot derive the next migration"
		}
	}

	/**
	 * Version 29 adds the `preferences` table by auto-migration.
	 *
	 * Opening the database is what runs the migration, so a broken one fails here rather than on
	 * a user's machine holding their library.
	 */
	@Test
	@DisplayName("per-manga reader preferences round trip")
	fun mangaPreferencesRoundTrip() = runBlocking {
		val manga = manga(id = 501)
		db.mangaDao().upsert(manga)
		db.mangaPrefsDao().upsert(
			MangaPrefsEntity(
				mangaId = manga.id,
				// 2 is WEBTOON in the Android app's ReaderMode. The ids are not ordinals -- see
				// the note on MangaPrefsEntity -- so this asserts the *id* survives.
				mode = 2,
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
		assertEquals(2, db.mangaPrefsDao().find(manga.id)?.mode)

		// The foreign key cascades, so removing a manga must not leave its reader settings behind.
		db.mangaDao().delete(manga.id)
		assertNull(db.mangaPrefsDao().find(manga.id))
	}
}
