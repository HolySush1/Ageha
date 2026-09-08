package app.ageha.core.designsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The two pieces of motion logic that are not just a number.
 *
 * Everything else in the motion layer is a constant or a Compose spec, and asserting that
 * `QUICK_MS` is 140 is a test of a copy-paste. These two make *decisions*, and both were written
 * because the obvious version of them is wrong in a way you would not notice for months.
 */
class MotionTokensTest {

	// ------------------------------------------------------------------ the stagger gate

	/**
	 * A list's entrance staggers by position -- for the items that are part of the entrance.
	 *
	 * This is the behaviour the naive `index * STAGGER_MS` also has, and it is the half that is
	 * correct. The two tests after it are the half that is not.
	 */
	@Test
	@DisplayName("items in the first screenful stagger by their position")
	fun staggersByIndex() {
		val gate = StaggerGate(System.nanoTime())
		assertEquals(0, gate.delayFor(0), "the first item waits for nothing")
		assertEquals(AgehaMotion.STAGGER_MS, gate.delayFor(1))
		assertEquals(AgehaMotion.STAGGER_MS * 5, gate.delayFor(5))
	}

	/**
	 * Nothing past the cap waits at all.
	 *
	 * A lazy grid composes an item when it is scrolled into view, so `index * 18ms` would hand
	 * item four hundred a seven-second delay -- and the user would meet it as a blank cell that
	 * filled in long after they stopped scrolling. Note that the answer is **zero**, not "the
	 * capped delay": clamping would still make every item arriving from a scroll wait a fixed
	 * 288ms, which is the same bug with a smaller number.
	 */
	@Test
	@DisplayName("items past the cap wait for nothing at all, rather than for the capped delay")
	fun cappedItemsDoNotWait() {
		val gate = StaggerGate(System.nanoTime())
		assertEquals(0, gate.delayFor(AgehaMotion.STAGGER_LIMIT))
		assertEquals(0, gate.delayFor(400), "item 400 is a scroll, not an entrance")
	}

	/**
	 * The gate shuts on a clock, and this is the reason it exists at all.
	 *
	 * The distinction that matters is not *which* item it is, it is *when* it composed. An item
	 * that composes a minute after the list appeared composed because somebody scrolled back up to
	 * it, and re-running its entrance there is a stutter rather than a flourish -- so even item
	 * zero gets no delay once the window has passed.
	 */
	@Test
	@DisplayName("a list that appeared long ago no longer staggers anything, index 0 included")
	fun theWindowShuts() {
		val longAgo = System.nanoTime() - TimeUnit.SECONDS.toNanos(60)
		val gate = StaggerGate(longAgo)
		assertEquals(0, gate.delayFor(0))
		assertEquals(0, gate.delayFor(3), "scrolling back to the top is not an entrance")
	}

	/**
	 * The entrance window outlasts the entrance.
	 *
	 * If the window were shorter than the stagger it gates, the last few items of a list would
	 * lose their delay partway through the animation and snap in ahead of the ones before them --
	 * a stagger that visibly gives up. The transition duration is added on top because a screen
	 * slides in *before* its list starts arriving.
	 */
	@Test
	@DisplayName("the window is long enough to cover the stagger it gates")
	fun windowCoversTheStagger() {
		val fullStagger = AgehaMotion.STAGGER_LIMIT * AgehaMotion.STAGGER_MS
		val gate = StaggerGate(System.nanoTime())
		assertEquals(
			fullStagger - AgehaMotion.STAGGER_MS,
			gate.delayFor(AgehaMotion.STAGGER_LIMIT - 1),
			"the last staggered item should still be inside the window",
		)
	}

	// ------------------------------------------------------------------ the preference

	@Test
	@DisplayName("following the system means following the system, in both directions")
	fun systemFollowsTheSystem() {
		assertTrue(MotionPreference.SYSTEM.isEnabled(systemAllowsMotion = true))
		assertFalse(MotionPreference.SYSTEM.isEnabled(systemAllowsMotion = false))
	}

	/**
	 * An explicit choice overrides Windows, which is the only reason the row exists.
	 *
	 * Someone who has animation off system-wide but wants it in this one application has to be
	 * able to say so, and someone who has it on system-wide but finds this application's motion
	 * uncomfortable has to be able to say the opposite. A setting that only ever agreed with the
	 * OS would be a label rather than a control.
	 */
	@Test
	@DisplayName("an explicit choice wins over what Windows says")
	fun explicitChoiceOverrides() {
		assertTrue(
			MotionPreference.FULL.isEnabled(systemAllowsMotion = false),
			"FULL should animate even where Windows has animation turned off",
		)
		assertFalse(
			MotionPreference.REDUCED.isEnabled(systemAllowsMotion = true),
			"REDUCED should stay still even where Windows is happy to animate",
		)
	}

	/**
	 * The default follows the system rather than picking a side.
	 *
	 * Pinned because it is a decision rather than an accident: Windows has already asked this
	 * question, and an application that ignores the answer makes its user give it twice.
	 */
	@Test
	@DisplayName("the default is to follow Windows")
	fun defaultFollowsWindows() {
		assertEquals(MotionPreference.SYSTEM, MotionPreference.entries.first())
	}
}
