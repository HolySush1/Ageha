package app.ageha.brandkit

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Writers for the two container formats desktop platforms insist on.
 *
 * Both are written by hand. `.ico` and `.icns` are simple enough that a library would be more
 * dependency than help, and the alternative -- shelling out to ImageMagick or `iconutil` -- would
 * make the icons buildable only on a machine that happens to have those installed, which for
 * `iconutil` means only on macOS. Hand-writing them keeps the whole pipeline reproducible from
 * a bare JDK on any of the three target platforms.
 */
object IconContainers {

	private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also {
		ImageIO.write(image, "png", it)
	}.toByteArray()

	/**
	 * Windows `.ico`.
	 *
	 * Sizes up to 64 are written as uncompressed 32-bit DIBs and the larger ones as PNG. Windows
	 * has accepted PNG entries since Vista, but a few things that read icons -- installer
	 * tooling, older shell extensions, some file managers -- still only understand the DIB form,
	 * and the small sizes are exactly the ones those tools ask for. The large entries are the
	 * ones where PNG actually saves meaningful space, so the split costs nothing.
	 */
	fun writeIco(images: List<BufferedImage>, target: File) {
		val entries = images.sortedBy { it.width }.map { image ->
			image to if (image.width <= 64) dib(image) else png(image)
		}
		val out = ByteArrayOutputStream()
		val data = DataOutputStream(out)
		data.writeShortLE(0) // reserved
		data.writeShortLE(1) // type: icon
		data.writeShortLE(entries.size)
		var offset = 6 + 16 * entries.size
		for ((image, bytes) in entries) {
			data.write(if (image.width >= 256) 0 else image.width)
			data.write(if (image.height >= 256) 0 else image.height)
			data.write(0) // palette size: none
			data.write(0) // reserved
			data.writeShortLE(1) // colour planes
			data.writeShortLE(32) // bits per pixel
			data.writeIntLE(bytes.size)
			data.writeIntLE(offset)
			offset += bytes.size
		}
		for ((_, bytes) in entries) data.write(bytes)
		data.flush()
		target.writeBytes(out.toByteArray())
	}

	/**
	 * A 32-bit bottom-up DIB with the doubled height an ICO entry expects.
	 *
	 * The trailing 1-bit AND mask is vestigial for 32-bit icons -- the alpha channel already
	 * carries transparency -- but it is not optional: readers compute the mask's offset from the
	 * declared height, so omitting it corrupts every entry after this one.
	 */
	private fun dib(image: BufferedImage): ByteArray {
		val w = image.width
		val h = image.height
		val out = ByteArrayOutputStream()
		val data = DataOutputStream(out)
		data.writeIntLE(40) // BITMAPINFOHEADER size
		data.writeIntLE(w)
		data.writeIntLE(h * 2) // XOR image plus AND mask
		data.writeShortLE(1)
		data.writeShortLE(32)
		data.writeIntLE(0) // BI_RGB
		data.writeIntLE(w * h * 4)
		repeat(4) { data.writeIntLE(0) } // resolution and palette fields, all unused
		for (y in h - 1 downTo 0) {
			for (x in 0 until w) {
				val argb = image.getRGB(x, y)
				data.write(argb and 0xFF) // blue
				data.write((argb shr 8) and 0xFF)
				data.write((argb shr 16) and 0xFF)
				data.write((argb ushr 24) and 0xFF)
			}
		}
		val maskRowBytes = ((w + 31) / 32) * 4
		repeat(h * maskRowBytes) { data.write(0) }
		data.flush()
		return out.toByteArray()
	}

	/**
	 * The `.icns` chunk types, each pinned to the pixel size it must contain.
	 *
	 * The retina types are not duplicates of the plain ones: `ic11` is "32 pixels for a 16 point
	 * slot", so macOS picks it when the same icon is drawn at 16pt on a 2x display. Shipping the
	 * plain types alone gets the icon upscaled and soft on every Mac made in the last decade.
	 */
	private val ICNS_TYPES = listOf(
		"icp4" to 16,
		"icp5" to 32,
		"ic11" to 32, // 16pt @2x
		"ic12" to 64, // 32pt @2x
		"ic07" to 128,
		"ic13" to 256, // 128pt @2x
		"ic08" to 256,
		"ic14" to 512, // 256pt @2x
		"ic09" to 512,
		"ic10" to 1024, // 512pt @2x
	)

	/** macOS `.icns`, built from a size-indexed set of already-rendered images. */
	fun writeIcns(bySize: Map<Int, BufferedImage>, target: File) {
		val chunks = ICNS_TYPES.mapNotNull { (type, size) ->
			bySize[size]?.let { type to png(it) }
		}
		val body = ByteArrayOutputStream()
		val data = DataOutputStream(body)
		for ((type, bytes) in chunks) {
			data.write(type.toByteArray(Charsets.US_ASCII))
			data.writeInt(bytes.size + 8) // big-endian, and inclusive of this header
			data.write(bytes)
		}
		data.flush()
		val payload = body.toByteArray()
		val out = ByteArrayOutputStream()
		val file = DataOutputStream(out)
		file.write("icns".toByteArray(Charsets.US_ASCII))
		file.writeInt(payload.size + 8)
		file.write(payload)
		file.flush()
		target.writeBytes(out.toByteArray())
	}

	private fun DataOutputStream.writeShortLE(value: Int) {
		write(value and 0xFF)
		write((value shr 8) and 0xFF)
	}

	private fun DataOutputStream.writeIntLE(value: Int) {
		write(value and 0xFF)
		write((value shr 8) and 0xFF)
		write((value shr 16) and 0xFF)
		write((value ushr 24) and 0xFF)
	}
}
