package app.ageha.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.MANGA_CARD_TAG
import app.ageha.core.data.LocalArchive
import app.ageha.core.network.AgehaPaths
import app.ageha.feature.explore.CHAPTER_ROW_TAG
import app.ageha.feature.explore.SOURCE_ROW_TAG
import app.ageha.feature.explore.SOURCE_TOGGLE_TAG
import app.ageha.feature.library.CONTINUE_ROW_TAG
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * The journey a person actually makes through Ageha, driven against the real application.
 *
 * Nothing here is mocked. Each phase starts a real [AgehaApplication] -- the real Koin graph, the
 * real parsers build behind its classloader, the real Room database on disk -- renders the real
 * [AgehaShell], and drives it with synthetic clicks, typing and key presses through Compose's
 * semantics tree. What it asserts on is what a user would see on screen.
 *
 * It exists because three of the four bugs found in the previous session were invisible to unit
 * tests and appeared only once something rendered. Composing each screen in isolation proves each
 * screen composes; only walking the whole path proves the path connects.
 *
 * **The phases are separate application lifetimes on purpose.** [readsAChapter] closes its
 * application before [resumesWhereItLeftOff] opens a new one against the same profile directory,
 * so the reading position under test has genuinely been through SQLite and back rather than
 * through a repository's memory. That is the "close it and reopen it" the reader has to survive,
 * and it is the part no in-process test was covering.
 *
 * Ordered, and sharing state through a companion object, because the second phase is meaningless
 * without the first. JUnit's per-method isolation is the right default and this is the case that
 * genuinely wants the other thing.
 *
 * Tagged `e2e` and excluded from the default run: it takes minutes. Run it with
 * `./gradlew :app:desktop:e2e`, which also points [AgehaPaths] at a scratch profile under `build/`
 * so it cannot touch the profile somebody actually reads their manga in.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgehaJourneyTest {

	companion object {
		/**
		 * Where phase one stopped, for phase two to check against.
		 *
		 * A companion object rather than instance fields: JUnit builds a fresh instance per test
		 * method, so instance fields would be empty by the time the second phase read them.
		 */
		private var stoppedOnPage = -1
		private var pageCount = -1

		/**
		 * How long to wait for something that crosses a real boundary.
		 *
		 * Generous, because the work behind these waits is a database open, a parsers-jar
		 * classload, or somebody else's web server -- none of them fast, and all of them slower on
		 * a loaded CI machine than on a laptop. A wait that is too short does not fail the feature
		 * under test, it fails the machine, and that is the worst kind of red.
		 */
		private const val WAIT_MS = 30_000L

		/** Live sources get longer again: this is a stranger's server, over the internet. */
		private const val NETWORK_WAIT_MS = 90_000L

		/** What `ReaderPageImage` prefixes its content description with. */
		private const val PAGE_LABEL = "Page "

	}

	/**
	 * A twelve-page archive, each page a distinct flat colour with its number drawn on it.
	 *
	 * Twelve rather than four, so "page 5" is unambiguously not the first page, the last page, or
	 * a fencepost either side of them -- the three values a broken resume tends to land on.
	 */
	private fun writeArchive(target: File, pages: Int) {
		target.parentFile?.mkdirs()
		ZipOutputStream(target.outputStream()).use { zip ->
			repeat(pages) { index ->
				val image = BufferedImage(600, 900, BufferedImage.TYPE_INT_RGB)
				val g = image.createGraphics()
				g.color = Color(40 + index * 15, 40 + index * 15, 48 + index * 15)
				g.fillRect(0, 0, 600, 900)
				g.color = Color.WHITE
				g.font = Font("SansSerif", Font.BOLD, 140)
				g.drawString("${index + 1}", 240, 500)
				g.dispose()
				zip.putNextEntry(ZipEntry("%03d.png".format(index + 1)))
				ImageIO.write(image, "png", zip)
				zip.closeEntry()
			}
		}
	}

	/** The archive both local phases read. Under the profile, so the `e2e` task cleans it up. */
	private val archive: File get() = File(AgehaPaths.dataDir, "journey.cbz")

	/**
	 * Phase one: open an archive, read into it, and close the application.
	 *
	 * A local archive rather than a live source, because this phase's subject is the *reading
	 * position*, and a position test that goes red when MangaDex is having an afternoon tells you
	 * nothing about Ageha. The live-source path is [findsAndOpensALiveSource], which is where a
	 * network failure belongs.
	 */
	@Test
	@Order(1)
	@OptIn(ExperimentalTestApi::class)
	fun readsAChapter() = runComposeUiTest {
		writeArchive(archive, pages = 12)

		val app = AgehaApplication.start()
		try {
			val navigator = Navigator()
			val keyRouter = KeyRouter()
			setContent {
				AgehaTheme(mode = AgehaThemeMode.DARK) {
					AgehaShell(
						app,
						navigator,
						FocusRequester(),
						Modifier.fillMaxSize(),
						keyRouter = keyRouter,
					)
				}
			}

			// Open the archive the way the File menu does.
			val (manga, chapter) = app.reader.localManga(archive)
			navigator.read(manga, chapter)

			pageCount = LocalArchive.pages(archive).size
			assertEquals(12, pageCount, "the archive under test should have twelve pages")
			assertEquals(
				1,
				awaitPageOnScreen(),
				"a chapter never opened should start at page 1, with its first page decoded",
			)

			// Four page turns, through the same key path the window uses. The reader installs its
			// bindings into the KeyRouter above, so this is the real dispatch, not a shortcut to
			// the view model.
			//
			// Space, not Right. Ageha's default reader mode is right-to-left, because that is what
			// manga is, and in that mode Right means *back* -- so four Rights from page one leave
			// you on page one, which is what this test asserted on its first run. Space and Page
			// Down are the direction-independent "forward" keys, and they are what a test of
			// "turn the page" should press.
			repeat(4) {
				assertTrue(
					keyRouter.dispatch(keyDown(Key.Spacebar)),
					"the reader should consume the page-forward key while it is open",
				)
				waitForIdle()
			}
			stoppedOnPage = awaitPageOnScreen()
			assertEquals(5, stoppedOnPage, "four page turns from page 1 should land on page 5")

			// Leaving the reader is what flushes the position past its debounce.
			navigator.back()
			waitForIdle()
		} finally {
			app.close()
		}
	}

	/**
	 * Phase two: reopen, and check Continue Reading goes back to the right page.
	 *
	 * This is the assertion the whole test exists for.
	 */
	@Test
	@Order(2)
	@OptIn(ExperimentalTestApi::class)
	fun resumesWhereItLeftOff() = runComposeUiTest {
		assertTrue(stoppedOnPage > 0, "phase one must have run first")

		// A brand new application against the same directory on disk. Nothing survives from phase
		// one except the database and the preferences file.
		val app = AgehaApplication.start()
		try {
			val navigator = Navigator()
			setContent {
				AgehaTheme(mode = AgehaThemeMode.DARK) {
					AgehaShell(app, navigator, FocusRequester(), Modifier.fillMaxSize())
				}
			}

			navigator.switchTo(Section.CONTINUE)
			awaitTag(CONTINUE_ROW_TAG, "a Continue Reading entry for what phase one read")

			onAllNodesWithTag(CONTINUE_ROW_TAG).onFirst().performClick()
			assertEquals(
				stoppedOnPage,
				awaitPageOnScreen(),
				"Continue Reading should reopen on the page the reader stopped on",
			)
		} finally {
			app.close()
		}
	}

	/**
	 * The live-source half: enable a source, search it, open a manga, open a chapter.
	 *
	 * Separated from the position test and tagged `network` as well, so a source having a bad day
	 * fails only the thing that actually depends on that source. It asserts on structure --
	 * results arrived, chapters arrived, pages arrived -- and never on a particular title, because
	 * what MangaDex returns for a query is theirs to change.
	 */
	@Test
	@Order(3)
	@Tag("network")
	@OptIn(ExperimentalTestApi::class)
	fun findsAndOpensALiveSource() = runComposeUiTest {
		val app = AgehaApplication.start()
		try {
			val navigator = Navigator()
			setContent {
				AgehaTheme(mode = AgehaThemeMode.DARK) {
					AgehaShell(app, navigator, FocusRequester(), Modifier.fillMaxSize())
				}
			}

			navigator.switchTo(Section.EXPLORE)
			waitForIdle()

			// Filter 1360 sources down to one, the way someone finds a source whose name they
			// know. Scrolling a list that long to reach the M's is not a test of anything.
			onAllNodes(hasSetTextAction()).onFirst().performTextInput("MangaDex")
			waitForIdle()

			// On a fresh profile nothing is enabled, so the picker's default filter finds nothing
			// and offers the full catalogue instead. Following that offer is the first-run path,
			// and this test failing here is what found it: the screen used to say "no sources
			// match", which is not what had happened.
			onNodeWithText("Show all sources").performClick()
			awaitTag(SOURCE_ROW_TAG, "the MangaDex row, after showing all sources")

			// Enable it, then open it. A fresh profile has no sources on, which is the state this
			// has to work from.
			onAllNodesWithTag(SOURCE_TOGGLE_TAG).onFirst().performClick()
			waitForIdle()
			onAllNodesWithTag(SOURCE_ROW_TAG).onFirst().performClick()

			awaitTag(MANGA_CARD_TAG, "a listing from MangaDex", NETWORK_WAIT_MS)
			onAllNodesWithTag(MANGA_CARD_TAG).onFirst().performClick()

			awaitTag(CHAPTER_ROW_TAG, "a chapter list from MangaDex", NETWORK_WAIT_MS)
			onAllNodesWithTag(CHAPTER_ROW_TAG).onFirst().performClick()

			assertEquals(
				1,
				awaitPageOnScreen(NETWORK_WAIT_MS),
				"a live chapter should open on page 1, with that page fetched and decoded",
			)
		} finally {
			app.close()
		}
	}

	/**
	 * Wait for at least one node carrying [tag], naming what is being waited for.
	 *
	 * The description matters more than it looks. Without it the failure reads `Condition still
	 * not satisfied after 30000ms`, which names neither the screen nor the step -- and this test
	 * has eleven waits in it.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.awaitTag(tag: String, what: String, timeoutMs: Long = WAIT_MS) {
		waitUntil(what, timeoutMs) {
			onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
		}
	}

	/**
	 * The page actually on screen, from the artwork's own content description.
	 *
	 * *Not* the page counter, which is the obvious probe and the wrong one: the reader's chrome
	 * auto-hides 2.5 seconds after the last interaction, and a test clock crosses 2.5 seconds
	 * between one assertion and the next. Probing the HUD therefore reads "no reader at all" a
	 * moment after a page turn -- which is how this test first failed, on correct app behaviour.
	 *
	 * The content description is better than a workaround, though. `ReaderPageImage` sets it only
	 * on the decoded image: a page still loading is a spinner with no description, and a page that
	 * failed is a text placeholder with none either. So waiting for it asserts something the
	 * counter never could -- that the page is resolved and drawn, not merely numbered.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.awaitPageOnScreen(timeoutMs: Long = WAIT_MS): Int {
		waitUntil("a decoded page to be on screen", timeoutMs) {
			onAllNodesWithContentDescription(PAGE_LABEL, substring = true)
				.fetchSemanticsNodes().size == 1
		}
		val label = onAllNodesWithContentDescription(PAGE_LABEL, substring = true)
			.fetchSemanticsNodes().single()
			.config[SemanticsProperties.ContentDescription].first()
		return label.removePrefix(PAGE_LABEL).trim().toInt()
	}

	/**
	 * A key-down event the reader's handler will accept.
	 *
	 * Compose Desktop's own factory, not a hand-built AWT event. `KeyEvent` is a value class over
	 * a platform type that is Skiko's, not AWT's, so wrapping a `java.awt.event.KeyEvent` in it
	 * compiles and then throws `ClassCastException` the moment anything reads `.key` -- which is
	 * how this test first failed. The public AWT bridge, `toComposeEvent`, is `internal`.
	 *
	 * That leaves the factory, which is `@InternalComposeUiApi`: supported only between compose-ui
	 * modules on the exact same version. Ageha pins one Compose version for the whole build, so
	 * that condition holds here, and the blast radius if it ever stops holding is this one function
	 * in a test -- it cannot reach the shipped app. Accepted on those terms rather than because the
	 * annotation was ignored.
	 *
	 * Going through the real event type is the point: the page turns below travel the window's
	 * actual key path, through [KeyRouter] and `ReaderKeys`, rather than calling the view model
	 * directly and calling that a keystroke.
	 */
	@OptIn(InternalComposeUiApi::class)
	private fun keyDown(key: Key) = KeyEvent(key = key, type = KeyEventType.KeyDown)
}
