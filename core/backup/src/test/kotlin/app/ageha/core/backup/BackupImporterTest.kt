package app.ageha.core.backup

import app.ageha.core.database.AgehaDatabase
import app.ageha.core.database.AgehaDatabaseFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Importing a Kotatsu-Redo backup.
 *
 * This is the migration path, and it doubles as the schema's acceptance test: it drives every
 * column against data shaped exactly as the Android app writes it, so a divergence surfaces here
 * rather than in front of the first user trying to move.
 */
class BackupImporterTest {

	private lateinit var db: AgehaDatabase
	private lateinit var importer: BackupImporter

	@BeforeEach
	fun open() {
		db = AgehaDatabaseFactory.openInMemory()
		importer = BackupImporter(db)
	}

	@AfterEach
	fun close() {
		db.close()
	}

	private fun archive(dir: File, build: BackupArchiveBuilder.() -> Unit): File =
		BackupArchiveBuilder().apply(build).writeTo(File(dir, "backup.zip"))

	@Test
	@DisplayName("a full backup restores history, favourites, categories and sources")
	fun fullRestore(@TempDir dir: File) = runBlocking {
		val file = archive(dir) {
			section(BackupSection.INDEX, BackupArchiveBuilder.index())
			section(BackupSection.SOURCES, "[" + BackupArchiveBuilder.source("MANGADEX") + "]")
			section(BackupSection.CATEGORIES, "[" + BackupArchiveBuilder.category(1, "Reading") + "]")
			section(BackupSection.HISTORY, "[" + BackupArchiveBuilder.history(100L, "Frieren") + "]")
			section(BackupSection.FAVOURITES, "[" + BackupArchiveBuilder.favourite(200L, 1L, "Berserk") + "]")
		}

		val result = importer.import(file)

		assertEquals(1, result.restored[BackupSection.HISTORY])
		assertEquals(1, result.restored[BackupSection.FAVOURITES])
		assertEquals(1, result.restored[BackupSection.CATEGORIES])
		assertEquals(1, result.restored[BackupSection.SOURCES])
		assertTrue(result.isCompletelyClean, result.describe())

		assertEquals("Frieren", db.mangaDao().find(100L)?.title)
		assertEquals("Berserk", db.mangaDao().find(200L)?.title)
		assertEquals(1, db.favouritesDao().inCategory(1).size)
		assertNotNull(db.sourcesDao().find("MANGADEX"))
		assertEquals(1234, result.index?.appVersion)
	}

	@Test
	@DisplayName("reading position is restored exactly, not approximately")
	fun readingPositionIsExact(@TempDir dir: File) = runBlocking {
		// The whole point of importing history. A page number off by one, or a scroll offset
		// rounded away, drops the user somewhere other than where they stopped -- precisely the
		// thing that makes a migration feel untrustworthy.
		val file = archive(dir) {
			section(
				BackupSection.HISTORY,
				"[" + BackupArchiveBuilder.history(100L, "Frieren", page = 17, scroll = 0.3456789f) + "]",
			)
		}

		importer.import(file)

		val entry = requireNotNull(db.historyDao().find(100L))
		assertEquals(99L, entry.chapterId)
		assertEquals(17, entry.page)
		assertEquals(0.3456789f, entry.scroll)
		assertEquals(120, entry.chaptersCount)
	}

	@Test
	@DisplayName("the archive's -1 rating sentinel survives, rather than becoming a zero rating")
	fun ratingSentinelSurvives(@TempDir dir: File) = runBlocking {
		val file = archive(dir) {
			section(BackupSection.HISTORY, "[" + BackupArchiveBuilder.history(100L, "Frieren") + "]")
		}

		importer.import(file)

		// -1 means "this source publishes no rating". Reading it as 0 would mark every unrated
		// manga as rated zero, which is a different and much worse claim.
		assertEquals(-1f, db.mangaDao().find(100L)?.rating)
	}

