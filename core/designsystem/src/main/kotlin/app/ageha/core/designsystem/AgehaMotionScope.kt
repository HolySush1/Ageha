package app.ageha.core.designsystem

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity

/**
 * The one switch that turns Ageha's motion off, and the helpers that make it reachable.
 *
 * ## Why a switch exists at all
 *
 * Animation is the one part of an interface that is actively harmful to some of the people using
 * it. Vestibular disorders are not rare, and a grid of covers that springs into place is exactly
 * the sort of motion that triggers them. Every platform with an accessibility story exposes a
 * "reduce motion" preference for this reason -- and Compose Desktop exposes nothing at all, so
 * Ageha has to carry its own. See [MotionPreference] and the desktop app's `SystemMotion`, which
 * reads the Windows setting so the default answer is the one the user already gave their OS.
 *
 * ## Why the helpers are shaped like this
 *
 * Every animation spec in the application is built by one of the functions below, and each of them
 * collapses to an **instant** spec when the switch is off. That is what makes the switch actually
 * work: a call site written as `tween(140)` is a call site the switch cannot reach, and one such
 * call site is all it takes for the setting to be a lie. `MotionThroughTokensTest` enforces this
 * by scanning for literal durations outside the design system.
 *
 * "Instant" is a zero-length spec rather than a skipped animation, deliberately. The state still
 * changes, the recomposition still happens, and callers need no branch -- only the interpolation
 * between the two values is removed, which is exactly what "reduce motion" asks for.
 */
val LocalMotionEnabled = staticCompositionLocalOf { true }

/** Whether animations run, for the rare caller that has to branch rather than pick a spec. */
val isMotionEnabled: Boolean
	@Composable get() = LocalMotionEnabled.current

/**
 * A duration-based spec, honouring the reduced-motion switch.
 *
 * For cross-fades, appearances and disappearances -- anything with no physical analogue, where a
 * spring would read as an unsteady hand rather than as weight.
 */
@Composable
fun <T> motionTween(
	durationMs: Int,
	delayMs: Int = 0,
	easing: Easing = AgehaMotion.standard,
): FiniteAnimationSpec<T> =
	if (LocalMotionEnabled.current) {
		tween(durationMillis = durationMs, delayMillis = delayMs, easing = easing)
	} else {
		snap()
	}

/**
 * The pointer-response spring: hover, press, the sliding selection indicator.
 *
 * [visibilityThreshold] is not optional for anything but `Float`. A spring animating `Dp` or
 * `IntOffset` with no threshold keeps running until it is within a ten-thousandth of its target,
 * which for a pixel offset is a long tail of invisible motion -- Compose supplies
 * `VisibilityThreshold` constants for exactly this, and the call sites here pass them.
 */
