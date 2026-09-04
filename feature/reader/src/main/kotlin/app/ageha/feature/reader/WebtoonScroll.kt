package app.ageha.feature.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

/**
 * Smooth wheel scrolling for the webtoon strip.
 *
 * Compose Desktop moves a lazy list by the whole wheel notch in a single frame. On a grid of
 * covers nobody notices; on a continuous strip of artwork, where the eye is tracking the page
 * rather than the list, every notch is a jump-cut. This spreads one notch across a handful of
 * frames instead.
 *
 * Deliberately *not* a fling. A fling keeps moving after the input stops, which for a reader means
 * overshooting the panel you were scrolling towards. This only ever travels the distance the wheel
 * actually asked for -- it changes when those pixels arrive, never how many.
 */

/**
 * How much of [pending] to travel in a frame that lasted [deltaMillis].
 *
 * Exponential ease-out: a fixed *fraction* of what is left each millisecond, which is why the
 * result is frame-rate independent rather than "a bit faster on a 144Hz monitor". Returns the
 * whole remainder once the tail would fall below a pixel, because an asymptote never arrives and a
 * scroll animation that never formally ends holds the list's scroll mutex forever.
 */
fun scrollStep(pending: Float, deltaMillis: Float): Float {
	if (pending == 0f) return 0f
	// A frame that took 300ms means the app was busy, not that the user asked for a 300ms-long
	// jump. Clamping keeps a stall from teleporting the strip.
	val dt = deltaMillis.coerceIn(0f, MAX_FRAME_MS)
	val step = pending * (1f - exp(-dt / SCROLL_TIME_CONSTANT_MS))
	return if (abs(pending - step) < SETTLE_EPSILON_PX) pending else step
}

/**
 * Drains accumulated wheel deltas into [listState] across frames.
 *
 * Wheel events arrive far faster than frames do, so they are summed into one pending distance and
 * a single animation job drains it. Notches that land mid-animation extend the current travel
 * rather than starting a competing one, which is what makes a fast flick feel like one long scroll
 * instead of several stuttering ones.
 */
class SmoothScroller(
	private val listState: LazyListState,
	private val scope: CoroutineScope,
) {

	private var pending = 0f
	private var job: Job? = null

	/** Queue [delta] pixels of travel. Positive scrolls towards the end of the chapter. */
	fun scrollBy(delta: Float) {
		pending += delta
		if (job?.isActive == true) return
		job = scope.launch {
			// `scroll` takes the list's scroll mutex, so a drag on the scrollbar or a
			// `scrollToItem` from position restore cancels this rather than fighting it.
			listState.scroll {
				var last = withFrameNanos { it }
				while (pending != 0f) {
					val now = withFrameNanos { it }
					val elapsed = (now - last) / NANOS_PER_MILLI
					last = now
					val step = scrollStep(pending, elapsed)
					val consumed = scrollBy(step)
					pending -= step
					// The list refused part of the step, so it is at one end of the chapter.
					// Keeping the remainder would leave the strip pinned there, silently
					// swallowing the next notch in the opposite direction.
					if (abs(consumed) < abs(step) - SETTLE_EPSILON_PX) pending = 0f
				}
			}
		}
	}

	private companion object {
		const val NANOS_PER_MILLI = 1_000_000f
	}
}

@Composable
fun rememberSmoothScroller(listState: LazyListState, scope: CoroutineScope): SmoothScroller =
	remember(listState, scope) { SmoothScroller(listState, scope) }

/**
 * The easing time constant, in milliseconds.
 *
 * At 60Hz this covers about 90% of a notch in four frames. Slower reads as lag on a mouse wheel;
 * faster is indistinguishable from the jump it replaces.
 */
private const val SCROLL_TIME_CONSTANT_MS = 28f

/** Below this the remaining travel is finished outright rather than eased into. */
private const val SETTLE_EPSILON_PX = 0.5f

/** Longest frame the easing will honour; anything above this was a stall. */
private const val MAX_FRAME_MS = 32f