	@Test
	@DisplayName("a manga appearing in two sections is written once, with its tags intact")
	fun duplicateMangaIsDeduplicated(@TempDir dir: File) = runBlocking {
		// Real archives embed the whole manga in every entry that references it, so the same
		// manga arrives twice whenever it is both in the library and in the history.
		val file = archive(dir) {
			section(BackupSection.CATEGORIES, "[" + BackupArchiveBuilder.category(1, "Reading") + "]")
			section(BackupSection.HISTORY, "[" + BackupArchiveBuilder.history(100L, "Frieren") + "]")
			section(BackupSection.FAVOURITES, "[" + BackupArchiveBuilder.favourite(100L, 1L, "Frieren") + "]")
		}

		val result = importer.import(file)

		assertTrue(result.isCompletelyClean, result.describe())
		assertEquals(1, db.mangaDao().tagsOf(100L).size, "the tag link should not be duplicated")
		assertNotNull(db.historyDao().find(100L))
		assertEquals(1, db.favouritesDao().inCategory(1).size)
	}

	@Test
	@DisplayName("a favourite pointing at an undefined category is dropped, and reported")
	fun danglingCategoryReferenceIsReported(@TempDir dir: File) = runBlocking {
		// Without this the foreign key rejects the row and takes the whole import down with it.
		val file = archive(dir) {
			section(BackupSection.CATEGORIES, "[" + BackupArchiveBuilder.category(1, "Reading") + "]")
			section(
				BackupSection.FAVOURITES,
				"[" + BackupArchiveBuilder.favourite(200L, 1L, "Kept") + "," +
					BackupArchiveBuilder.favourite(300L, 99L, "Dropped") + "]",
			)
		}

		val result = importer.import(file)

		assertEquals(1, result.restored[BackupSection.FAVOURITES])
		assertEquals(1, result.droppedRows.size)
		assertTrue(result.droppedRows.single().contains("category 99"), result.droppedRows.single())
		assertNotNull(db.mangaDao().find(200L))
		// The rest of the import still landed, which is the point of dropping the row rather than
		// failing the archive.
		assertEquals(1, db.favouritesDao().inCategory(1).size)
	}

	@Test
	@DisplayName("sections Ageha cannot restore yet are named rather than silently ignored")
	fun unsupportedSectionsAreReported(@TempDir dir: File) = runBlocking {
		val file = archive(dir) {
			section(BackupSection.HISTORY, "[" + BackupArchiveBuilder.history(100L, "Frieren") + "]")
			section(BackupSection.BOOKMARKS, "[]")
			section(BackupSection.STATISTICS, "[]")
		}

		val result = importer.import(file)

		// A user migrating years of reading should be told their bookmarks were left behind, not
		// discover it three weeks later.
		assertEquals(
			listOf(BackupSection.BOOKMARKS, BackupSection.STATISTICS),
			result.skippedSections.sortedBy { it.name },
		)
		assertTrue(result.describe().contains("bookmarks"), result.describe())
	}

	@Test
	@DisplayName("a backup from a newer Android app still imports what it can")
	fun forwardCompatibility(@TempDir dir: File) = runBlocking {
		val file = archive(dir) {
			section(BackupSection.HISTORY, "[" + BackupArchiveBuilder.history(100L, "Frieren") + "]")
			rawEntry("something_invented_later", """[{"a":1}]""")
		}

		val result = importer.import(file)

		assertEquals(1, result.restored[BackupSection.HISTORY])
		assertEquals(listOf("something_invented_later"), result.unknownEntries)
	}

	@Test
	@DisplayName("unknown JSON fields do not fail the import")
	fun unknownFieldsAreTolerated(@TempDir dir: File) = runBlocking {
		val file = archive(dir) {
			section(
				BackupSection.SOURCES,
				"""[{"source":"MANGADEX","sort_key":0,"used_at":1,"added_in":28,"a_future_field":"x"}]""",
			)
		}

		val result = importer.import(file)

		assertEquals(1, result.restored[BackupSection.SOURCES])
	}

