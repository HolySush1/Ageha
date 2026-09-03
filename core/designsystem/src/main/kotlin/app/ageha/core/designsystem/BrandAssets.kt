package app.ageha.core.designsystem

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * The generated brand images, loaded from the classpath.
 *
 * Compose Multiplatform's own `useResource`/`loadImageBitmap` pair is deprecated in favour of the
 * `compose.components.resources` library, which brings a Gradle plugin, a generated `Res` object
 * and a `composeResources` source layout. That machinery earns its keep when the same images have
 * to reach Android, iOS and the web; Ageha is one JVM target reading one classpath, so it buys
 * nothing here and costs a build plugin. `ImageIO` plus `toComposeImageBitmap` is the plain
 * equivalent, is not deprecated, and is three lines.
 *
 * Everything under `app/ageha/brand/` is written by `:tools:brandkit:generateIcons`. Do not add
 * files there by hand -- the generator owns the directory and will not preserve them.
 */
object BrandAssets {

	private val cache = ConcurrentHashMap<String, ImageBitmap>()

	/** Window and taskbar icon. The OS rescales it, so give it plenty of pixels. */
	fun windowIcon(): Painter = painter("icon-256.png")

	/**
	 * Tray icon for a tray whose background is [darkTray].
	 *
	 * Trays tint, invert and composite icons differently on every platform, so Ageha ships both
	 * polarities of the monochrome mark and picks rather than hoping one works everywhere.
	 */
	fun trayIcon(size: Int = 16, darkTray: Boolean = true): Painter =
		painter(if (darkTray) "tray-light-$size.png" else "tray-dark-$size.png")

	fun painter(name: String): Painter = BitmapPainter(bitmap(name))

	fun bitmap(name: String): ImageBitmap = cache.getOrPut(name) {
		val path = "app/ageha/brand/$name"
		val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
			"brand asset $path is missing; run ./gradlew :tools:brandkit:generateIcons"
		}
		stream.use { ImageIO.read(it) }.toComposeImageBitmap()
	}
}
