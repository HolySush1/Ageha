package app.ageha.core.jvmcontext

import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * The parsers library's [Bitmap], backed by AWT.
 *
 * Used for image descrambling: a handful of sources serve pages as shuffled tiles and hand the
 * parser a redraw function to put them back in order. The interface is deliberately tiny -- two
 * dimensions and one blit -- so there is nothing Android-specific to work around here.
 */
internal class AwtBitmap(
	val image: BufferedImage,
) : Bitmap {

	override val width: Int get() = image.width

	override val height: Int get() = image.height

	override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
		val source = (sourceBitmap as AwtBitmap).image
		val graphics = image.createGraphics()
		try {
			graphics.drawImage(
				source,
				dst.left, dst.top, dst.right, dst.bottom,
				src.left, src.top, src.right, src.bottom,
				null,
			)
		} finally {
			graphics.dispose()
		}
	}

	fun compress(format: String = "png"): ByteString {
		val out = ByteArrayOutputStream(image.width * image.height / 4)
		check(ImageIO.write(image, format, out)) { "No ImageIO writer for format '$format'" }
		return out.toByteArray().toByteString()
	}
}
