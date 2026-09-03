package app.ageha.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Density
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the real application shell to a PNG, headlessly, against the real graph.
 *
 * Not a mock. This starts the actual Koin container, loads the actual parsers build through the
 * classloader boundary, opens the actual database, and draws whatever comes back. It therefore
 * fails if the parsers jar will not load, if the source registry is empty, if a repository query
 * is malformed, or if a screen will not compose -- and it fails in CI, on a machine with no
 * display, before anyone ships an app whose first window is blank.
 *
 * `./gradlew :app:desktop:renderShell`
 */
fun main(args: Array<String>) {
	val outDir = File(args.firstOrNull() ?: "build/shell").apply { mkdirs() }
	val app = AgehaApplication.start()
	try {
		for ((name, section) in listOf("library" to Section.LIBRARY, "explore" to Section.EXPLORE)) {
			val navigator = Navigator().apply { switchTo(section) }
			val scene = ImageComposeScene(width = 1280, height = 860, density = Density(1f)) {
				AgehaTheme(mode = AgehaThemeMode.DARK) {
					AgehaShell(app, navigator, FocusRequester(), Modifier.fillMaxSize())
				}
			}
			try {
				// Repositories emit asynchronously -- the source list arrives from the parsers
				// bridge and the library from Room. Rendering immediately captures the empty
				// first frame, which would make this test pass on an app that never loads
				// anything. Render, wait, render again.
				scene.render()
				runBlocking { delay(RENDER_SETTLE_MS) }
				val image = scene.render()
				val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG))
				File(outDir, "shell-$name.png").writeBytes(data.bytes)
				println("wrote shell-$name.png")
			} finally {
				scene.close()
			}
		}
		println("sources visible to the UI: ${app.sources.allDescriptors().size}")
	} finally {
		app.close()
	}
}

/** Long enough for the parsers bridge and the first Room emission. */
private const val RENDER_SETTLE_MS = 2_500L