@Composable
fun <T> snappySpring(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
	if (LocalMotionEnabled.current) {
		spring(
			dampingRatio = AgehaMotion.SNAPPY_DAMPING,
			stiffness = AgehaMotion.SNAPPY_STIFFNESS,
			visibilityThreshold = visibilityThreshold,
		)
	} else {
		snap()
	}

/** The settle spring: a thing finding a new position, with no overshoot. See [snappySpring]. */
@Composable
fun <T> settleSpring(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
	if (LocalMotionEnabled.current) {
		spring(
			dampingRatio = AgehaMotion.SETTLE_DAMPING,
			stiffness = AgehaMotion.SETTLE_STIFFNESS,
			visibilityThreshold = visibilityThreshold,
		)
	} else {
		snap()
	}

/** [snappySpring] widened to [AnimationSpec], for APIs that ask for the looser type. */
@Composable
fun <T> snappyAnimation(visibilityThreshold: T? = null): AnimationSpec<T> =
	snappySpring(visibilityThreshold)

/** [settleSpring] widened to [AnimationSpec], for APIs that ask for the looser type. */
@Composable
fun <T> settleAnimation(visibilityThreshold: T? = null): AnimationSpec<T> =
	settleSpring(visibilityThreshold)

/**
 * How long an item waits before its entrance, for one appearance of one list.
 *
 * ## Why this is a shared object rather than a function of the index
 *
 * The obvious implementation is `delay = index * STAGGER_MS`, and it is wrong in a lazy list. A
 * `LazyVerticalGrid` composes an item when it is *scrolled into view*, not when the list appears,
 * so item four hundred would compose with a seven-second delay and the user would meet it as a
 * blank cell that fills in long after they stopped scrolling. Capping the delay does not fix it
 * either -- it just makes every item arriving from a scroll wait the same fixed 288ms instead.
 *
 * The real distinction is not *which* item it is, it is *when* it composed. An item that composes
 * in the first fraction of a second after a list appears is part of that list's entrance. An item
 * that composes two minutes later composed because somebody scrolled to it, and an entrance
 * animation there is a stutter rather than a flourish.
 *
 * So the gate is remembered at the *list's* level and closes on a clock. Inside the window, items
 * stagger by index; after it, [delayFor] returns zero for everything and the list behaves as if
 * the feature were not there.
 */
@Immutable
class StaggerGate internal constructor(private val openedAt: Long) {

	/**
	 * The delay for the item at [index], in milliseconds. Zero once the entrance window has shut.
	 *
	 * Read at an item's first composition, which is the moment the answer is wanted, and never
	 * again -- so reading a wall clock here does not need to be snapshot-aware.
	 */
	fun delayFor(index: Int): Int {
		if (index >= AgehaMotion.STAGGER_LIMIT) return 0
		if (System.nanoTime() - openedAt > WINDOW_NANOS) return 0
		return index * AgehaMotion.STAGGER_MS
	}

	private companion object {
		/**
		 * How long a list counts as "still appearing".
		 *
		 * The full stagger plus one screen transition, which is the longest a first paint can
		 * legitimately take: the screen slides in, and only then does the last staggered item
		 * start. Anything composing after that did so because the user moved.
		 */
		val WINDOW_NANOS =
			(AgehaMotion.STAGGER_LIMIT * AgehaMotion.STAGGER_MS + AgehaMotion.TRANSITION_MS) *
				1_000_000L
	}
}

/** A [StaggerGate] that opens now and lives as long as the composable that remembers it. */
@Composable
fun rememberStaggerGate(): StaggerGate = remember { StaggerGate(System.nanoTime()) }

/**
 * A list item's entrance: fading in and rising [AgehaMotion.slide], delayed by its place in the row.
 *
 * Applied through `graphicsLayer` rather than through `offset` and `alpha` modifiers, so the
 * animation is a draw-phase property change and never triggers a layout pass. In a grid of a
 * hundred covers that is the difference between an entrance and a stall.
 */
@Composable
fun Modifier.motionStagger(index: Int, gate: StaggerGate): Modifier {
	if (!LocalMotionEnabled.current) return this
	val slidePx = with(LocalDensity.current) { AgehaMotion.slide.toPx() }
	val delay = remember(index) { gate.delayFor(index) }
	var arrived by remember { mutableStateOf(false) }
	LaunchedEffect(Unit) { arrived = true }
	val progress by animateFloatAsState(
		targetValue = if (arrived) 1f else 0f,
		animationSpec = tween(
			durationMillis = AgehaMotion.QUICK_MS,
			delayMillis = delay,
			easing = AgehaMotion.standard,
		),
		label = "item-entrance",
	)
	return this.graphicsLayer {
		alpha = progress
		translationY = (1f - progress) * slidePx
	}
}

/**
 * What Ageha does about animation, as a user setting.
 *
 * Three states rather than a switch, because the honest default is neither on nor off: Windows
 * already asks this question in Settings -> Accessibility -> Visual effects, and an application
 * that makes the user answer it a second time is an application that will disagree with their
 * system the first time they change their mind.
 */
enum class MotionPreference {
	/** Follow the Windows "Animation effects" setting. The default. */
	SYSTEM,

	/** Animate regardless of what Windows says. */
	FULL,

	/** Never animate. Every transition becomes an instant state change. */
	REDUCED,
	;

	/**
	 * Resolve against the OS answer.
	 *
	 * See the desktop app's `SystemMotion` for where [systemAllowsMotion] comes from, and why it
	 * defaults to true whenever Windows cannot be asked.
	 */
	fun isEnabled(systemAllowsMotion: Boolean): Boolean = when (this) {
		SYSTEM -> systemAllowsMotion
		FULL -> true
		REDUCED -> false
	}
}
