package app.ageha.core.image

import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.ErrorResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Loading a page out of a CBZ, through the real image loader.
 *
 * The reader draws local archives and remote sources through the same `AsyncImage`, which is only
 * true if the archive fetcher is genuinely wired into the loader Ageha builds. That is what this
 * checks -- not the fetcher in isolation, but the loader as the application constructs it.
 */
class ArchiveFetcherTest {

	private fun cbz(dir: Path): File {
		val file = File(dir.toFile(), "chapter.cbz")
		ZipOutputStream(file.outputStream()).use { zip ->
			val page = BufferedImage(120, 180, BufferedImage.TYPE_INT_RGB)
			val g = page.createGraphics()
			g.color = Color(0x40, 0x80, 0xC0)
			g.fillRect(0, 0, 120, 180)
			g.dispose()
			zip.putNextEntry(ZipEntry("001.png"))
			ImageIO.write(page, "png", zip)
			zip.closeEntry()
		}
		return file
	}

	@Test
	fun `a page loads out of an archive through the application's loader`(@TempDir dir: Path) = runTest {
		val file = cbz(dir)
		val loader = AgehaImages.create(OkHttpClient(), File(dir.toFile(), "cache"))
		val url = app.ageha.core.model.ArchiveUrl.of(file, "001.png")

		val result = loader.execute(ImageRequest.Builder(coil3.PlatformContext.INSTANCE).data(url).build())

		assertTrue(result is SuccessResult) {
			"expected the archive fetcher to serve $url, got ${(result as? ErrorResult)?.throwable}"
		}
		val image = (result as SuccessResult).image
		assertTrue(image.width == 120 && image.height == 180) {
			"decoded ${image.width}x${image.height}, expected the 120x180 page"
		}
	}

	@Test
	fun `a missing entry fails rather than hanging`(@TempDir dir: Path) = runTest {
		val file = cbz(dir)
		val loader = AgehaImages.create(OkHttpClient(), File(dir.toFile(), "cache"))
		val url = app.ageha.core.model.ArchiveUrl.of(file, "does-not-exist.png")
		assertTrue(loader.execute(ImageRequest.Builder(coil3.PlatformContext.INSTANCE).data(url).build()) is ErrorResult)
	}
}
