package app.ageha.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.ageha.core.data.LocalArchive
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.ReaderBackground
import app.ageha.core.designsystem.AgehaThemeMode
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.ageha.core.model.ReaderMode
import app.ageha.feature.reader.ReaderScreen
import app.ageha.feature.reader.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToLong

/**
 * The measurement `docs/ARCHITECTURE.md` 1.4 has been waiting for.
 *
 * The risk recorded at the start of the project was that Compose Desktop has no equivalent of
 * Android's `RecyclerView` tuning, and that a webtoon chapter -- 200 images, each taller than the
 * window by an order of magnitude -- would either stutter or exhaust the heap. `WebtoonReader`
 * bets on a `LazyColumn`, on the argument that a lazy list disposes items that leave the viewport
 * and Coil releases their bitmaps with them, so the decoded set stays proportional to the viewport
 * rather than to the chapter. That was an argument, not a number.
 *
 * This produces the number. It builds a genuine 200-page strip, opens it in the real reader
 * through the real graph, scrolls it end to end with real scroll events, and reports frame times
 * and heap. Nothing is mocked and nothing is a benchmark harness pretending to be the reader: it
 * is `AgehaShell` with `ReaderMode.WEBTOON`, the same code a user scrolls.
 *
 * `./gradlew :app:desktop:webtoonProfile`
 */
fun main(args: Array<String>) {
	val outDir = File(args.firstOrNull() ?: "build/profile").apply { mkdirs() }
	val app = AgehaApplication.start()
	try {
		val archive = File(outDir, "webtoon-$PAGE_COUNT.cbz")
		if (!archive.isFile) writeWebtoonArchive(archive)
		val (manga, chapter) = app.reader.localManga(archive)
		runBlocking { app.reader.setMode(manga, ReaderMode.WEBTOON) }

		val pages = LocalArchive.pages(archive).size
		check(pages == PAGE_COUNT) { "expected $PAGE_COUNT pages, archive has $pages" }
		println("strip: $pages pages of ${PAGE_WIDTH}x$PAGE_HEIGHT, ${archive.length() / 1024}KB")

		val report = profile(app, manga, chapter)
		println(report.render())
		File(outDir, "webtoon-profile.txt").writeText(report.render())

		// Two separate claims, both worth failing on. That the strip moved at all: a profile of a
		// static frame reports beautiful numbers and measures nothing, and nothing in the timings
		// would reveal it. And that it reached the end: the frames worth measuring are the ones
		// deep in the strip, where a list that failed to release what it passed is already in
		// trouble.
		//
		// Read from the view model rather than from the history row. The row is written on a 600ms
		// debounce that a loop firing an event every 16ms never lets complete, so it lags the strip
		// by an arbitrary amount -- which is correct behaviour for the reader and useless as a
		// measurement.
		check(report.reachedPage > 0) {
			"the strip never scrolled -- the numbers above measure a still image"
		}
		check(report.reachedPage >= pages - END_OF_STRIP_SLACK) {
			"only reached page ${report.reachedPage + 1} of $pages -- the far end was never rendered"
		}
		// A strip that scrolls smoothly while showing spinners is fast for the wrong reason, and
		// the frame times alone look excellent when it happens. This is how that was caught.
		check(report.resolvedPages >= pages - UNRESOLVED_SLACK) {
			"only ${report.resolvedPages} of $pages pages ever resolved -- the strip was scrolled " +
				"past placeholders, so the frame times above are not the real cost"
		}
	} finally {
		app.close()
	}
}

private class Report(
	val reachedPage: Int,
	val resolvedPages: Int,
	val failedPages: Int,
	val frameMillis: List<Double>,
	val heapBeforeBytes: Long,
	val heapAfterBytes: Long,
	val heapPeakBytes: Long,
) {
	fun render(): String {
		val sorted = frameMillis.sorted()
		fun pct(p: Double) = sorted[((sorted.size - 1) * p).roundToLong().toInt()]
		return buildString {
			appendLine("webtoon strip, $PAGE_COUNT pages, ${frameMillis.size} scrolled frames")
			appendLine("  frame p50   ${"%.2f".format(pct(0.50))} ms")
			appendLine("  frame p95   ${"%.2f".format(pct(0.95))} ms")
			appendLine("  frame p99   ${"%.2f".format(pct(0.99))} ms")
			appendLine("  frame max   ${"%.2f".format(sorted.last())} ms")
			appendLine("  budget      ${"%.2f".format(FRAME_BUDGET_MS)} ms (60Hz)")
			appendLine("  over budget ${frameMillis.count { it > FRAME_BUDGET_MS }} frames")
			appendLine("  heap before ${heapBeforeBytes / MB} MB")
			appendLine("  heap peak   ${heapPeakBytes / MB} MB")
			appendLine("  heap after  ${heapAfterBytes / MB} MB (after gc)")
			appendLine("  reached     page ${reachedPage + 1} of $PAGE_COUNT")
			appendLine("  resolved    $resolvedPages pages, $failedPages failed")
		}
	}
}

