package app.ageha.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import app.ageha.core.designsystem.ThemeGallery
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the theme gallery straight to a PNG, with no window.
 *
 * A gallery is only useful if somebody looks at it, and "somebody" includes review on a machine
 * that is not this one. `ImageComposeScene` composes and draws through the same Skia pipeline the
 * real window uses, so the output is what the app actually renders rather than an approximation --
 * but it is deterministic, headless, and can run in CI, which a screenshot of a window is not.
 *
 * `./gradlew :app:desktop:renderGallery`
 */
fun main(args: Array<String>) {
	val target = File(args.firstOrNull() ?: "docs/design-gallery.png")
	target.parentFile?.mkdirs()

	// Tall enough for the whole scrolling column: the point is to see all of it at once.
	val scene = ImageComposeScene(width = 1220, height = 2400, density = Density(1f)) {
		ThemeGallery(Modifier.fillMaxSize())
	}
	try {
		val image = scene.render()
		val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) {
			"Skia declined to encode the gallery"
		}
		target.writeBytes(data.bytes)
		println("wrote ${target.path} (${image.width}x${image.height})")
	} finally {
		// The scene owns native Skia resources; leaking it hangs the JVM on exit.
		scene.close()
	}
}
