package app.ageha.desktop

import app.ageha.core.model.AgehaManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Navigation, which on a desktop is a different shape from a phone's.
 *
 * The behaviour under test is that the two sections keep *separate* back stacks. Flicking to the
 * library and back is something a desktop user does constantly, and a single global stack would
 * mean each round trip dumps them at the root of wherever they came from.
 */
class NavigatorTest {

	private fun manga(id: Long, source: String = "TEST") = AgehaManga(
		id = id,
		title = "Manga $id",
		altTitles = emptySet(),
		url = "/m/$id",
		publicUrl = "https://test/m/$id",
		rating = null,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = null,
		sourceName = source,
	)

	@Test
	fun `each section starts at its own root`() {
		val navigator = Navigator()
		assertEquals(Section.LIBRARY, navigator.section)
		assertEquals(Destination.Library, navigator.current)
		assertFalse(navigator.canGoBack)

		navigator.switchTo(Section.EXPLORE)
		assertEquals(Destination.Sources, navigator.current)
		assertFalse(navigator.canGoBack)
	}

	@Test
	fun `switching sections does not disturb the other section's stack`() {
		val navigator = Navigator()
		navigator.openManga(manga(1))
		assertTrue(navigator.canGoBack)

		navigator.switchTo(Section.EXPLORE)
		navigator.push(Destination.Browse("MANGADEX"))
		assertEquals(Destination.Browse("MANGADEX"), navigator.current)

		// Back in the library, still on the manga we left open.
		navigator.switchTo(Section.LIBRARY)
		assertEquals(Destination.Details(manga(1)), navigator.current)

		// And Explore is still where we left it.
		navigator.switchTo(Section.EXPLORE)
		assertEquals(Destination.Browse("MANGADEX"), navigator.current)
	}

	@Test
	fun `back pops only within the current section`() {
		val navigator = Navigator()
		navigator.openManga(manga(1))
		navigator.switchTo(Section.EXPLORE)

		// Explore is at its root, so there is nothing to pop -- and crucially this must not reach
		// into the library's stack.
		assertFalse(navigator.back())
		navigator.switchTo(Section.LIBRARY)
		assertTrue(navigator.back())
		assertEquals(Destination.Library, navigator.current)
	}

	/**
	 * Double-clicking a cover in a grid is easy to do by accident. Two identical entries would
	 * mean two presses of Escape to get back out, which reads as the key not working.
	 */
	@Test
	fun `pushing the current destination twice does not stack it`() {
		val navigator = Navigator()
		navigator.openManga(manga(7))
		navigator.openManga(manga(7))
		assertTrue(navigator.back())
		assertEquals(Destination.Library, navigator.current)
	}

	/**
	 * Ids are only unique *within* a source. Two sources' manga #7 are different manga, and
	 * treating them as the same destination would show the wrong one.
	 */
	@Test
	fun `the same id from two sources are two destinations`() {
		val navigator = Navigator()
		navigator.openManga(manga(7, source = "A"))
		navigator.openManga(manga(7, source = "B"))
		assertEquals(Destination.Details(manga(7, source = "B")), navigator.current)
		assertTrue(navigator.back())
		assertEquals(Destination.Details(manga(7, source = "A")), navigator.current)
	}

	@Test
	fun `opening a source jumps to explore and lands on its listing`() {
		val navigator = Navigator()
		navigator.openSource("WEEBCENTRAL")
		assertEquals(Section.EXPLORE, navigator.section)
		assertEquals(Destination.Browse("WEEBCENTRAL"), navigator.current)
		assertTrue(navigator.canGoBack)
	}

	@Test
	fun `resetting returns to the section root without touching the other`() {
		val navigator = Navigator()
		navigator.openManga(manga(1))
		navigator.switchTo(Section.EXPLORE)
		navigator.push(Destination.Browse("X"))
		navigator.push(Destination.Details(manga(2)))
		navigator.resetToRoot()

		assertEquals(Destination.Sources, navigator.current)
		navigator.switchTo(Section.LIBRARY)
		assertEquals(Destination.Details(manga(1)), navigator.current)
	}
}
