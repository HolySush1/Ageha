package app.ageha.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer

/**
 * What every clickable thing in Ageha does when the pointer arrives, and when it is pressed.
 *
 * ## Why this is one modifier rather than a convention
 *
 * Before this existed, hover feedback in Ageha was present in **four places in the entire
 * application** -- the Continue Reading row, the reader chrome, the title-bar window buttons, and
 * the manga card, where the hover state was collected and then used only to un-blur an adult
 * cover. Chapter rows, source rows, settings rows, the words in the navigation pill, the chips and
 * the ghost buttons had no response to the pointer at all. Forty-odd surfaces that looked
 * clickable, were clickable, and gave no sign of it until they had already been clicked.
 *
 * That is not a per-screen omission, it is a missing primitive. Fixing it screen by screen would
 * have produced forty slightly different hover treatments, which is the same failure as two cover
 * cards with different corner radii -- the thing [MangaCard] lives in this module to prevent. So
 * it is one modifier, and everything wears it.
 *
 * ## What it animates, and what it deliberately does not
 *
 * **Scale** and **a background tint**, and nothing else. Not colour changes on the text, not
 * borders that appear on hover, not elevation on every surface. `ui-ux-pro-max` rates "animate one
 * or two key elements per view" as a High-severity rule and it is right: a row that changes four
 * properties at once when the pointer crosses it reads as unstable rather than as responsive.
 *
 * Scale is applied through `graphicsLayer`, so it is a draw-phase property and moves nothing
 * around it. A hover effect that ran layout would reflow a grid of a hundred covers on every
 * pointer movement.
 *
 * Both go through [snappySpring] and [motionTween], so a user who has turned motion off gets the
 * same states with no interpolation -- the feedback stays, only the movement goes.
 *
 * ## Interruption is the whole reason scale is a spring
 *
 * These animations are pointer-driven, which means they are interrupted constantly: sweeping
 * across a shelf starts and abandons a hover animation on every card it crosses. A spring carries
 * its velocity into the new target; a tween restarts from wherever it happened to be, which is
 * precisely the discontinuity people mean when they call an interface janky.
 */
@Composable
fun Modifier.interactive(
	interaction: MutableInteractionSource,
	/**
	 * How much the control grows under the pointer, or when focused.
	 *
	 * 1.04 for a cover, 1.0 for a row. Not because a row cannot scale -- this is a draw-time
	 * transform and reflows nothing -- but because a row scaled in place *visually* overlaps the
	 * rows above and below it, which sit flush against it. A cover has a 12dp gutter to grow into.
	 */
	hoverScale: Float = 1f,
	/**
	 * How far a press sinks. Small, and *inward*: a control that grows under the finger looks like
	 * it is refusing the press, where one that recedes looks like it took it.
	 */
	pressScale: Float = PRESS_SCALE,
	/** Painted behind the content on hover or focus. Null draws nothing -- the default for covers. */
	hoverTint: Color? = null,
	/** The tint's shape. Ignored when [hoverTint] is null. */
	shape: Shape = RectangleShape,
	/** A disabled control does not respond, and must not pretend to. */
	enabled: Boolean = true,
): Modifier {
	val hovered by interaction.collectIsHoveredAsState()
	val pressed by interaction.collectIsPressedAsState()
	// Focus counts as much as hover, and this is not decoration.
	//
	// Call sites pass `indication = null` to `clickable`, because this modifier is drawing the
	// feedback instead -- and the indication that was being replaced was Material's ripple, which
	// also carried the *focus* state. Tracking hover alone would have left every control in Ageha
	// with no visible focus at all, which takes the interface away from anyone driving it by
	// keyboard. The tint is the focus ring here.
	val focused by interaction.collectIsFocusedAsState()
	val active = enabled && (hovered || focused)
	val scale by animateFloatAsState(
		targetValue = when {
			!enabled -> 1f
			pressed -> pressScale
			// Focus scales exactly as hover does, and it has to: a cover card passes no tint --
			// a 6% wash over artwork is invisible -- so the lift is the *only* feedback it has.
			// Without this, tabbing through a shelf would move an indicator nobody can see.
			//
			// Safe to scale on focus because this is a `graphicsLayer` property: it is applied at
			// draw time and never reflows the grid around it.
			active -> hoverScale
			else -> 1f
		},
		animationSpec = snappySpring(),
		label = "interactive-scale",
	)
	val tinted = if (hoverTint == null) {
		this
	} else {
		// Crossed rather than switched. A background that appears the instant the pointer crosses
		// a boundary flickers when someone drags along a list of rows, because the pointer clips
		// the edge of three of them on the way past.
		val fill by animateColorAsState(
			targetValue = if (active) hoverTint else Color.Transparent,
			animationSpec = motionTween(AgehaMotion.INSTANT_MS),
			label = "interactive-tint",
		)
		this.background(fill, shape)
	}
	// Scale sits outside the tint so the tint scales with the content rather than staying put
	// behind a control that has moved off it.
	return tinted.graphicsLayer {
		scaleX = scale
		scaleY = scale
	}
}

/** A [MutableInteractionSource] for one control. Convenience, so call sites stay one line. */
@Composable
fun rememberInteraction(): MutableInteractionSource = remember { MutableInteractionSource() }

/**
 * The hover fill for a row in a list: chapters, sources, settings, search results.
 *
 * Derived from `onSurface` rather than declared, so it is correct in Ember, Glass, Light and
 * AMOLED without four constants -- and so it stays a *tint* of whatever is underneath rather than
 * a colour of its own, which is what lets it sit over both an opaque Ember panel and a translucent
 * Glass one without turning either into a third material.
 */
val rowHoverTint: Color
	@Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = ROW_HOVER_ALPHA)

/**
 * How much a cover grows under the pointer.
 *
 * Four percent. Enough to lift the card out of the grid and say which one the click will land on;
 * small enough not to overlap its neighbours across a 12dp gutter, which at this scale it does
 * not -- a 132dp cover gains about five pixels of width, two and a half a side.
 */
const val HOVER_SCALE_CARD = 1.04f

/** The default press depth. Two percent, inward. */
private const val PRESS_SCALE = 0.98f

/** Six percent of the ink colour. Visible as a change of state, invisible as a colour. */
private const val ROW_HOVER_ALPHA = 0.06f