	@Test
	@DisplayName("a file that is not an archive fails with an explanation, not a stack trace")
	fun nonArchiveIsRejectedClearly(@TempDir dir: File) {
		val notAZip = File(dir, "notes.txt").apply { writeText("this is not a backup") }

		val error = assertThrows<BackupImportException> { runBlocking { importer.import(notAZip) } }

		assertTrue(error.message!!.contains("zip"), error.message!!)
	}

	@Test
	@DisplayName("a missing file is reported as such")
	fun missingFileIsReported(@TempDir dir: File) {
		val error = assertThrows<BackupImportException> {
			runBlocking { importer.import(File(dir, "absent.zip")) }
		}
		assertTrue(error.message!!.contains("No such file"), error.message!!)
	}

	@Test
	@DisplayName("an empty archive imports nothing and says so, rather than failing")
	fun emptyArchiveIsHarmless(@TempDir dir: File) = runBlocking {
		val result = importer.import(archive(dir) { })

		assertEquals(0, result.totalRestored)
		assertTrue(result.isCompletelyClean)
	}

	@Test
	@DisplayName("importing the same backup twice is idempotent")
	fun importIsRepeatable(@TempDir dir: File) = runBlocking {
		// Users retry imports. A restore that duplicates a library on the second run is worse than
		// one that refuses; upserts keyed on the archive's own ids make repeating it safe.
		val file = archive(dir) {
			section(BackupSection.CATEGORIES, "[" + BackupArchiveBuilder.category(1, "Reading") + "]")
			section(BackupSection.FAVOURITES, "[" + BackupArchiveBuilder.favourite(200L, 1L, "Berserk") + "]")
			section(BackupSection.HISTORY, "[" + BackupArchiveBuilder.history(100L, "Frieren") + "]")
		}

		importer.import(file)
		importer.import(file)

		assertEquals(1, db.favouritesDao().inCategory(1).size)
		assertEquals(1, db.historyDao().observeRecent(10).first().size)
		assertEquals(1, db.mangaDao().tagsOf(100L).size)
	}

	@Test
	@DisplayName("the index is read from the array the Android app actually writes")
	fun indexIsArrayWrapped(@TempDir dir: File) = runBlocking {
		// The regression this pins: upstream writes every section, `index` included, through one
		// `writeJsonArray` helper, so the entry is `[{...}]`. The importer decoded it as a bare
		// object and swallowed the failure, which meant `result.index` was null for every real
		// Android backup ever imported -- while this suite passed, because the fixture wrote the
		// shape the parser wanted rather than the shape the app produces.
		val file = archive(dir) {
			section(BackupSection.INDEX, "[" + BackupArchiveBuilder.indexObject(4321) + "]")
		}

		val index = importer.import(file).index

		assertNotNull(index, "an array-wrapped index is the real shape and must be read")
		assertEquals("org.koitharu.kotatsu", index?.appId)
		assertEquals(4321, index?.appVersion)
	}

	@Test
	@DisplayName("a bare index object is still read")
	fun indexBareObject(@TempDir dir: File) = runBlocking {
		// Not the shape any app writes, but one branch's worth of tolerance covers an archive
		// somebody assembled by hand -- and this is the shape the suite used to assert, so
		// keeping it stops the fix from trading one blind spot for another.
		val file = archive(dir) {
			section(BackupSection.INDEX, BackupArchiveBuilder.indexObject(7))
		}

		assertEquals(7, importer.import(file).index?.appVersion)
	}

	@Test
	@DisplayName("an unreadable index does not fail the import")
	fun indexGarbageIsNotFatal(@TempDir dir: File) = runBlocking {
		// The index is provenance, not data. An archive holding a library worth restoring must
		// not be refused over a decorative field, at the one moment the user is migrating.
		val file = archive(dir) {
			section(BackupSection.INDEX, "not json at all")
			section(BackupSection.HISTORY, "[" + BackupArchiveBuilder.history(100L, "Frieren") + "]")
		}

		val result = importer.import(file)

		assertEquals(null, result.index)
		assertEquals(1, result.restored[BackupSection.HISTORY])
	}
}