private fun profile(
	app: AgehaApplication,
	manga: app.ageha.core.model.AgehaManga,
	chapter: app.ageha.core.model.AgehaChapter,
): Report {
	// The real `ReaderScreen`, driven by a real `ReaderViewModel`, rather than the whole shell.
	//
	// Not a simplification -- `ReaderScreen` *is* the code under test, and `AgehaShell` around it
	// only decides which screen shows. What owning the view model buys is the one thing the shell
	// hides: `state.currentPage`, updated synchronously as the strip scrolls, which is how this
	// knows the measurement covered the strip instead of assuming it.
	val viewModel = ReaderViewModel(app.reader, app.scope)
	// Page 0 explicitly, never -1: resuming from history would start a rerun wherever the last one
	// stopped and quietly measure a shorter strip each time.
	viewModel.open(manga, chapter, startPage = 0)

	val scene = ImageComposeScene(width = WINDOW_WIDTH, height = WINDOW_HEIGHT, density = Density(1f)) {
		AgehaTheme(mode = AgehaThemeMode.EMBER) {
			val state by viewModel.state.collectAsState()
			ReaderScreen(
				state = state,
				background = ReaderBackground.BLACK,
				doublePage = false,
				coverOffset = false,
				onPageChange = viewModel::goToPage,
				onScroll = viewModel::recordScroll,
				onNextPage = viewModel::nextPage,
				onPreviousPage = viewModel::previousPage,
				onNextChapter = {},
				onPreviousChapter = {},
				onOpenChapterList = {},
				onSetMode = viewModel::setMode,
				onSetScale = viewModel::setScale,
				onSetBackground = {},
				onToggleDoublePage = {},
				onToggleCoverOffset = {},
				onToggleChrome = viewModel::toggleChrome,
				onRetry = viewModel::retry,
				onClose = {},
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
	try {
		// Warm-up, excluded from the numbers. The first frames pay for composition and Coil's first
		// decodes, neither of which a user pays again while scrolling -- including them would report
		// the cost of *opening* a chapter as the cost of reading one.
		var clock = 0L
		repeat(WARMUP_FRAMES) {
			scene.render(clock)
			clock += FRAME_NANOS
			runBlocking { delay(WARMUP_FRAME_GAP_MS) }
		}
		check(viewModel.state.value.pageCount == PAGE_COUNT) {
			"the reader never loaded the strip: ${viewModel.state.value.pageCount} pages, " +
				"failure ${viewModel.state.value.failure}"
		}
		check(viewModel.state.value.mode == ReaderMode.WEBTOON) {
			"not in webtoon mode -- this would profile the paged reader instead"
		}

		System.gc()
		val heapBefore = usedHeap()
		var heapPeak = heapBefore
		val frames = ArrayList<Double>(SCROLL_STEPS)
		var reached = 0

		val centre = Offset(WINDOW_WIDTH / 2f, WINDOW_HEIGHT / 2f)
		repeat(SCROLL_STEPS) {
			scene.sendPointerEvent(
				eventType = PointerEventType.Scroll,
				position = centre,
				// Positive Y scrolls down the strip. One notch is roughly a mouse wheel detent.
				scrollDelta = Offset(0f, SCROLL_NOTCHES),
			)
			val started = System.nanoTime()
			scene.render(clock)
			frames += (System.nanoTime() - started) / NANOS_PER_MILLI
			clock += FRAME_NANOS
			heapPeak = maxOf(heapPeak, usedHeap())
			reached = maxOf(reached, viewModel.state.value.currentPage)
			// Images decode off-thread; without letting them land this measures a strip of
			// placeholders, which is the cheap case and not the one at risk.
			runBlocking { delay(SCROLL_FRAME_GAP_MS) }
		}

		val heapPeakSeen = heapPeak
		val finalState = viewModel.state.value
		System.gc()
		return Report(
			reachedPage = reached,
			// Reported because it is the number that caught a real bug: a strip that scrolls
			// beautifully while showing spinners is fast for the wrong reason.
			resolvedPages = finalState.pages.count { it.resolvedUrl != null },
			failedPages = finalState.pages.count { it.failure != null },
			frameMillis = frames,
			heapBeforeBytes = heapBefore,
			heapAfterBytes = usedHeap(),
			heapPeakBytes = heapPeakSeen,
		)
	} finally {
		scene.close()
	}
}

private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

/**
 * A 200-page strip, written as a real CBZ.
 *
 * The page images cycle through [DISTINCT_PAGES] designs rather than being 200 unique renders.
 * That is a generation shortcut and not a measurement one: every entry is a separate zip member
 * with its own url, so Coil caches, fetches and decodes each independently -- the reader does
 * exactly as much work either way. Rendering 200 distinct 800x2400 images up front would have
 * added a minute to a task whose point is the scrolling.
 */
private fun writeWebtoonArchive(target: File) {
	val designs = (0 until DISTINCT_PAGES).map { encodePage(it) }
	ZipOutputStream(target.outputStream().buffered()).use { zip ->
		for (index in 0 until PAGE_COUNT) {
			zip.putNextEntry(ZipEntry("page-%03d.png".format(index + 1)))
			zip.write(designs[index % DISTINCT_PAGES])
			zip.closeEntry()
		}
	}
	println("wrote ${target.name} (${target.length() / 1024}KB)")
}

/** One tall panel: banded, so the PNG is not a degenerate flat fill, with its number on it. */
private fun encodePage(seed: Int): ByteArray {
	val image = BufferedImage(PAGE_WIDTH, PAGE_HEIGHT, BufferedImage.TYPE_INT_RGB)
	val g = image.createGraphics()
	g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
	val bands = 24
	val bandHeight = PAGE_HEIGHT / bands
	for (band in 0 until bands) {
		val shade = 40 + ((band * 7 + seed * 11) % 140)
		g.color = Color(shade, shade, shade)
		g.fillRect(0, band * bandHeight, PAGE_WIDTH, bandHeight)
	}
	g.color = Color.WHITE
	g.font = Font(Font.SANS_SERIF, Font.BOLD, 160)
	g.drawString("${seed + 1}", 60, 220)
	g.dispose()
	return ByteArrayOutputStream().use { out ->
		ImageIO.write(image, "png", out)
		out.toByteArray()
	}
}

/** A realistic webtoon panel: narrow, and much taller than any window. */
private const val PAGE_WIDTH = 800
private const val PAGE_HEIGHT = 2_400

/** The count the architecture risk was written about. */
private const val PAGE_COUNT = 200

private const val DISTINCT_PAGES = 12

private const val WINDOW_WIDTH = 1_000
private const val WINDOW_HEIGHT = 1_200

private const val WARMUP_FRAMES = 20
private const val WARMUP_FRAME_GAP_MS = 60L

/**
 * Enough steps to reach the end of the strip.
 *
 * At this notch size a step advances roughly 1,180px and the strip is about 600,000px tall once
 * each 800x2400 page is scaled to the window width, so ~510 steps reach the last page. The margin
 * is there so the run still finishes the strip if the window or the page size changes; the final
 * position is asserted afterwards rather than assumed.
 */
private const val SCROLL_STEPS = 600
private const val SCROLL_NOTCHES = 6f
private const val SCROLL_FRAME_GAP_MS = 16L

private const val FRAME_BUDGET_MS = 16.67
private const val FRAME_NANOS = 16_666_667L
private const val NANOS_PER_MILLI = 1_000_000.0
private const val MB = 1024 * 1024

/** How near the last page counts as having crossed the strip. */
private const val END_OF_STRIP_SLACK = 3

/**
 * How many pages may still be unresolved at the end.
 *
 * A small allowance, not a loose one: resolution runs a few pages ahead of the reader, so the
 * very last pages can still be in flight when the scroll stops.
 */
private const val UNRESOLVED_SLACK = 4
