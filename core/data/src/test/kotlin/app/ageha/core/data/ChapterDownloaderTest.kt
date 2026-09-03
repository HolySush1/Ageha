package app.ageha.core.data

import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaContentType
import app.ageha.core.model.AgehaFilter
import app.ageha.core.model.AgehaFilterCapabilities
import app.ageha.core.model.AgehaFilterOptions
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaPage
import app.ageha.core.model.AgehaSortOrder
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.model.SourceFailure
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.source.MangaSourceRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Downloading a chapter to CBZ.
 *
 * The assertions worth having are about the *failure* shapes: an interrupted download must not
 * leave something that looks finished, a missing page must not lose the chapter, and a title
 * containing a colon must not produce a path Windows refuses.
 */
class ChapterDownloaderTest {

	private val descriptor = SourceDescriptor("TEST", "Test", "en", AgehaContentType.MANGA, false)

	private fun manga(title: String = "Berserk") = AgehaManga(
		id = 1, title = title, altTitles = emptySet(), url = "/m/1",
		publicUrl = "https://test/m/1", rating = null, contentRating = null,
		coverUrl = null, largeCoverUrl = null, tags = emptySet(), state = null,
		authors = emptySet(), description = null, chapters = null, sourceName = "TEST",
	)

	private fun chapter(number: Float? = 3f, title: String? = "The Golden Age") = AgehaChapter(
		id = 7, title = title, number = number, volume = null, url = "/c/7",
		scanlator = null, uploadDate = null, branch = null, sourceName = "TEST",
	)

	private inner class FakeClient(
		private val pageCount: Int,
		private val pagesFail: Boolean = false,
		private val unresolvable: Set<Int> = emptySet(),
	) : MangaSourceClient {
		override val descriptor = this@ChapterDownloaderTest.descriptor
		override val domain = "test.invalid"
		override val availableSortOrders = setOf(AgehaSortOrder.UPDATED)
		override val filterCapabilities =
			AgehaFilterCapabilities(true, false, false, false, false, false)

		override suspend fun list(offset: Int, order: AgehaSortOrder, filter: AgehaFilter) = emptyList<AgehaManga>()
		override suspend fun details(manga: AgehaManga) = manga
		override suspend fun pages(chapter: AgehaChapter): List<AgehaPage> {
			if (pagesFail) throw SourceFailure.Network("TEST", java.io.IOException("down"))
			return (0 until pageCount).map {
				AgehaPage(id = it.toLong(), url = "https://test/p/$it.png", preview = null, sourceName = "TEST")
			}
		}

		override suspend fun pageUrl(page: AgehaPage): String {
			if (page.id.toInt() in unresolvable) throw SourceFailure.NotFound("TEST")
			return page.url
		}

		override suspend fun filterOptions() =
			AgehaFilterOptions(emptySet(), emptySet(), emptySet(), emptySet())
		override suspend fun relatedManga(seed: AgehaManga) = emptyList<AgehaManga>()
		override fun imageRequestHeaders() = mapOf("Referer" to "https://test.invalid/")
	}

	private inner class FakeRegistry(private val client: MangaSourceClient) : MangaSourceRegistry {
		override fun availableSources() = listOf(descriptor)
		override fun descriptorFor(name: String) = descriptor
		override fun clientFor(name: String) = client
		override val parsersVersion = "test"
	}

	private fun downloader(
		dir: Path,
		client: MangaSourceClient,
		fetch: suspend (String, Map<String, String>) -> InputStream? = { url, _ ->
			ByteArrayInputStream(url.toByteArray())
		},
	) = ChapterDownloader(CatalogRepository(FakeRegistry(client)), dir.toFile(), fetch)

	@Test
	fun `a chapter is written as a cbz with ordered entries`(@TempDir dir: Path) = runTest {
		val result = downloader(dir, FakeClient(pageCount = 12)).download(manga(), chapter())
		assertTrue(result is DownloadResult.Complete) { "got $result" }
		val file = (result as DownloadResult.Complete).file
		assertTrue(file.isFile)
		assertEquals(12, result.pageCount)

		val names = ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
		// Zero-padded, so plain lexicographic order is also reading order -- for any reader, not
		// just Ageha's own natural sort.
		assertEquals(names, names.sorted(), "entries must sort lexicographically into reading order")
		assertEquals("001.png", names.first())
		assertEquals("012.png", names.last())
	}

	@Test
	fun `downloading twice does not repeat the work`(@TempDir dir: Path) = runTest {
		val subject = downloader(dir, FakeClient(pageCount = 3))
		subject.download(manga(), chapter())
		val second = subject.download(manga(), chapter())
		assertTrue(second is DownloadResult.AlreadyDownloaded) { "got $second" }
	}

	/**
	 * The property everything else depends on: if a `.cbz` exists, it is complete. A failed
	 * download must leave nothing, or "already downloaded" becomes a lie.
	 */
	@Test
	fun `a failed download leaves no file behind`(@TempDir dir: Path) = runTest {
		val subject = downloader(dir, FakeClient(pageCount = 5)) { _, _ -> null }
		val result = subject.download(manga(), chapter())
		assertTrue(result is DownloadResult.Failed) { "got $result" }
		assertFalse(subject.isDownloaded(manga(), chapter()))
		// Not even the temporary.
		assertTrue(dir.toFile().walkTopDown().none { it.extension == "part" })
	}

