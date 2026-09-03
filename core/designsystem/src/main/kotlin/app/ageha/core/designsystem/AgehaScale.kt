package app.ageha.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
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
 * Motion tokens. Quiet and quick.
 *
 * The brief's ceiling is 200ms for a page transition and it is the right ceiling. Ageha gets
 * opened many times a day by someone who wants to resume a chapter; every animation is a tax on
 * that, paid repeatedly. Nothing here staggers, bounces, or overshoots -- an eased fade and a
 * short slide are the whole vocabulary.
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
}

/**
 * Corner radii.
 *
 * Restrained on purpose. Material 3's defaults are drawn for phones, where a 28dp dialog corner
 * looks right next to a rounded screen; on a desktop window next to native chrome the same radius
 * reads as a toy. These are roughly half Material's, and covers get almost none -- manga art is
 * rectangular and rounding it crops the artwork.
 */
val AgehaShapes = Shapes(
	extraSmall = RoundedCornerShape(2.dp),
	small = RoundedCornerShape(4.dp),
	medium = RoundedCornerShape(6.dp),
	large = RoundedCornerShape(10.dp),
	extraLarge = RoundedCornerShape(14.dp),
)

/** The corner used on manga covers. Enough to soften the grid, not enough to eat the art. */
val CoverShape = RoundedCornerShape(3.dp)
