package app.ageha.brandkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * The hand-written `.ico` and `.icns` writers, parsed back byte by byte.
 *
 * These formats fail quietly. A wrong offset or a missing AND mask produces a file that opens
 * without complaint in an image viewer and then shows the wrong icon, or none, once Windows or
 * macOS gets hold of it -- by which point it is inside an installer. Reading the bytes back is
 * the only check that runs on every build rather than on every release.
 */
class IconContainersTest {

	private val pngSignature = byteArrayOf(
		0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
	)

	private fun swatch(size: Int, argb: Int = 0xFFB93723.toInt()) =
		BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
			for (y in 0 until size) for (x in 0 until size) setRGB(x, y, argb)
		}

	@Test
	fun `ico declares every entry at the right size and offset`(@TempDir dir: Path) {
		val sizes = listOf(16, 32, 48, 256)
		val target = File(dir.toFile(), "test.ico")
		IconContainers.writeIco(sizes.map { swatch(it) }, target)

		val bytes = target.readBytes()
		val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		assertEquals(0, buffer.short.toInt(), "reserved field")
		assertEquals(1, buffer.short.toInt(), "type must be 1 for an icon")
		assertEquals(sizes.size, buffer.short.toInt(), "entry count")

		for (expected in sizes) {
			val width = buffer.get().toInt() and 0xFF
			buffer.get() // height, same value
			buffer.get() // palette size
			buffer.get() // reserved
			assertEquals(1, buffer.short.toInt(), "colour planes")
			assertEquals(32, buffer.short.toInt(), "bit depth")
			val length = buffer.int
			val offset = buffer.int
			// 256 is encoded as 0 in a single byte. This is the classic trap in the format, and
			// getting it wrong yields an icon Windows silently declines to draw at large sizes.
			assertEquals(if (expected >= 256) 0 else expected, width, "declared width for $expected")
			assertTrue(offset + length <= bytes.size) {
				"entry for $expected points past the end of the file"
			}
			assertTrue(length > 0)
		}
	}

	@Test
	fun `small ico entries are DIBs and large ones are PNGs`(@TempDir dir: Path) {
		val target = File(dir.toFile(), "mixed.ico")
		IconContainers.writeIco(listOf(swatch(32), swatch(256)), target)
		val bytes = target.readBytes()

		fun entry(index: Int): Pair<Int, Int> {
			val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
			b.position(6 + index * 16 + 8)
			return b.int to b.int
		}
		val (smallLength, smallOffset) = entry(0)
		val (_, largeOffset) = entry(1)

		// A DIB begins with the 40-byte BITMAPINFOHEADER size field.
		assertEquals(
			40,
			ByteBuffer.wrap(bytes, smallOffset, 4).order(ByteOrder.LITTLE_ENDIAN).int,
			"the 32px entry should be an uncompressed DIB",
		)
		assertTrue(bytes.copyOfRange(largeOffset, largeOffset + 8).contentEquals(pngSignature)) {
			"the 256px entry should be PNG-compressed"
		}
		// The DIB carries a doubled height and a trailing AND mask, so it must be larger than its
		// pixels alone. Omitting the mask corrupts the offsets of every entry after it.
		assertTrue(smallLength > 40 + 32 * 32 * 4) { "the AND mask is missing from the 32px DIB" }
	}

	@Test
	fun `icns chunks are well formed and cover the retina types`(@TempDir dir: Path) {
		val sizes = listOf(16, 32, 64, 128, 256, 512, 1024)
		val target = File(dir.toFile(), "test.icns")
		IconContainers.writeIcns(sizes.associateWith { swatch(it) }, target)

		val bytes = target.readBytes()
		assertEquals("icns", String(bytes, 0, 4, Charsets.US_ASCII))
		val declared = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.BIG_ENDIAN).int
		assertEquals(bytes.size, declared, "the header length must cover the whole file")

		val types = mutableListOf<String>()
		var offset = 8
		while (offset < bytes.size) {
			val type = String(bytes, offset, 4, Charsets.US_ASCII)
			val length = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.BIG_ENDIAN).int
			assertTrue(length in 9..(bytes.size - offset)) { "chunk $type declares a bad length" }
			assertTrue(
				bytes.copyOfRange(offset + 8, offset + 16).contentEquals(pngSignature),
				"chunk $type should carry a PNG payload",
			)
			types += type
			offset += length
		}
		assertEquals(bytes.size, offset, "chunk lengths must tile the file exactly")
		// Without the @2x types macOS upscales the plain ones on every Retina display, which is
		// every Mac sold in the last decade.
		assertTrue(types.containsAll(listOf("ic11", "ic12", "ic13", "ic14"))) {
			"missing retina chunk types, found $types"
		}
		assertTrue(types.contains("ic10")) { "missing the 1024px chunk" }
	}
}
