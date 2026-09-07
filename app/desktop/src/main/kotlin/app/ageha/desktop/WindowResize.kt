package app.ageha.desktop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Window

/**
 * The eight edges an undecorated window has to grow back.
 *
 * Removing the native caption removes the whole non-client area with it, and the resize border is
 * part of that. Without this a user can drag the window and maximise it and never once make it
 * narrower -- not a cosmetic loss on an app whose signature view is a grid that reflows.
 *
 * ## Why it drives the AWT window rather than `WindowState`
 *
 * Two coordinate spaces, and only one of them is unambiguous. `WindowState` is in `Dp`, so a resize
 * written against it converts every pointer delta through the window's density -- while the pointer
 * position AWT reports is already in the same user-space pixels as `window.bounds`. Going through
 * `Dp` means converting out and back for no gain, and getting it wrong on a scaled display makes
 * the window run away from the cursor. Compose's `Window` listens to the AWT component and folds
 * native bounds changes back into `WindowState`, so setting bounds here keeps that state correct
 * without this file having to know about it.
 *
 * ## Why the deltas are absolute rather than accumulated
 *
 * Each drag event recomputes from where the pointer was when the drag *started*, against the bounds
 * the window had then. The obvious alternative -- adding each frame's delta to the current bounds --
 * drifts, because the handle being dragged is itself moving as the window resizes, so every frame
 * measures against a component that has already shifted underneath it. Anchoring to the start makes
 * the arithmetic idempotent: the window ends up exactly as big as the total pointer travel says,
 * however many events that took.
 */
@Composable
fun BoxScope.WindowResizeHandles(window: Window, enabled: Boolean) {
	if (!enabled) return
	Box(Modifier.fillMaxSize()) {
		// Edges first, corners second. A corner grab is 14dp square and an edge is 4dp deep, so
		// the corner has to be composed later to win the overlap -- otherwise the last stretch of
		// each edge resizes on one axis while the pointer is clearly on a diagonal.
		ResizeEdge(window, Alignment.CenterStart, Cursor.W_RESIZE_CURSOR, Modifier.fillMaxHeight().width(EDGE), left = true)
		ResizeEdge(window, Alignment.CenterEnd, Cursor.E_RESIZE_CURSOR, Modifier.fillMaxHeight().width(EDGE), right = true)
		ResizeEdge(window, Alignment.TopCenter, Cursor.N_RESIZE_CURSOR, Modifier.fillMaxWidth().height(EDGE), top = true)
		ResizeEdge(window, Alignment.BottomCenter, Cursor.S_RESIZE_CURSOR, Modifier.fillMaxWidth().height(EDGE), bottom = true)

		ResizeEdge(window, Alignment.TopStart, Cursor.NW_RESIZE_CURSOR, Modifier.size(CORNER), top = true, left = true)
		ResizeEdge(window, Alignment.TopEnd, Cursor.NE_RESIZE_CURSOR, Modifier.size(CORNER), top = true, right = true)
		ResizeEdge(window, Alignment.BottomStart, Cursor.SW_RESIZE_CURSOR, Modifier.size(CORNER), bottom = true, left = true)
		ResizeEdge(window, Alignment.BottomEnd, Cursor.SE_RESIZE_CURSOR, Modifier.size(CORNER), bottom = true, right = true)
	}
}

/** How deep an edge grab is. Thin enough that it never steals a click meant for the content. */
private val EDGE = 4.dp

/**
 * How big a corner grab is.
 *
 * Larger than the edge on purpose, and it is the number that decides whether resizing feels
 * possible or fiddly: a corner is where people reach to resize both axes at once, and a 4dp square
 * is a target most pointers miss. Windows' own corner grip is about this size.
 */
private val CORNER = 14.dp

@Composable
private fun BoxScope.ResizeEdge(
	window: Window,
	alignment: Alignment,
	cursor: Int,
	modifier: Modifier,
	top: Boolean = false,
	bottom: Boolean = false,
	left: Boolean = false,
	right: Boolean = false,
) {
	Box(
		modifier
			.align(alignment)
			.pointerHoverIcon(PointerIcon(Cursor(cursor)))
			.pointerInput(window, top, bottom, left, right) {
				var origin: Rectangle? = null
				var anchorX = 0
				var anchorY = 0
				detectDragGestures(
					onDragStart = {
						origin = Rectangle(window.bounds)
						MouseInfo.getPointerInfo()?.location?.let {
							anchorX = it.x
							anchorY = it.y
						}
					},
					onDragEnd = { origin = null },
					onDragCancel = { origin = null },
				) { change, _ ->
					change.consume()
					val start = origin ?: return@detectDragGestures
					val now = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
					val dx = now.x - anchorX
					val dy = now.y - anchorY

					var x = start.x
					var y = start.y
					var w = start.width
					var h = start.height

					// Dragging a leading edge moves the origin and shrinks the extent by the same
					// amount, so the opposite edge stays put. Clamping the extent at the minimum
					// has to clamp the origin with it, or the window keeps sliding after it has
					// stopped shrinking.
					if (left) {
						w = (start.width - dx).coerceAtLeast(MIN_WIDTH)
						x = start.x + (start.width - w)
					}
					if (top) {
						h = (start.height - dy).coerceAtLeast(MIN_HEIGHT)
						y = start.y + (start.height - h)
					}
					if (right) w = (start.width + dx).coerceAtLeast(MIN_WIDTH)
					if (bottom) h = (start.height + dy).coerceAtLeast(MIN_HEIGHT)

					window.setBounds(x, y, w, h)
					// The Skia surface is sized from the peer, which does not lay out again on its
					// own mid-drag. Without this the frame moves and the content trails a frame
					// behind it, which reads as tearing rather than as resizing.
					window.validate()
				}
			},
	)
}

/**
 * The smallest useful window, in AWT pixels.
 *
 * Not arbitrary: below roughly this width the library grid falls to two columns and the reader's
 * control pill starts wrapping, so anything smaller is a window in which Ageha does not work rather
 * than one that is merely small.
 */
private const val MIN_WIDTH = 880
private const val MIN_HEIGHT = 560
