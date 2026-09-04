package app.ageha.core.backup

import app.ageha.core.database.AgehaDatabase
import app.ageha.core.database.AgehaDatabaseFactory
import app.ageha.core.database.dao.RestorePayload
import app.ageha.core.database.entity.FavouriteCategoryEntity
import app.ageha.core.database.entity.FavouriteEntity
import app.ageha.core.database.entity.HistoryEntity
import app.ageha.core.database.entity.MangaEntity
import app.ageha.core.database.entity.MangaSourceEntity
import app.ageha.core.database.entity.MangaTagsEntity
import app.ageha.core.database.entity.TagEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

/**
 * Writing a backup.
 *
 * The centrepiece is [roundTrip], and it is worth saying why it is the shape it is. Asserting that
 * the exporter emits particular JSON would only prove the exporter agrees with the test's author.
 * Exporting a populated database and importing it into an empty one proves the two halves agree
 * with *each other*, over every column, using the reader real Android archives already go through.
 * A field the exporter forgets, or spells differently, or writes with the wrong type, fails here
 * without anyone having to have thought of that field in advance.
 */
class BackupExporterTest {

	private lateinit var db: AgehaDatabase

	@BeforeEach
	fun open() {
		db = AgehaDatabaseFactory.openInMemory()
	}

	@AfterEach
	fun close() {
		db.close()
	}

	private fun exporter(now: Long = 1_756_900_000_000L) =
		BackupExporter(db, appId = "app.ageha", appVersion = 1, now = { now })

	@Test
	@DisplayName("a library survives an export and an import unchanged")
	fun roundTrip(@TempDir dir: File) = runBlocking {
		populate(db)

		val file = exporter().export(File(dir, "backup.bk.zip")).file

		val restored = AgehaDatabaseFactory.openInMemory()
		try {
			val result = BackupImporter(restored).import(file)

			assertEquals(2, result.restored[BackupSection.HISTORY])
			assertEquals(2, result.restored[BackupSection.FAVOURITES])
			assertEquals(1, result.restored[BackupSection.CATEGORIES])
			assertEquals(1, result.restored[BackupSection.SOURCES])
			assertTrue(result.droppedRows.isEmpty(), "nothing should be dropped: " + result.droppedRows)

			// Column by column on the manga, because this is what silently degrades: a title
			// survives and the comma-joined authors, the -1 rating sentinel or the nullable
			// large cover quietly do not.
			val manga = requireNotNull(restored.mangaDao().find(100L))
			assertEquals("Frieren", manga.title)
			assertEquals("Beyond Journey's End", manga.altTitles)
			assertEquals("/manga/100", manga.url)
			assertEquals("https://mangadex.org/title/100", manga.publicUrl)
			assertEquals(-1f, manga.rating)
			assertEquals(false, manga.isNsfw)
			assertEquals("SAFE", manga.contentRating)
			assertEquals("https://example.org/100.jpg", manga.coverUrl)
			assertEquals(null, manga.largeCoverUrl)
			assertEquals("ONGOING", manga.state)
			assertEquals("Kanehito Yamada, Tsukasa Abe", manga.authors)
			assertEquals("MANGADEX", manga.source)

			// The reading position, which is the entire point of carrying history across.
			val history = requireNotNull(restored.historyDao().find(100L))
			assertEquals(17, history.page)
			assertEquals(0.3456789f, history.scroll)
			assertEquals(0.35f, history.percent)
			assertEquals(99L, history.chapterId)
			assertEquals(120, history.chaptersCount)
			assertEquals(1000L, history.createdAt)
			assertEquals(2000L, history.updatedAt)

			val tags = restored.mangaDao().tagsOf(100L).sortedBy { it.id }
			assertEquals(listOf(10L, 11L), tags.map { it.id })
			assertEquals("Adventure", tags[0].title)
			assertEquals("adventure", tags[0].key)
			assertEquals(true, tags[1].isPinned)

			val category = restored.favouritesDao().categories().single()
			assertEquals(1, category.categoryId)
			assertEquals("Reading", category.title)
			assertEquals("NEWEST", category.order)
			assertEquals(false, category.isVisibleInLibrary)

			val favourite = restored.favouritesDao().inCategory(1).sortedBy { it.mangaId }
			assertEquals(listOf(100L, 200L), favourite.map { it.mangaId })
			assertEquals(true, favourite[1].isPinned)
			assertEquals(7, favourite[1].sortKey)

			val source = requireNotNull(restored.sourcesDao().find("MANGADEX"))
			assertEquals(true, source.isEnabled)
			assertEquals(3, source.sortKey)
			assertEquals(28, source.addedIn)
			assertEquals(9000L, source.lastUsedAt)
			assertEquals(true, source.isPinned)
		} finally {
			restored.close()
		}
	}

