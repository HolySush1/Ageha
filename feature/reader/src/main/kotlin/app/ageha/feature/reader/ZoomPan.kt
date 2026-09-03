package app.ageha.feature.reader

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import app.ageha.core.model.PageScale
import kotlin.math.max

/**
 * Zoom and pan for one page.
 *
 * Written for a mouse and keyboard rather than adapted from touch. On a desktop the gestures are
 * Ctrl+wheel to zoom, drag to pan, double-click to toggle between fit and 1:1 -- and the pan is
 * *clamped* so a page can never be dragged off screen, which is the single most annoying thing an
 * unclamped zoom does.
 */
class ZoomPanState {

	var scale by mutableFloatStateOf(1f)
		private set

	var offset by mutableStateOf(Offset.Zero)
		private set

	private var viewport = Size.Zero
	private var content = Size.Zero

	val isZoomed: Boolean get() = scale > 1.001f

	fun onViewportChanged(size: IntSize) {
		viewport = Size(size.width.toFloat(), size.height.toFloat())
		clamp()
	}

	fun onContentChanged(size: Size) {
		content = size
		clamp()
	}

	/**
	 * Zoom about a point, so the pixel under the cursor stays under the cursor.
	 *
	 * Zooming about the centre instead is the easy implementation and it is wrong: the reader
	 * points at the panel they want to read, and centre-zoom moves it away from them.
	 */
	fun zoomBy(factor: Float, focus: Offset) {
		val target = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
		if (target == scale) return
		val ratio = target / scale
		offset = (offset + focus) * ratio - focus
		scale = target
		clamp()
	}

	fun panBy(delta: Offset) {
		offset -= delta
		clamp()
	}

	fun reset() {
		scale = 1f
		offset = Offset.Zero
	}

	/** Double-click toggles between fitted and a fixed magnification, about the click point. */
	fun toggleZoom(focus: Offset) {
		if (isZoomed) reset() else zoomBy(DOUBLE_CLICK_SCALE, focus)
	}

	/**
	 * Keep the scaled content covering the viewport.
	 *
	 * When the content is smaller than the viewport on an axis it is centred on that axis rather
	 * than pinned to an edge; when it is larger, the offset is bounded so no gap can appear.
	 */
	private fun clamp() {
		if (viewport == Size.Zero) return
		val scaledWidth = content.width * scale
		val scaledHeight = content.height * scale
		val maxX = max(0f, (scaledWidth - viewport.width) / 2f)
		val maxY = max(0f, (scaledHeight - viewport.height) / 2f)
		offset = Offset(
			x = offset.x.coerceIn(-maxX, maxX),
			y = offset.y.coerceIn(-maxY, maxY),
		)
	}

	companion object {
		const val MIN_SCALE = 1f

		/** Beyond this a scan is just enlarged noise; the source image has no more detail in it. */
		const val MAX_SCALE = 8f

		const val DOUBLE_CLICK_SCALE = 2.5f

		/** One wheel notch. Small enough to feel continuous, large enough to be worth doing. */
		const val WHEEL_ZOOM_STEP = 1.15f
	}
}

@Composable
fun rememberZoomPanState(vararg keys: Any?): ZoomPanState = remember(*keys) { ZoomPanState() }

/**
 * Applies [state] to a page, and wires the desktop input for it.
 *
 * [onScrollFallthrough] receives wheel events that are *not* zooming and that the page cannot use
 * itself -- when it is not zoomed, or is already at the edge in the wheel's direction. That is
 * what lets the wheel turn pages in paged mode while still panning a zoomed-in page, without the
 * two fighting each other.
 */
@Composable
fun Modifier.zoomPan(
	state: ZoomPanState,
	scale: PageScale,
	enabled: Boolean = true,
    onScrollFallthrough: (delta: Float) -> Unit = {},
    onTap: () -> Unit = {},
): Modifier {
	if (!enabled) return this
	return this
		.onSizeChanged(state::onViewportChanged)
		.pointerInput(state, scale) {
			awaitPointerEventScope {
				while (true) {
					val event = awaitPointerEvent()
					if (event.type != PointerEventType.Scroll) continue
					val change = event.changes.firstOrNull() ?: continue
					val delta = change.scrollDelta.y
					if (delta == 0f) continue
					if (event.keyboardModifiers.isCtrlPressed) {
						// Ctrl+wheel zooms. Scroll deltas are positive downward, and down should
						// zoom out, hence the inversion.
						val factor = if (delta < 0) {
							ZoomPanState.WHEEL_ZOOM_STEP
						} else {
							1f / ZoomPanState.WHEEL_ZOOM_STEP
						}
						state.zoomBy(factor, change.position - Offset(size.width / 2f, size.height / 2f))
						change.consume()
					} else if (state.isZoomed) {
						state.panBy(Offset(0f, -delta * WHEEL_PAN_PIXELS))
						change.consume()
					} else {
						onScrollFallthrough(delta)
						change.consume()
					}
				}
			}
		}
		.pointerInput(state) {
			detectDragGestures { change, dragAmount ->
				if (state.isZoomed) {
					state.panBy(dragAmount)
					change.consume()
				}
			}
		}
		.pointerInput(state) {
			detectTapGestures(
				onDoubleTap = { position ->
					state.toggleZoom(position - Offset(size.width / 2f, size.height / 2f))
				},
				onTap = { onTap() },
			)
		}
		.graphicsLayer {
			scaleX = state.scale
			scaleY = state.scale
			translationX = -state.offset.x
			translationY = -state.offset.y
		}
}

/** How far one wheel notch pans a zoomed page. */
private const val WHEEL_PAN_PIXELS = 60f

/** Content scaling for a page, from the user's [PageScale] choice. */
fun PageScale.toContentScale(): androidx.compose.ui.layout.ContentScale = when (this) {
	PageScale.FIT_PAGE -> androidx.compose.ui.layout.ContentScale.Fit
	PageScale.FIT_WIDTH -> androidx.compose.ui.layout.ContentScale.FillWidth
	PageScale.FIT_HEIGHT -> androidx.compose.ui.layout.ContentScale.FillHeight
	// `None` is one image pixel per screen pixel, which is exactly what "original size" means and
	// is why it is not `Inside` -- `Inside` would shrink a large scan to fit, defeating the point.
	PageScale.ORIGINAL -> androidx.compose.ui.layout.ContentScale.None
}
