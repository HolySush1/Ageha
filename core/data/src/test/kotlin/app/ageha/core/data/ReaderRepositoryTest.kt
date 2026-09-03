package app.ageha.core.data

import app.ageha.core.database.AgehaDatabase
import app.ageha.core.database.AgehaDatabaseFactory
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaTag
import app.ageha.core.model.ReaderMode
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.source.MangaSourceRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reading position, reader settings, and local archives.
 *
 * The first test here is a regression. Recording a position wrote only the history row, and
 * `history.manga_id` is an enforced foreign key -- so reading anything that had not been
 * *favourited* failed with a constraint violation and silently recorded nothing. Favouriting was
 * the only code path that happened to insert the manga row. It was found by rendering the reader
 * against a real CBZ, which is the case with no library entry behind it at all.
 */
class ReaderRepositoryTest {

	private lateinit var db: AgehaDatabase
	private lateinit var reader: ReaderRepository

	private object EmptyRegistry : MangaSourceRegistry {
		override fun availableSources() = emptyList<app.ageha.core.model.SourceDescriptor>()
		override fun descriptorFor(name: String) = null
		override fun clientFor(name: String): MangaSourceClient =
			throw app.ageha.core.model.SourceFailure.UnknownSource(name)
		override val parsersVersion = "test"
	}

	@BeforeEach
	fun open() {
		db = AgehaDatabaseFactory.openInMemory()
		reader = ReaderRepository(
			catalog = CatalogRepository(EmptyRegistry),
			manga = db.mangaDao(),
			history = db.historyDao(),
			prefs = db.mangaPrefsDao(),
		)
	}

	@AfterEach
	fun close() = db.close()

	private fun manga(id: Long = 1) = AgehaManga(
		id = id, title = "Berserk", altTitles = emptySet(), url = "/m/$id",
		publicUrl = "https://test/m/$id", rating = null, contentRating = null,
		coverUrl = null, largeCoverUrl = null,
		tags = setOf(AgehaTag("Seinen", "seinen", "TEST")),
		state = null, authors = emptySet(), description = null,
		chapters = null, sourceName = "TEST",
	)

	private fun chapter(id: Long = 7) = AgehaChapter(
		id = id, title = "Ch $id", number = id.toFloat(), volume = null, url = "/c/$id",
		scanlator = null, uploadDate = null, branch = null, sourceName = "TEST",
	)

	/**
	 * The regression. Nothing has favourited this manga, so before the fix the foreign key
	 * rejected the history row and the position was lost.
	 */
	@Test
	fun `a position can be saved for a manga that is not in the library`() = runTest {
		reader.savePosition(manga(), chapter(), page = 4, scroll = 0.25f, percent = 0.5f)

		// requireNotNull, not assertNotNull: JUnit 5 returns Unit and cannot narrow the type.
		val position = requireNotNull(reader.positionFor(1))
		assertEquals(7L, position.chapterId)
		assertEquals(4, position.page)
		// The scroll fraction is what makes a webtoon position exact, and it must survive intact.
		assertEquals(0.25f, position.scroll)
		assertEquals(0.5f, position.percent)
	}

	@Test
	fun `saving a position also records the manga itself`() = runTest {
		assertNull(db.mangaDao().find(1))
		reader.savePosition(manga(), chapter(), page = 0, scroll = 0f, percent = 0f)
		assertNotNull(db.mangaDao().find(1))
		// Tags come with it, so the manga is complete rather than a bare row satisfying a key.
		assertEquals(1, db.mangaDao().tagsOf(1).size)
	}

	/**
	 * `created_at` is when the manga was first opened. Overwriting it on every page turn would
	 * make "reading since" meaningless and confuse a sync server that treats it as an identity.
	 */
	@Test
	fun `the first-opened timestamp survives later page turns`() = runTest {
		reader.savePosition(manga(), chapter(), 0, 0f, 0f, now = 1_000L)
		reader.savePosition(manga(), chapter(), 5, 0f, 0.2f, now = 9_000L)

		val row = requireNotNull(db.historyDao().find(1))
		assertEquals(1_000L, row.createdAt)
		assertEquals(9_000L, row.updatedAt)
	}

	@Test
	fun `reading something again clears its tombstone`() = runTest {
		reader.savePosition(manga(), chapter(), 0, 0f, 0f, now = 1_000L)
		db.historyDao().markDeleted(1, now = 2_000L)
		assertNull(reader.positionFor(1), "a deleted entry must not be reported")

		reader.savePosition(manga(), chapter(), 1, 0f, 0.1f, now = 3_000L)
		assertNotNull(reader.positionFor(1), "resuming a removed manga must un-delete it")
	}

