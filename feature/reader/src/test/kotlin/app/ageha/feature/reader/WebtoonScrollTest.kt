package app.ageha.feature.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The easing behind smooth webtoon scrolling.
 *
 * Worth testing away from Compose because two of its properties are not obvious from reading it
 * and both are ones a plausible-looking rewrite would break: it must converge in finite time (an
 * exponential decay does not, and a scroll animation that never ends holds the lazy list's scroll
 * mutex forever), and it must travel the same distance per millisecond regardless of frame rate,
 * or the strip scrolls further on a 144Hz monitor than on a 60Hz one.
 */
class WebtoonScrollTest {

	@Test
	fun `nothing pending means nothing to travel`() {
		assertEquals(0f, scrollStep(pending = 0f, deltaMillis = 16f))
	}

	@Test
	fun `a step moves towards the target without overshooting it`() {
		val step = scrollStep(pending = 100f, deltaMillis = 16f)
		assertTrue(step > 0f, "a positive remainder must travel forwards, was $step")
		assertTrue(step <= 100f, "a step must never overshoot the remainder, was $step")
	}

	@Test
	fun `direction follows the sign of the remainder`() {
		assertTrue(scrollStep(pending = -100f, deltaMillis = 16f) < 0f)
	}

	/**
	 * The frame-rate independence property, stated as an experiment rather than as arithmetic:
	 * draining the same distance in 16ms frames and in 8ms frames must cover the same ground per
	 * millisecond, not per frame.
	 */
	@Test
	fun `total travel over a fixed duration does not depend on frame rate`() {
		fun drain(frameMillis: Float, frames: Int): Float {
			var pending = 1000f
			repeat(frames) { pending -= scrollStep(pending, frameMillis) }
			return 1000f - pending
		}

		val at60Hz = drain(frameMillis = 16f, frames = 6)
		val at120Hz = drain(frameMillis = 8f, frames = 12)
		assertTrue(
			abs(at60Hz - at120Hz) < 1f,
			"96ms of scrolling should cover the same distance at either refresh rate, " +
				"got $at60Hz and $at120Hz",
		)
	}

	/**
	 * The convergence property. An exponential approach never formally arrives, so the last
	 * fraction of a pixel is taken in one go -- otherwise the drain loop in [SmoothScroller] never
	 * exits and the list stays locked against every other scroll source.
	 */
	@Test
	fun `the last fraction of a pixel is finished outright`() {
		var pending = 400f
		var frames = 0
		while (pending != 0f) {
			pending -= scrollStep(pending, deltaMillis = 16f)
			frames++
			if (frames > 1_000) break
		}
		assertEquals(0f, pending, "the easing must reach zero exactly")
		assertTrue(frames < 60, "one notch should settle in a few frames, took $frames")
	}

	/**
	 * A frame that took a third of a second means the app stalled, not that the user asked for a
	 * third of a second of travel. Without the clamp, one stutter teleports the strip.
	 */
	@Test
	fun `a stalled frame does not teleport the strip`() {
		val afterStall = scrollStep(pending = 1000f, deltaMillis = 300f)
		val afterLongestHonouredFrame = scrollStep(pending = 1000f, deltaMillis = 32f)
		assertEquals(afterLongestHonouredFrame, afterStall)
	}
}
