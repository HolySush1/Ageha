package app.ageha.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Density
import app.ageha.core.data.LocalArchive
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.feature.settings.SettingsSection
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
		for ((name, section) in listOf(
			"library" to Section.LIBRARY,
			"continue" to Section.CONTINUE,
			"explore" to Section.EXPLORE,
			"downloads" to Section.DOWNLOADS,
			"settings" to Section.SETTINGS,
		)) {
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
		renderSettingsPanels(app, outDir)
		renderSearchAll(app, outDir)
		renderReader(app, outDir)
		println("sources visible to the UI: ${app.sources.allDescriptors().size}")
	} finally {
		app.close()
	}
}

/** Long enough for the parsers bridge and the first Room emission. */
private const val RENDER_SETTLE_MS = 2_500L

/** Frames to draw while waiting for asynchronous image loads to land. */
private const val RENDER_FRAMES = 30
private const val RENDER_FRAME_GAP_MS = 100L

/**
 * Renders each settings panel.
 *
 * The panels beyond Appearance are three clicks from the front door, which makes them exactly the
 * screens that compose wrong for a release and are found by a user rather than by CI. Sync is the
 * newest and the worst of them to get wrong: it is a form, so a failure there is a user who cannot
 * sign in rather than a screen that looks odd.
 */
private fun renderSettingsPanels(app: AgehaApplication, outDir: File) {
	for (section in SettingsSection.entries) {
		val navigator = Navigator().apply { switchTo(Section.SETTINGS) }
		val scene = ImageComposeScene(width = 1280, height = 860, density = Density(1f)) {
			AgehaTheme(mode = AgehaThemeMode.DARK) {
				AgehaShell(
					app,
					navigator,
					FocusRequester(),
					Modifier.fillMaxSize(),
					initialSettingsSection = section,
				)
			}
		}
		try {
			scene.render()
			runBlocking { delay(RENDER_FRAME_GAP_MS) }
			val image = scene.render()
			val name = "shell-settings-" + section.name.lowercase() + ".png"
			File(outDir, name).writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
			println("wrote " + name)
		} finally {
			scene.close()
		}
	}
}

/**
 * Renders the cross-source search, which nothing else reaches.
 *
 * It is the destination a Continue Reading entry lands on when its source has gone from the
 * parsers build, so it is only ever seen in a situation that is hard to arrange deliberately --
 * exactly the kind of screen that composes wrong for a release and is found by a user.
 *
 * On a fresh profile no sources are enabled, so this draws its empty state rather than querying
 * anybody's server. That is the intended behaviour on a machine that has never been configured,
 * and rendering it proves the screen handles it.
 */
private fun renderSearchAll(app: AgehaApplication, outDir: File) {
	val navigator = Navigator().apply { searchAllSources("berserk", subject = "Berserk") }
	val scene = ImageComposeScene(width = 1280, height = 860, density = Density(1f)) {
		AgehaTheme(mode = AgehaThemeMode.DARK) {
			AgehaShell(app, navigator, FocusRequester(), Modifier.fillMaxSize())
		}
	}
	try {
		// Frame by frame rather than one render after a sleep: sources answer at their own pace
		// and Coil decodes their covers asynchronously, and a composition only advances when the
		// scene is rendered. One late frame would capture placeholders however long the wait.
		var image = scene.render()
		repeat(RENDER_FRAMES) {
			runBlocking { delay(RENDER_FRAME_GAP_MS) }
			image = scene.render()
		}
		File(outDir, "shell-search-all.png").writeBytes(
			checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes,
		)
		println("wrote shell-search-all.png")
	} finally {
		scene.close()
	}
}

/**
 * Renders the reader against a real CBZ built on the spot.
 *
 * End to end and with nothing mocked: LocalArchive lists the zip, ReaderRepository serves the
 * pages, the view model resolves them, `:core:image`'s archive fetcher pulls the bytes back out of
 * the zip, Coil decodes them and Compose draws them. A unit test covers each of those in
 * isolation; only this proves they are connected.
 */
private fun renderReader(app: AgehaApplication, outDir: File) {
	val archive = File(outDir, "sample.cbz")
	writeSampleArchive(archive)
	val (manga, chapter) = app.reader.localManga(archive)
	val navigator = Navigator().apply { read(manga, chapter) }

	val scene = ImageComposeScene(width = 1000, height = 720, density = Density(1f)) {
		AgehaTheme(mode = AgehaThemeMode.DARK) {
			AgehaShell(app, navigator, FocusRequester(), Modifier.fillMaxSize())
		}
	}
	try {
		// Rendered repeatedly rather than once after a sleep. Coil loads asynchronously and the
		// composition only advances when the scene is rendered, so a single frame after a delay
		// captures the placeholder no matter how long the delay is.
		var image = scene.render()
		repeat(RENDER_FRAMES) {
			runBlocking { delay(RENDER_FRAME_GAP_MS) }
			image = scene.render()
		}
		File(outDir, "shell-reader.png").writeBytes(
			checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes,
		)
		println("wrote shell-reader.png (from a real CBZ of ${LocalArchive.pages(archive).size} pages)")
	} finally {
		scene.close()
	}
}

/** Four numbered pages, each a distinct flat colour, so page order is visible in the render. */
private fun writeSampleArchive(target: File) {
	val colours = listOf(0xFF3A3A3A.toInt(), 0xFF5A5A5A.toInt(), 0xFF7A7A7A.toInt(), 0xFF9A9A9A.toInt())
	java.util.zip.ZipOutputStream(target.outputStream()).use { zip ->
		colours.forEachIndexed { index, colour ->
			val page = java.awt.image.BufferedImage(600, 900, java.awt.image.BufferedImage.TYPE_INT_RGB)
			val g = page.createGraphics()
			g.color = java.awt.Color(colour)
			g.fillRect(0, 0, 600, 900)
			g.color = java.awt.Color.WHITE
			g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 96)
			g.drawString("${index + 1}", 260, 480)
			g.dispose()
			zip.putNextEntry(java.util.zip.ZipEntry("%03d.png".format(index + 1)))
			javax.imageio.ImageIO.write(page, "png", zip)
			zip.closeEntry()
		}
	}
}