	@Test
	fun `reader mode defaults to right to left and round trips by id`() = runTest {
		assertEquals(ReaderMode.REVERSED, reader.observeMode(1).first())
		db.mangaDao().upsert(MangaMapping.toEntity(manga()))
		reader.setMode(1, ReaderMode.WEBTOON)
		assertEquals(ReaderMode.WEBTOON, reader.observeMode(1).first())
		// Stored as the Android app's id, not an ordinal. WEBTOON is 2 and is declared second, so
		// this would pass by luck on an ordinal -- REVERSED is the one that catches it.
		assertEquals(2, db.mangaPrefsDao().find(1)?.mode)
		reader.setMode(1, ReaderMode.REVERSED)
		assertEquals(3, db.mangaPrefsDao().find(1)?.mode)
	}

	/**
	 * The row carries eight colour-filter columns Ageha does not use but the Android app does.
	 * Writing a fresh row would reset a user's settings on their next backup round trip.
	 */
	@Test
	fun `changing the mode does not clobber the other columns`() = runTest {
		db.mangaDao().upsert(MangaMapping.toEntity(manga()))
		db.mangaPrefsDao().upsert(
			app.ageha.core.database.entity.MangaPrefsEntity(
				mangaId = 1, mode = 1, cfBrightness = 0.4f, cfContrast = 0.7f,
				cfInvert = true, cfGrayscale = false, cfBookEffect = true,
				titleOverride = "My name for it", coverUrlOverride = null, contentRatingOverride = null,
			),
		)
		reader.setMode(1, ReaderMode.WEBTOON)

		val row = requireNotNull(db.mangaPrefsDao().find(1))
		assertEquals(2, row.mode)
		assertEquals(0.4f, row.cfBrightness)
		assertTrue(row.cfInvert)
		assertEquals("My name for it", row.titleOverride)
	}

	// ------------------------------------------------------------------ local archives

	private fun cbz(dir: Path, entries: Int): File {
		val file = File(dir.toFile(), "Volume 1.cbz")
		ZipOutputStream(file.outputStream()).use { zip ->
			repeat(entries) { index ->
				zip.putNextEntry(ZipEntry("%03d.png".format(index + 1)))
				zip.write(byteArrayOf(1, 2, 3))
				zip.closeEntry()
			}
		}
		return file
	}

	@Test
	fun `a local archive reads without any source behind it`(@TempDir dir: Path) = runTest {
		val file = cbz(dir, entries = 5)
		val (manga, chapter) = reader.localManga(file)
		assertEquals(LocalArchive.LOCAL_SOURCE, manga.sourceName)

		val pages = reader.pages(chapter)
		assertTrue(pages is CatalogResult.Success) { "got $pages" }
		assertEquals(5, (pages as CatalogResult.Success).value.size)

		// Archive pages are already addressable, so resolving one makes no request.
		val url = reader.pageUrl(pages.value.first())
		assertTrue(url is CatalogResult.Success)
		assertTrue((url as CatalogResult.Success).value.startsWith("cbz://"))
	}

	@Test
	fun `a local archive keeps its reading position across opens`(@TempDir dir: Path) = runTest {
		val file = cbz(dir, entries = 5)
		val (manga, chapter) = reader.localManga(file)
		reader.savePosition(manga, chapter, page = 3, scroll = 0f, percent = 0.6f)

		// Reopened from scratch: the id comes from the path, so it is the same manga.
		val (reopened, _) = reader.localManga(file)
		assertEquals(manga.id, reopened.id)
		assertEquals(3, reader.positionFor(reopened.id)?.page)
	}

	@Test
	fun `a missing archive fails rather than throwing`(@TempDir dir: Path) = runTest {
		val (_, chapter) = reader.localManga(File(dir.toFile(), "gone.cbz"))
		assertTrue(reader.pages(chapter) is CatalogResult.Failure)
	}

	/** A `.cbr` must be refused with the reason, not with a generic failure. */
	@Test
	fun `a cbr is refused with an explanation`(@TempDir dir: Path) = runTest {
		val rar = File(dir.toFile(), "chapter.cbr").apply { writeBytes(byteArrayOf(0x52, 0x61, 0x72)) }
		val (_, chapter) = reader.localManga(rar)
		val result = reader.pages(chapter)
		assertTrue(result is CatalogResult.Failure) { "got $result" }
		val failure = (result as CatalogResult.Failure).failure
		assertTrue(failure is app.ageha.core.model.SourceFailure.ContentUnavailable)
		assertTrue(
			(failure as app.ageha.core.model.SourceFailure.ContentUnavailable).reason?.contains("RAR") == true,
		) { "the reason must name the format: ${failure.reason}" }
	}

	@Test
	fun `progress is chapter-weighted`() {
		assertEquals(0f, reader.progressOf(0, 10, 0, 20))
		assertEquals(0.5f, reader.progressOf(5, 10, 0, 20))
		// Halfway through chapter 5 of 10 is halfway between 0.5 and 0.6.
		assertEquals(0.55f, reader.progressOf(5, 10, 10, 21), 0.01f)
		assertEquals(0f, reader.progressOf(0, 0, 0, 0), "no chapters means no progress, not a crash")
	}
}
