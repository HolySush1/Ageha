package app.ageha.feature.reader

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How wide the webtoon strip is drawn, and specifically that Ctrl+wheel always does something.
 *
 * This exists because of a regression that was shipped and reported: the strip width was clamped
 * to the window, so on a window no wider than the source page, a run of zoom levels all resolved
 * to the same width. Ctrl+wheel appeared dead -- the setting was changing, the picture was not.
 *
 * The clamp is gone, and these are the properties that say so. Note what is *not* a parameter of
 * [webtoonStripWidth]: the viewport. A width that depends on the window is a width that can be
 * clamped by the window, and the whole defect was that clamp.
 */
class WebtoonStripWidthTest {

	private val source = 800.dp

	@Test
	fun `zoom of one is the source's own width`() {
		assertEquals(source, webtoonStripWidth(source, 1f))
	}

	/**
	 * The regression, stated directly. Every notch of the wheel across the whole supported range
	 * must change the width -- including well past the point where a window would have clipped it.
	 */
	@Test
	fun `every zoom step changes the width`() {
		var zoom = 0.25f
		var previous = webtoonStripWidth(source, zoom)
		var steps = 0
		while (zoom < 6f) {
			zoom = (zoom * 1.15f).coerceAtMost(6f)
			val width = webtoonStripWidth(source, zoom)
			assertTrue(
				width > previous,
				"zoom $zoom produced $width, no wider than the previous $previous -- a notch of " +
					"the wheel that changes nothing is a control that reads as broken",
			)
			previous = width
			steps++
		}
		assertTrue(steps > 10, "the range should take more than a handful of notches, took $steps")
	}

	/**
	 * The case the clamp got wrong: a source page at least as wide as the window. Under the old
	 * code every one of these collapsed to the window width.
	 */
	@Test
	fun `a source wider than the window still zooms`() {
		val wide = 1_920.dp
		assertTrue(webtoonStripWidth(wide, 1.15f) > webtoonStripWidth(wide, 1f))
		assertTrue(webtoonStripWidth(wide, 1f) > webtoonStripWidth(wide, 0.87f))
	}

	@Test
	fun `zooming in magnifies past any window`() {
		assertTrue(
			webtoonStripWidth(source, 6f) > 4_000.dp,
			"the point of zooming in is to get bigger than the screen, not to fill it",
		)
	}

	@Test
	fun `zooming all the way out still leaves something on screen`() {
		assertTrue(webtoonStripWidth(source, 0.001f) >= 160.dp)
	}
}
