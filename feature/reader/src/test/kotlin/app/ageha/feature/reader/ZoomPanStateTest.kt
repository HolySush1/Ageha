package app.ageha.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Zoom and pan on a single page.
 *
 * The case that matters here is the one nothing exercised: `onContentChanged` was never called
 * from anywhere in the app, because the drawn size of a page inside an `AsyncImage` is not
 * something the gesture modifier is ever told. The content size therefore stayed zero, both pan
 * bounds computed as zero, and **every drag was clamped straight back to the origin**. Zoom
 * worked; panning silently did nothing at all. Nothing failed, which is why it survived: the only
 * way to catch it is to assert that a pan actually moves something.
 */
class ZoomPanStateTest {

	private fun zoomed(): ZoomPanState = ZoomPanState().apply {
		onViewportChanged(IntSize(1000, 800))
		zoomBy(2f, Offset.Zero)
	}

	@Test
	fun `an unzoomed page cannot be panned off centre`() {
		val state = ZoomPanState().apply { onViewportChanged(IntSize(1000, 800)) }
		state.panBy(Offset(200f, 200f))
		assertEquals(Offset.Zero, state.offset, "at 1:1 there is nothing off screen to pan to")
	}

	@Test
	fun `a zoomed page pans even when the content size was never reported`() {
		val state = zoomed()
		state.panBy(Offset(100f, 0f))
		assertTrue(
			state.offset.x != 0f,
			"a zoomed page must pan; the viewport fallback is what makes this possible",
		)
	}

	@Test
	fun `panning stops at the edge of the scaled page`() {
		val state = zoomed()
		// Far further than the page can travel: at 2x on a 1000x800 viewport the bounds are half
		// the viewport in each direction.
		state.panBy(Offset(10_000f, 10_000f))
		assertEquals(-500f, state.offset.x, "x must stop at (width * scale - width) / 2")
		assertEquals(-400f, state.offset.y, "y must stop at (height * scale - height) / 2")
	}

	@Test
	fun `a reported content size takes precedence over the viewport`() {
		val state = ZoomPanState().apply {
			onViewportChanged(IntSize(1000, 800))
			// A page letterboxed into a wide window: narrower than the viewport it sits in.
			onContentChanged(Size(400f, 800f))
			zoomBy(2f, Offset.Zero)
		}
		state.panBy(Offset(10_000f, 0f))
		assertEquals(
			0f,
			state.offset.x,
			"800px of scaled content in a 1000px viewport has nothing off screen horizontally",
		)
	}

	@Test
	fun `zooming back out recentres a page that was panned to its edge`() {
		val state = zoomed()
		state.panBy(Offset(10_000f, 10_000f))
		state.reset()
		assertEquals(Offset.Zero, state.offset)
		assertTrue(!state.isZoomed)
	}

	@Test
	fun `zoom is bounded at both ends`() {
		val state = ZoomPanState().apply { onViewportChanged(IntSize(1000, 800)) }
		repeat(100) { state.zoomBy(2f, Offset.Zero) }
		assertEquals(ZoomPanState.MAX_SCALE, state.scale)
		repeat(100) { state.zoomBy(0.5f, Offset.Zero) }
		assertEquals(ZoomPanState.MIN_SCALE, state.scale)
	}
}