	@Test
	fun `a source that cannot list pages fails cleanly`(@TempDir dir: Path) = runTest {
		val result = downloader(dir, FakeClient(pageCount = 0, pagesFail = true))
			.download(manga(), chapter())
		assertTrue(result is DownloadResult.Failed) { "got $result" }
		assertTrue((result as DownloadResult.Failed).failure is SourceFailure.Network)
	}

	/** A chapter missing two of forty pages is still worth having offline. */
	@Test
	fun `pages that cannot be resolved are reported, not fatal`(@TempDir dir: Path) = runTest {
		val client = FakeClient(pageCount = 6, unresolvable = setOf(2, 4))
		val result = downloader(dir, client).download(manga(), chapter())
		assertTrue(result is DownloadResult.Partial) { "got $result" }
		result as DownloadResult.Partial
		assertEquals(4, result.pageCount)
		assertEquals(listOf(3, 5), result.missingPages, "missing pages are reported 1-based")
		assertTrue(result.file.isFile)
	}

	/**
	 * Manga titles routinely contain characters Windows rejects. A library that downloads on
	 * Linux and fails on Windows is a bug that only half the users ever see.
	 */
	@Test
	fun `titles with illegal characters produce a usable path`(@TempDir dir: Path) = runTest {
		val awkward = manga(title = """Re:Zero - Starting Life? <in> Another/World""")
		val subject = downloader(dir, FakeClient(pageCount = 2))
		val result = subject.download(awkward, chapter())
		assertTrue(result is DownloadResult.Complete) { "got $result" }
		val path = (result as DownloadResult.Complete).file.absolutePath
		for (illegal in listOf(':', '?', '<', '>', '|', '*', '"')) {
			assertFalse(path.substringAfter(dir.toFile().absolutePath).contains(illegal)) {
				"'$illegal' survived into $path"
			}
		}
	}

	@Test
	fun `a reserved windows name is escaped`(@TempDir dir: Path) = runTest {
		val subject = downloader(dir, FakeClient(pageCount = 1))
		val result = subject.download(manga(title = "CON"), chapter())
		assertTrue(result is DownloadResult.Complete) { "got $result" }
		assertTrue((result as DownloadResult.Complete).file.parentFile.name == "_CON")
	}

	@Test
	fun `chapter files sort into reading order on disk`(@TempDir dir: Path) = runTest {
		val subject = downloader(dir, FakeClient(pageCount = 1))
		for (number in listOf(1f, 2f, 10f, 11f)) {
			subject.download(manga(), chapter(number = number, title = "Ch $number"))
		}
		val names = subject.downloadedChapters(manga()).map { it.name }
		assertEquals(4, names.size)
		// Zero-padded chapter numbers, so 2 precedes 10 whichever way a tool sorts them.
		assertTrue(names.first().startsWith("0001")) { "got $names" }
		assertTrue(names.last().startsWith("0011")) { "got $names" }
	}

	@Test
	fun `an untitled unnumbered chapter still gets a name`(@TempDir dir: Path) = runTest {
		val result = downloader(dir, FakeClient(pageCount = 1))
			.download(manga(), chapter(number = null, title = null))
		assertTrue(result is DownloadResult.Complete) { "got $result" }
		assertTrue((result as DownloadResult.Complete).file.name.isNotBlank())
	}

	@Test
	fun `progress is reported from zero to complete`(@TempDir dir: Path) = runTest {
		val seen = mutableListOf<DownloadProgress>()
		downloader(dir, FakeClient(pageCount = 4)).download(manga(), chapter()) { seen += it }
		assertTrue(seen.isNotEmpty())
		assertEquals(0, seen.first().completed)
		assertEquals(1f, seen.last().fraction)
	}

	@Test
	fun `size on disk counts what was written`(@TempDir dir: Path) = runTest {
		val subject = downloader(dir, FakeClient(pageCount = 3))
		assertEquals(0L, subject.sizeOnDisk())
		subject.download(manga(), chapter())
		assertTrue(subject.sizeOnDisk() > 0)
	}

	@Test
	fun `a deleted chapter is no longer reported as downloaded`(@TempDir dir: Path) = runTest {
		val subject = downloader(dir, FakeClient(pageCount = 2))
		subject.download(manga(), chapter())
		assertTrue(subject.isDownloaded(manga(), chapter()))
		assertTrue(subject.delete(manga(), chapter()))
		assertFalse(subject.isDownloaded(manga(), chapter()))
	}

	/** The written file must be readable by the same code that reads any other CBZ. */
	@Test
	fun `a downloaded chapter reads back through LocalArchive`(@TempDir dir: Path) = runTest {
		val result = downloader(dir, FakeClient(pageCount = 5)).download(manga(), chapter())
		val file = (result as DownloadResult.Complete).file
		assertTrue(LocalArchive.isSupported(file))
		assertEquals(5, LocalArchive.pages(file).size)
	}
}
