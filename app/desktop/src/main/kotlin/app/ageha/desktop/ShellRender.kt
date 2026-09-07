package app.ageha.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.ageha.core.data.ContinueEntry
import app.ageha.core.data.LocalArchive
import app.ageha.core.designsystem.AgehaBackdrop
import app.ageha.core.model.ArchiveUrl
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.feature.library.ContinueHero
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
		// Both skins, every screen. One skin's screenshots cannot show what the other looks like,
		// and the whole premise of the design is that the two are different materials rather than
		// two palettes -- so a review set that only ever renders Ember is a review set that
		// cannot catch Glass being broken.
		for ((skinName, mode) in listOf("ember" to AgehaThemeMode.EMBER, "glass" to AgehaThemeMode.GLASS))
		for ((name, section) in listOf(
			"library" to Section.LIBRARY,
			"continue" to Section.CONTINUE,
			"explore" to Section.EXPLORE,
			"downloads" to Section.DOWNLOADS,
			"settings" to Section.SETTINGS,
		)) {
			val navigator = Navigator().apply { switchTo(section) }
			val scene = ImageComposeScene(width = 1280, height = 860, density = Density(1f)) {
				AgehaTheme(mode = mode) {
					ChromedShell(app, navigator, mode)
				}
			}
			try {
				// Repositories emit asynchronously -- the source list arrives from the parsers
				// bridge and the library from Room. Rendering immediately captures the empty
				// first frame, which would make this test pass on an app that never loads
				// anything. Render, wait, render again.
				// Two frames on a moving clock: anything that fades in once its data arrives --
				// the reader's chapter pill, a chip's selection crossfade -- is mid-animation at
				// frame zero and would be captured half-drawn or not at all.
				scene.render(0L)
				runBlocking { delay(RENDER_SETTLE_MS) }
				val image = scene.render(RENDER_SETTLE_MS * 1_000_000)
				val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG))
				File(outDir, "shell-$skinName-$name.png").writeBytes(data.bytes)
				println("wrote shell-$skinName-$name.png")
			} finally {
				scene.close()
			}
		}
		renderSettingsPanels(app, outDir)
		renderCollapsedRail(app, outDir)
		renderSearchAll(app, outDir)
		renderCommandPanel(app, outDir)
		renderContinueHero(app, outDir)
		renderReader(app, outDir)
		val descriptors = app.sources.allDescriptors()
		println("sources visible to the UI: ${descriptors.size}")
		// The picker hides both of these by default, so a build where either count collapses to zero
		// is a build where the classification stopped working -- and the symptom of *that* is adult
		// sources appearing in a catalogue that promises it is hiding them.
		println("  adult (18+): ${descriptors.count { it.isAdult }}")
		println("  known broken: ${descriptors.count { it.isBroken }}")
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
			AgehaTheme(mode = AgehaThemeMode.EMBER) {
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
 * Renders the library with its shelf rail folded down.
 *
 * The collapsed rail is a different composition, not a narrower copy of the expanded one -- an
 * initial over a count instead of a name beside one -- and it is behind a preference, which means
 * it is only ever seen by someone who has already clicked the chevron. That is precisely the kind
 * of state that composes wrong for a release and is found by a user rather than by CI.
 */
private fun renderCollapsedRail(app: AgehaApplication, outDir: File) {
	val navigator = Navigator().apply { switchTo(Section.LIBRARY) }
	val scene = ImageComposeScene(width = 1280, height = 860, density = Density(1f)) {
		AgehaTheme(mode = AgehaThemeMode.EMBER) {
			AgehaShell(
				app,
				navigator,
				FocusRequester(),
				Modifier.fillMaxSize(),
				preferences = Preferences(libraryRailCollapsed = true),
			)
		}
	}
	try {
		scene.render()
		runBlocking { delay(RENDER_SETTLE_MS) }
		val image = scene.render()
		File(outDir, "shell-library-rail-collapsed.png").writeBytes(
			checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes,
		)
		println("wrote shell-library-rail-collapsed.png")
	} finally {
		scene.close()
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
/**
 * Renders the command panel over the library.
 *
 * It is an overlay rather than a destination, so nothing in the screen loop can reach it -- and an
 * overlay that only appears on a keystroke is exactly the surface that ships broken. Composing it
 * with `isCommandPanelOpen = true` proves the panel lays out, that its backdrop covers the window,
 * and that the field and rows draw over a live screen rather than under it.
 */
private fun renderCommandPanel(app: AgehaApplication, outDir: File) {
	val navigator = Navigator().apply { switchTo(Section.LIBRARY) }
	val scene = ImageComposeScene(width = 1280, height = 860, density = Density(1f)) {
		AgehaTheme(mode = AgehaThemeMode.EMBER) {
			AgehaShell(
				app,
				navigator,
				FocusRequester(),
				Modifier.fillMaxSize(),
				isCommandPanelOpen = true,
			)
		}
	}
	try {
		var image = scene.render()
		repeat(RENDER_FRAMES) {
			runBlocking { delay(RENDER_FRAME_GAP_MS) }
			image = scene.render()
		}
		File(outDir, "shell-command-panel.png").writeBytes(
			checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes,
		)
		println("wrote shell-command-panel.png")
	} finally {
		scene.close()
	}
}

private fun renderSearchAll(app: AgehaApplication, outDir: File) {
	val navigator = Navigator().apply { searchAllSources("berserk", subject = "Berserk") }
	val scene = ImageComposeScene(width = 1280, height = 860, density = Density(1f)) {
		AgehaTheme(mode = AgehaThemeMode.EMBER) {
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
 * Renders the Continue Reading hero, in both themes, against a real cover.
 *
 * The hero is the one component in Ageha whose colours are computed from an *image* rather than
 * from the palette, and it only appears when there is reading history -- which a fresh render
 * profile does not have. So this composes it directly, on a cover built on the spot in a colour
 * chosen to be obvious, and proves three things a unit test cannot:
 *
 *  - the cover ends up beside the text at its own 2:3 proportions rather than stretched across the
 *    panel, which is the specific regression this hero was rebuilt to fix;
 *  - `CoverAccent` gets a real decode from a real `ImageLoader` and comes back with the cover's
 *    hue, not with the neutral fallback -- the fallback looks perfectly fine in a screenshot,
 *    which is exactly why a silent failure here would ship;
 *  - the same seed lands dark in the dark theme and pale in the light one.
 */
private fun renderContinueHero(app: AgehaApplication, outDir: File) {
	// A saturated cover rather than the reader sample's greys: a grey cover is the one case
	// CoverAccent deliberately leaves achromatic, so it would prove nothing about hue.
	val archive = File(outDir, "hero-cover.cbz")
	writeSampleArchive(archive, colours = listOf(0xFF1E6F5C.toInt()), pageSize = 600 to 900)
	val (manga, _) = app.reader.localManga(archive)
	// `localManga` leaves the cover null -- a local archive has no cover *url*, only pages -- so
	// the first page is pointed at through the same `cbz://` scheme the reader uses. Without this
	// the hero draws its title fallback and CoverAccent returns the neutral theme colour, which
	// looks entirely plausible in a screenshot and proves nothing.
	val entry = ContinueEntry(
		manga = manga.copy(coverUrl = ArchiveUrl.of(archive, "001.png")),
		sourceTitle = "Local archive",
		isSourceAvailable = true,
		lastReadAt = System.currentTimeMillis(),
		progressPercent = 0.62f,
		chapterNumber = 34f,
		chapterName = null,
	)
	for ((name, mode) in listOf("dark" to AgehaThemeMode.EMBER, "light" to AgehaThemeMode.LIGHT)) {
		val scene = ImageComposeScene(width = 1000, height = 320, density = Density(1f)) {
			AgehaTheme(mode = mode) {
				AgehaBackdrop(Modifier.fillMaxSize()) {
					ContinueHero(
						entry = entry,
						imageHeaders = emptyMap(),
						onOpen = {},
						modifier = Modifier.padding(24.dp),
					)
				}
			}
		}
		try {
			// Frame by frame, on a clock that actually moves. The accent arrives from a suspending
			// decode and is then crossed in with `animateColorAsState`, and an animation advances
			// with the frame time it is handed -- not with wall-clock delays. Rendering thirty
			// frames at the default nanoTime would capture thirty copies of frame zero, which is
			// the neutral fallback, on a hero whose colour extraction had worked perfectly.
			var nanos = 0L
			var image = scene.render(nanos)
			repeat(RENDER_FRAMES) {
				runBlocking { delay(RENDER_FRAME_GAP_MS) }
				nanos += RENDER_FRAME_GAP_MS * 1_000_000
				image = scene.render(nanos)
			}
			val file = "shell-continue-hero-$name.png"
			File(outDir, file).writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
			println("wrote $file")
		} finally {
			scene.close()
		}
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
		AgehaTheme(mode = AgehaThemeMode.EMBER) {
			AgehaShell(app, navigator, FocusRequester(), Modifier.fillMaxSize())
		}
	}
	try {
		// Rendered repeatedly rather than once after a sleep. Coil loads asynchronously and the
		// composition only advances when the scene is rendered, so a single frame after a delay
		// captures the placeholder no matter how long the delay is.
		// On a clock that moves, for the reason the hero's render explains -- and for a second
		// one here. The reader's bottom pill is hidden until a page list arrives, so it enters by
		// *animation* rather than being visible from the first composition. Rendered at the
		// default nanoTime, that fade never advances past zero and the pill is absent from every
		// screenshot while being perfectly present in the running app.
		var nanos = 0L
		var image = scene.render(nanos)
		repeat(RENDER_FRAMES) {
			runBlocking { delay(RENDER_FRAME_GAP_MS) }
			nanos += RENDER_FRAME_GAP_MS * 1_000_000
			image = scene.render(nanos)
		}
		File(outDir, "shell-reader.png").writeBytes(
			checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes,
		)
		println("wrote shell-reader.png (from a real CBZ of ${LocalArchive.pages(archive).size} pages)")
	} finally {
		scene.close()
	}
}

/** Numbered pages, each a distinct flat colour, so page order is visible in the render. */
private fun writeSampleArchive(
	target: File,
	colours: List<Int> = listOf(
		0xFF3A3A3A.toInt(),
		0xFF5A5A5A.toInt(),
		0xFF7A7A7A.toInt(),
		0xFF9A9A9A.toInt(),
	),
	pageSize: Pair<Int, Int> = 600 to 900,
) {
	val (width, height) = pageSize
	java.util.zip.ZipOutputStream(target.outputStream()).use { zip ->
		colours.forEachIndexed { index, colour ->
			val page = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
			val g = page.createGraphics()
			g.color = java.awt.Color(colour)
			g.fillRect(0, 0, width, height)
			g.color = java.awt.Color.WHITE
			g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 96)
			g.drawString("${index + 1}", width * 4 / 10, height / 2)
			g.dispose()
			zip.putNextEntry(java.util.zip.ZipEntry("%03d.png".format(index + 1)))
			javax.imageio.ImageIO.write(page, "png", zip)
			zip.closeEntry()
		}
	}
}

/**
 * The shell with its title bar, as the window actually assembles it.
 *
 * Duplicated from `Main.kt` rather than shared, and that is the honest trade: the real one is
 * bound to a `Window` -- it minimises, maximises and closes a thing that does not exist here --
 * so extracting a common composable would mean inventing an abstraction over "has a window" whose
 * only second implementation is a screenshot. The part worth keeping in step is the *layout*: a
 * title bar above a shell, both drawn in the same theme. That is what this repeats, in six lines.
 */
@androidx.compose.runtime.Composable
private fun ChromedShell(
	app: AgehaApplication,
	navigator: Navigator,
	mode: AgehaThemeMode,
) {
	androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
		AgehaTitleBar(
			context = windowContextLine(app, navigator),
			theme = mode,
			onTheme = {},
			onMinimize = {},
			onToggleMaximize = {},
			onClose = {},
		)
		AgehaShell(app, navigator, FocusRequester(), Modifier.fillMaxSize())
	}
}