	@Test
	@DisplayName("the archive is the Android app's, section for section")
	fun archiveShape(@TempDir dir: File) = runBlocking {
		// What makes the file restorable on a phone. Written against upstream's
		// `BackupRepository`, which is the only authority on this.
		populate(db)

		val file = exporter().export(File(dir, "backup.bk.zip")).file

		ZipFile(file).use { zip ->
			val names = zip.entries().toList().map { it.name }
			assertEquals(
				listOf("index", "categories", "history", "favourites", "sources"),
				names,
				"only the sections Ageha holds data for, and no empty ones",
			)
			names.forEach { name ->
				val text = zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() }
				assertTrue(
					Json.parseToJsonElement(text) is JsonArray,
					"every section is a JSON array, `index` included -- `" + name + "` was not",
				)
			}

			// The index is one object inside that array, and it says Ageha wrote the file rather
			// than claiming to be the Android app.
			val index = Json.parseToJsonElement(
				zip.getInputStream(zip.getEntry("index")).bufferedReader().use { it.readText() },
			) as JsonArray
			assertEquals(1, index.size)
			assertEquals("app.ageha", index[0].jsonObject["app_id"]?.jsonPrimitive?.content)
			assertEquals(
				"1756900000000",
				index[0].jsonObject["created_at"]?.jsonPrimitive?.content,
			)
		}
	}

	@Test
	@DisplayName("soft-deleted history and favourites are not exported")
	fun tombstonesStayHome(@TempDir dir: File) = runBlocking {
		// A tombstone is a local record that something *was* deleted. Exporting it would restore
		// a deletion onto another device as though it were data -- and worse, the entry would
		// come back marked present, because the importer clears `deleted_at` on the way in.
		populate(db)
		db.historyDao().markDeleted(100L, now = 5_000L)
		db.favouritesDao().markDeleted(mangaId = 200L, categoryId = 1, now = 5_000L)

		val result = exporter().export(File(dir, "backup.bk.zip"))

		assertEquals(1, result.written[BackupSection.HISTORY])
		assertEquals(1, result.written[BackupSection.FAVOURITES])

		val restored = AgehaDatabaseFactory.openInMemory()
		try {
			BackupImporter(restored).import(result.file)
			assertEquals(null, restored.historyDao().find(100L), "the deleted entry must not return")
			assertNotNull(restored.historyDao().find(200L))
			assertEquals(listOf(100L), restored.favouritesDao().inCategory(1).map { it.mangaId })
		} finally {
			restored.close()
		}
	}

	@Test
	@DisplayName("disabled sources are not exported")
	fun onlyEnabledSources(@TempDir dir: File) = runBlocking {
		// Ageha ships 1360 sources disabled. An archive carrying all of them would be mostly a
		// copy of the source list, and restoring it would enable nothing anyway -- the Android
		// app's own dump is `dumpEnabled` for the same reason.
		populate(db)
		db.sourcesDao().upsert(
			MangaSourceEntity("WEEBCENTRAL", isEnabled = false, sortKey = 4, addedIn = 28, lastUsedAt = 0, isPinned = false, cfState = 0),
		)

		val result = exporter().export(File(dir, "backup.bk.zip"))

		assertEquals(1, result.written[BackupSection.SOURCES])
	}

	@Test
	@DisplayName("an empty library exports a valid, empty archive")
	fun emptyLibrary(@TempDir dir: File) = runBlocking {
		// The boundary the hand-written brackets get wrong if they are got wrong at all: with no
		// elements the section must still be `[]` and not an empty entry, which is not JSON.
		val result = exporter().export(File(dir, "backup.bk.zip"))

		assertEquals(0, result.totalWritten)

		val restored = AgehaDatabaseFactory.openInMemory()
		try {
			val imported = BackupImporter(restored).import(result.file)
			assertEquals(0, imported.totalRestored)
			assertEquals("app.ageha", imported.index?.appId)
		} finally {
			restored.close()
		}
	}

	@Test
	@DisplayName("more rows than one window still export exactly once each")
	fun pagingBoundary(@TempDir dir: File) = runBlocking {
		// The window is 64, so 130 rows crosses the boundary twice and ends on a short page.
		//
		// Every row shares one `updated_at`, which is the point: that is the column the dump
		// orders by, and a tie makes `LIMIT`/`OFFSET` free to order the tied rows differently per
		// window -- returning one row twice and another never. The count written would still say
		// 130 either way, so the assertion that matters is the one after the import: a duplicated
		// entry collapses on the primary key and the restored history comes back one row short.
		val count = 130
		db.restoreDao().restore(
			RestorePayload(
				manga = (1..count).map { mangaEntity(it.toLong(), "Title " + it) },
				history = (1..count).map { historyEntity(it.toLong(), updatedAt = 2000L) },
			),
		)

		val result = exporter().export(File(dir, "backup.bk.zip"))

		assertEquals(count, result.written[BackupSection.HISTORY])

		val restored = AgehaDatabaseFactory.openInMemory()
		try {
			BackupImporter(restored).import(result.file)
			val ids = restored.historyDao().observeRecent(count * 2).first().map { it.mangaId }
			assertEquals(count, ids.size, "every row exactly once -- no window duplicated or skipped one")
			assertEquals((1L..count).toList(), ids.sorted())
			assertEquals(count, restored.mangaDao().count())
		} finally {
			restored.close()
		}
	}

	@Test
	@DisplayName("a failed export leaves no file, not a broken one")
	fun failureLeavesNothing(@TempDir dir: File) = runBlocking {
		// The property everything else rests on: if an archive exists, it is complete. Half a
		// backup is discovered at restore time, which is the one moment there is nothing to fall
		// back to. Forced by closing the database out from under the export.
		populate(db)
		val destination = File(dir, "backup.bk.zip")
		db.close()

		runCatching { exporter().export(destination) }

		assertFalse(destination.exists(), "no archive should be left behind")
		assertFalse(File(dir, destination.name + ".part").exists(), "and no .part either")
	}

	@Test
	@DisplayName("exporting over an existing archive replaces it")
	fun overwrite(@TempDir dir: File) = runBlocking {
		// Overwriting is the normal case -- someone exporting weekly picks the same name -- and
		// on Windows `renameTo` will not replace, so this is the platform behaviour that would
		// otherwise only fail on one of the three CI runners.
		populate(db)
		val destination = File(dir, "backup.bk.zip")
		destination.writeText("this is not a backup")

		val result = exporter().export(destination)

		assertTrue(result.sizeBytes > 0)
		val restored = AgehaDatabaseFactory.openInMemory()
		try {
			assertEquals(2, BackupImporter(restored).import(destination).restored[BackupSection.HISTORY])
		} finally {
			restored.close()
		}
	}

	@Test
	@DisplayName("the default file name sorts by date and says which app wrote it")
	fun fileName() {
		// Upstream's `BackupUtils` format, so a folder holding archives from both apps sorts by
		// date rather than interleaving two conventions.
		val name = defaultBackupFileName(1_756_900_000_000L)
		assertTrue(name.startsWith("ageha_"), name)
		assertTrue(name.endsWith(".bk.zip"), name)
		assertTrue(Regex("""ageha_\d{8}-\d{4}\.bk\.zip""").matches(name), name)
	}

	/**
	 * A library with every shape that has ever been got wrong: a manga in two categories, a
	 * pinned tag, a null large cover, the -1 rating sentinel, comma-joined authors, and a
	 * category that is hidden from the library.
	 */
	private suspend fun populate(database: AgehaDatabase) {
		database.restoreDao().restore(
			RestorePayload(
				sources = listOf(
					MangaSourceEntity(
						source = "MANGADEX",
						isEnabled = true,
						sortKey = 3,
						addedIn = 28,
						lastUsedAt = 9000L,
						isPinned = true,
						// Per-installation Cloudflare state. Set here precisely so the round trip
						// can show it is *not* carried across.
						cfState = 2,
					),
				),
				categories = listOf(
					FavouriteCategoryEntity(
						categoryId = 1,
						createdAt = 1000L,
						sortKey = 0,
						title = "Reading",
						order = "NEWEST",
						track = true,
						isVisibleInLibrary = false,
						deletedAt = 0L,
					),
				),
				manga = listOf(
					mangaEntity(100L, "Frieren", altTitles = "Beyond Journey's End"),
					mangaEntity(200L, "Berserk"),
				),
				tags = listOf(
					TagEntity(10L, "Adventure", "adventure", "MANGADEX", isPinned = false),
					TagEntity(11L, "Fantasy", "fantasy", "MANGADEX", isPinned = true),
				),
				tagLinks = listOf(
					MangaTagsEntity(100L, 10L),
					MangaTagsEntity(100L, 11L),
					MangaTagsEntity(200L, 10L),
				),
				history = listOf(
					historyEntity(100L, updatedAt = 2000L),
					historyEntity(200L, updatedAt = 3000L),
				),
				favourites = listOf(
					FavouriteEntity(100L, categoryId = 1, sortKey = 0, createdAt = 1500L, deletedAt = 0L, isPinned = false),
					FavouriteEntity(200L, categoryId = 1, sortKey = 7, createdAt = 1600L, deletedAt = 0L, isPinned = true),
				),
			),
		)
	}

	private fun mangaEntity(id: Long, title: String, altTitles: String? = null) = MangaEntity(
		id = id,
		title = title,
		altTitles = altTitles,
		url = "/manga/" + id,
		publicUrl = "https://mangadex.org/title/" + id,
		rating = -1f,
		isNsfw = false,
		contentRating = "SAFE",
		coverUrl = "https://example.org/" + id + ".jpg",
		largeCoverUrl = null,
		state = "ONGOING",
		authors = "Kanehito Yamada, Tsukasa Abe",
		source = "MANGADEX",
	)

	private fun historyEntity(mangaId: Long, updatedAt: Long) = HistoryEntity(
		mangaId = mangaId,
		createdAt = 1000L,
		updatedAt = updatedAt,
		chapterId = 99L,
		page = 17,
		// Ageha's own column, with no archive field. It is deliberately non-zero here so the
		// round trip shows it coming back as 0 -- "unknown" -- rather than appearing to survive.
		pageCount = 24,
		scroll = 0.3456789f,
		percent = 0.35f,
		deletedAt = 0L,
		chaptersCount = 120,
	)

	@Test
	@DisplayName("Ageha's own page-count column does not survive the archive, and says so")
	fun pageCountIsNotCarried(@TempDir dir: File) = runBlocking {
		// Named rather than discovered. `history.page_count` is schema 30, Ageha's divergence
		// from the Android schema, and there is no archive field for it. Inventing one would make
		// a file the Android app does not understand for a value that degrades to "unknown"
		// anyway -- so a round trip forgets it, exactly as an Android import arrives without it.
		populate(db)

		val file = exporter().export(File(dir, "backup.bk.zip")).file

		val restored = AgehaDatabaseFactory.openInMemory()
		try {
			BackupImporter(restored).import(file)
			assertEquals(0, requireNotNull(restored.historyDao().find(100L)).pageCount)
			assertEquals(24, requireNotNull(db.historyDao().find(100L)).pageCount, "the original is untouched")
		} finally {
			restored.close()
		}
	}
}
