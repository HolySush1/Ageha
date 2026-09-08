package app.ageha.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A 4dp spacing scale.
 *
 * The step names are t-shirt sizes rather than numbers because a number invites arithmetic --
 * `spacing.md * 1.5` is how a design system starts leaking. If a gap does not exist on the scale,
 * the answer is to pick the nearest step, not to invent one.
 *
 * Desktop density is the reason the scale starts tight. This is not a touch UI: there is no 48dp
 * minimum target, the pointer is precise, and the library grid needs to fit a lot of covers on a
 * 27-inch display without looking like a phone app that was stretched.
 */
@Immutable
object AgehaSpacing {
	/** Hairline separation inside a control. */
	val xxs: Dp = 2.dp

	/** Icon-to-label. */
	val xs: Dp = 4.dp

	/** Inside a dense control. */
	val sm: Dp = 8.dp

	/** The default gap between related things. */
	val md: Dp = 12.dp

	/** Between unrelated things; standard content inset. */
	val lg: Dp = 16.dp

	/** Section separation. */
	val xl: Dp = 24.dp

	/** Between major panes. */
	val xxl: Dp = 32.dp

	/** Page margin on wide windows. */
	val xxxl: Dp = 48.dp

	/** The library grid's gutter. Its own token because the grid is the app's signature view. */
	val gridGutter: Dp = 12.dp

	/** Minimum cover width in the library grid before the column count drops. */
	val minCoverWidth: Dp = 132.dp
}

/**
 * Motion tokens. Quiet and quick, and now with a little weight.
 *
 * The brief's ceiling is 200ms for a page transition and it is still the right ceiling. Ageha gets
 * opened many times a day by someone who wants to resume a chapter; every animation is a tax on
 * that, paid repeatedly, and nothing here is allowed to make the app slower to use.
 *
 * ## What changed, and why it is written down
 *
 * This object used to end "nothing here staggers, bounces, or overshoots". That was a defensible
 * position and it produced an application with **nineteen animated call sites in a source tree of
 * two and a half thousand files**, fifteen of them inside two settings controls. The result was not
 * restraint; it was an interface where hovering a cover did nothing, where going deeper and coming
 * back looked identical, and where a shelf that re-sorted itself was indistinguishable from a
 * shelf that had been replaced.
 *
 * So the vocabulary is wider now: two springs and a stagger, on top of the four durations. The
 * ceiling did not move and the reader is still exempt -- see docs/DESIGN.md section 4.
 *
 * ## Durations or springs
 *
 * A **duration** is right when the animation is a cross-fade, an appearance or a disappearance:
 * those have no physical analogue, and a spring on an alpha just looks like an unsteady hand.
 *
 * A **spring** is right when something *moves* -- a card under the pointer, a selection sliding
 * between two words, a cover taking its new place in a re-sorted grid. Spring motion carries
 * velocity across an interruption, which matters here because these are pointer-driven: a user who
 * sweeps across a row of covers interrupts every one of those animations halfway through, and a
 * tween restarted from wherever it happened to be is exactly what "janky" means.
 *
 * The parameters are constants rather than `SpringSpec` instances because a spec is typed to what
 * it animates -- `Float`, `Dp`, `IntOffset` -- and one shared instance would only ever fit one of
 * them. Build them through `snappySpring()` and `settleSpring()`, which also honour the
 * reduced-motion switch. See `AgehaMotionScope.kt`.
 */
@Immutable
object AgehaMotion {
	/** Hover and pressed feedback. Below this a state change reads as a glitch rather than a response. */
	const val INSTANT_MS = 90

	/** The default. Expansions, cross-fades, selection changes. */
	const val QUICK_MS = 140

	/** Screen-level transitions. The brief's ceiling, and a ceiling rather than a target. */
	const val TRANSITION_MS = 190

	/**
	 * The reader's chrome auto-hide. Deliberately slower than everything else: this one is a
	 * *withdrawal* the reader should barely notice, not a response to an action they took.
	 */
	const val CHROME_FADE_MS = 260

	/** Standard easing for anything entering or moving. */
	val standard: Easing = FastOutSlowInEasing

	/**
	 * Exits accelerate away instead of easing out. A thing being dismissed should not linger --
	 * it has already stopped being interesting by the time it starts moving.
	 */
	val exit: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

	/**
	 * The spring for anything directly under the pointer: hover scale, press scale, the selection
	 * indicator in the navigation pill.
	 *
	 * Damped at 0.68, which overshoots by a couple of percent and settles on the second approach.
	 * That is the whole of the "bounce" this design system permits, and it is deliberately small
	 * enough that you would struggle to name it if asked -- what it actually buys is the sense that
	 * the control has mass, which is the difference between a card that responds and a card that
	 * merely changes.
	 *
	 * Stiff, because this one is a *reply*. Anything slower than roughly a tenth of a second stops
	 * reading as a response to the pointer and starts reading as an effect.
	 */
	const val SNAPPY_DAMPING = 0.68f
	const val SNAPPY_STIFFNESS = Spring.StiffnessMedium

	/**
	 * The spring for something finding a new position: a cover moving to its slot in a re-sorted
	 * grid, a progress bar growing to a new value.
	 *
	 * Critically damped -- no overshoot at all. A cover that overshot its position in a grid would
	 * be a cover that briefly overlapped its neighbour, and a progress bar that overshot would
	 * report a percentage that is not true. Both are cases where the honest end state matters more
	 * than the character of the arrival.
	 */
	const val SETTLE_DAMPING = Spring.DampingRatioNoBouncy
	const val SETTLE_STIFFNESS = Spring.StiffnessMediumLow

	/**
	 * Per-item delay when a list first paints.
	 *
	 * 18ms is chosen against the number of items actually visible rather than against a feel: a
	 * library grid shows around fifteen covers on a default window, so the last one starts roughly
	 * a quarter of a second after the first. Wide enough to read as a sweep, short enough that the
	 * grid is never *waiting* on it.
	 */
	const val STAGGER_MS = 18

	/**
	 * How many items get a stagger delay before the rest come in flat.
	 *
	 * The cap is not a nicety, it is a correctness requirement. A lazy grid composes an item when
	 * it scrolls into view, so an uncapped `index * 18ms` would give item four hundred a seven
	 * second delay -- and the user would meet it as a blank row that fills in long after they
	 * stopped scrolling. Sixteen is about one screenful; past that, an item is arriving because it
	 * was scrolled to, which is not an entrance.
	 */
	const val STAGGER_LIMIT = 16

	/** One sweep of a loading skeleton's highlight. Slow: it is a heartbeat, not a spinner. */
	const val SHIMMER_MS = 1400

	/**
	 * How far a screen or a list item travels as it arrives.
	 *
	 * Small on purpose. The slide's job is to say which *direction* something came from -- forward
	 * or back, arriving or leaving -- and twelve pixels says that as clearly as a hundred while
	 * costing none of the settling time.
	 */
	val slide: Dp = 12.dp
}

/**
 * The corner used on manga covers. Enough to soften the grid, not enough to eat the art.
 *
 * The one radius that does not change with the skin. Manga art is rectangular, and how much of it
 * gets cropped is not a question of taste -- so where Ember rounds at 6dp and Glass at 11dp, a
 * cover is 3dp in both. The rest of the shape scale moved to [AgehaSkin], because Ember and Glass
 * disagree about every step of it.
 */
val CoverShape = RoundedCornerShape(3.dp)
