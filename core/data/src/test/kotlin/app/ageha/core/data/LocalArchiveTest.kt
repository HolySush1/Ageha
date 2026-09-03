package app.ageha.core.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reading CBZ archives.
 *
 * Page *ordering* is what most of this tests, because getting it wrong produces a chapter that
 * reads in a plausible but wrong sequence -- which a user will blame on the scanlation rather
 * than on the reader.
 */
class LocalArchiveTest {

	private fun cbz(dir: Path, name: String, entries: List<String>): File {
		val file = File(dir.toFile(), name)
		ZipOutputStream(file.outputStream()).use { zip ->
			for (entry in entries) {
				zip.putNextEntry(ZipEntry(entry))
				zip.write(entry.toByteArray())
				zip.closeEntry()
			}
		}
		return file
	}

	@Test
	fun `pages come back in natural order, not lexicographic`(@TempDir dir: Path) {
		// Deliberately written to the zip out of order, and named so that a lexicographic sort
		// gets it wrong: "page10" sorts before "page2".
		val file = cbz(dir, "chapter.cbz", listOf("page10.jpg", "page2.jpg", "page1.jpg"))
		val names = LocalArchive.pages(file).map { LocalArchive.parse(it.url)!!.second }
		assertEquals(listOf("page1.jpg", "page2.jpg", "page10.jpg"), names)
	}

	@Test
	fun `zero padded and unpadded numbering sort together`() {
		val sorted = listOf("p7.jpg", "p007.jpg", "p10.jpg", "p2.jpg").sortedWith(LocalArchive.naturalOrder)
		// 007 and 7 are the same number, so their relative order is arbitrary -- what matters is
		// that both land between 2 and 10 rather than at the start.
		assertEquals("p2.jpg", sorted.first())
		assertEquals("p10.jpg", sorted.last())
	}

	@Test
	fun `natural order handles names with no digits`() {
		val sorted = listOf("cover.jpg", "back.jpg", "page1.jpg").sortedWith(LocalArchive.naturalOrder)
		assertEquals(listOf("back.jpg", "cover.jpg", "page1.jpg"), sorted)
	}

	@Test
	fun `non-image entries are ignored`(@TempDir dir: Path) {
		val file = cbz(
			dir,
			"chapter.cbz",
			listOf("ComicInfo.xml", "page1.jpg", "readme.txt", "page2.png", "Thumbs.db"),
		)
		val names = LocalArchive.pages(file).map { LocalArchive.parse(it.url)!!.second }
		assertEquals(listOf("page1.jpg", "page2.png"), names)
	}

	@Test
	fun `page urls round trip`(@TempDir dir: Path) {
		val file = cbz(dir, "chapter.cbz", listOf("sub folder/page 1.jpg"))
		val page = LocalArchive.pages(file).single()
		// JUnit 5's assertNotNull returns Unit, so it cannot narrow the type -- requireNotNull does.
		val (archive, entry) = requireNotNull(LocalArchive.parse(page.url))
		assertEquals(file.absolutePath, archive.absolutePath)
		assertEquals("sub folder/page 1.jpg", entry)
	}

	@Test
	fun `entry bytes can be read back without extracting`(@TempDir dir: Path) {
		val file = cbz(dir, "chapter.cbz", listOf("page1.jpg"))
		assertEquals("page1.jpg", LocalArchive.readEntry(file, "page1.jpg")?.decodeToString())
		assertNull(LocalArchive.readEntry(file, "does-not-exist.jpg"))
	}

	/**
	 * CBR is the gap worth being explicit about. A user with a RAR-based archive gets told what
	 * is wrong and what to do, rather than "could not open archive".
	 */
	@Test
	fun `cbr is refused with a reason a user can act on`(@TempDir dir: Path) {
		val rar = File(dir.toFile(), "chapter.cbr")
		rar.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21))
		assertFalse(LocalArchive.isSupported(rar))
		val reason = LocalArchive.unsupportedReason(rar)
		assertNotNull(reason)
		assertTrue(reason!!.contains("RAR"), "the reason must name the actual format: $reason")
		assertTrue(reason.contains("CBZ"), "and say what to do about it: $reason")
	}

	@Test
	fun `an unknown extension still gets a reason`(@TempDir dir: Path) {
		val odd = File(dir.toFile(), "chapter.xyz")
		assertNotNull(LocalArchive.unsupportedReason(odd))
	}

	@Test
	fun `zip and cbz are both accepted`(@TempDir dir: Path) {
		assertTrue(LocalArchive.isSupported(File(dir.toFile(), "a.cbz")))
		assertTrue(LocalArchive.isSupported(File(dir.toFile(), "a.zip")))
		assertTrue(LocalArchive.isSupported(File(dir.toFile(), "A.CBZ")), "extensions are case-insensitive")
	}

	@Test
	fun `an archive maps to a chapter with a stable id`(@TempDir dir: Path) {
		val file = cbz(dir, "Volume 3.cbz", listOf("page1.jpg"))
		val first = LocalArchive.chapterFor(file)
		val second = LocalArchive.chapterFor(file)
		assertEquals(first.id, second.id, "the id must not change between runs; it is a foreign key")
		assertEquals("Volume 3", first.title)
		assertTrue(first.id >= 0)
	}

	@Test
	fun `an archive with no images yields no pages`(@TempDir dir: Path) {
		val file = cbz(dir, "empty.cbz", listOf("ComicInfo.xml"))
		assertTrue(LocalArchive.pages(file).isEmpty())
	}
}
