package app.ageha.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.ReaderBackground
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaPage
import app.ageha.feature.reader.PAGE_RETRY_TAG
import app.ageha.feature.reader.ReaderPage
import app.ageha.feature.reader.ReaderScreen
import app.ageha.feature.reader.ReaderUiState
import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * A page image that will not load says so, and can be asked for again.
 *
 * It used to draw nothing. ComicK's image server refused every page Ageha asked for, and the reader
 * opened to a blank screen with no spinner, no message and no way to tell a refused image from one
 * that had never been requested.
 *
 * Driven by a real HTTP server that refuses the image and then serves it, so the whole path is the
 * one a source takes: an `HttpException` out of Coil's network fetcher, the message and the Retry
 * button in the page's place, and a second request when the button is pressed.
 */
@OptIn(ExperimentalTestApi::class)
class ReaderPageFailureTest {

	private lateinit var server: HttpServer
	private val refuse = AtomicBoolean(true)
	private val requests = AtomicInteger()
	private val loaded = AtomicInteger()

	@BeforeEach
	fun setUp() {
		val png = onePixelPng()
		server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
			createContext("/page.png") { exchange ->
				requests.incrementAndGet()
				val body = if (refuse.get()) "refused".toByteArray() else png
				exchange.responseHeaders.add("Content-Type", if (refuse.get()) "text/plain" else "image/png")
				exchange.sendResponseHeaders(if (refuse.get()) 403 else 200, body.size.toLong())
				exchange.responseBody.use { it.write(body) }
			}
			start()
		}
		// A loader of our own, so that "the page loaded" is an event this test can wait for rather
		// than an absence it has to hope has lasted long enough.
		SingletonImageLoader.setUnsafe(
			ImageLoader.Builder(PlatformContext.INSTANCE)
				.eventListener(object : EventListener() {
					override fun onSuccess(request: ImageRequest, result: SuccessResult) {
						loaded.incrementAndGet()
					}
				})
				.build(),
		)
	}

	@AfterEach
	fun tearDown() {
		server.stop(0)
		// The singleton is process-global. Left set, it would leak into any test that ran after.
		SingletonImageLoader.reset()
	}

	@Test
	fun `a refused page shows why, and Retry fetches it again`() = runComposeUiTest {
		val url = "http://127.0.0.1:" + server.address.port + "/page.png"
		setContent {
			AgehaTheme(mode = AgehaThemeMode.EMBER) {
				ReaderScreen(
					state = ReaderUiState(manga = manga, chapter = chapter, chapterCount = 1, pages = listOf(page(url))),
					background = ReaderBackground.BLACK,
					doublePage = false,
					coverOffset = true,
					onPageChange = {},
					onScroll = { _, _ -> },
					onNextPage = {},
					onPreviousPage = {},
					onNextChapter = {},
					onPreviousChapter = {},
					onOpenChapterList = {},
					onSetMode = {},
					onSetScale = {},
					onSetBackground = {},
					onToggleDoublePage = {},
					onToggleCoverOffset = {},
					onToggleChrome = {},
					onRetry = {},
					onClose = {},
					modifier = Modifier.fillMaxSize(),
				)
			}
		}

		waitUntil("the refused page to be reported", WAIT_MS) {
			onAllNodesWithTag(PAGE_RETRY_TAG).fetchSemanticsNodes().isNotEmpty()
		}
		onNodeWithText("Page 1 could not be loaded").assertIsDisplayed()
		onNodeWithText("HTTP 403 from 127.0.0.1").assertIsDisplayed()
		assertEquals(1, requests.get(), "the page is asked for once, and refused")

		refuse.set(false)
		onNodeWithTag(PAGE_RETRY_TAG).performClick()

		waitUntil("the retried page to load", WAIT_MS) { loaded.get() == 1 }
		waitForIdle()
		assertEquals(2, requests.get(), "Retry must make a new request, not replay the failure")
		assertEquals(
			0,
			onAllNodesWithTag(PAGE_RETRY_TAG).fetchSemanticsNodes().size,
			"a page that loaded must not still be showing its failure",
		)
	}

	private fun onePixelPng(): ByteArray = ByteArrayOutputStream().use { out ->
		ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", out)
		out.toByteArray()
	}

	private fun page(url: String) = ReaderPage(
		page = AgehaPage(id = 1L, url = url, preview = null, sourceName = "LOCAL"),
		index = 0,
		resolvedUrl = url,
	)

	private val manga = AgehaManga(
		id = 1L,
		title = "Test",
		altTitles = emptySet(),
		url = "test",
		publicUrl = "test",
		rating = null,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = null,
		sourceName = "LOCAL",
	)

	private val chapter = AgehaChapter(
		id = 1L,
		title = "Chapter 1",
		number = 1f,
		volume = null,
		url = "test",
		scanlator = null,
		uploadDate = null,
		branch = null,
		sourceName = "LOCAL",
	)

	private companion object {
		const val WAIT_MS = 10_000L
	}
}
