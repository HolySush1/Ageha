package app.ageha.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glass: translucent chrome drawn over a live backdrop.
 *
 * ## What this is not
 *
 * It is not `backdrop-filter`, and it is not iOS 26's Liquid Glass. Neither exists here.
 * Compose Desktop's `Modifier.blur` blurs a composable's *own* content, not what is behind it,
 * so there is no way to ask a panel to frost the pixels underneath it. Every glassmorphism recipe
 * written for the web or for SwiftUI assumes that primitive, and none of them port.
 *
 * What Ageha does instead is the two-layer construction that predates backdrop filters and still
 * looks right: [AgehaBackdrop] draws one calm, theme-derived gradient across the window, and
 * panels are translucent fills over it. Depth comes from the fill, the specular edge and the cast
 * shadow rather than from a per-panel blur nobody can afford.
 *
 * ## Why the alphas are so much higher than a web recipe's
 *
 * The usual glassmorphism figure is 10-30% white. At that opacity a panel takes its contrast from
 * whatever happens to be behind it, which is to say it has no contrast guarantee at all. Ageha's
 * floor is WCAG AA on body text in every theme, so the alphas here are derived from that
 * constraint rather than from a screenshot, and `AgehaContrastTest` composites them over the
 * backdrop's own gradient stops -- and, for [GlassTone.RAISED], over raw cover art -- on every
 * build.
 *
 * Nothing here introduces a colour. Fills come from the active scheme's container ramp, so glass
 * follows light, dark and AMOLED for free and rule 7 stays intact. The only non-scheme values are
 * the white and black alphas on the specular edge, which are light rather than pigment.
 */
@Immutable
enum class GlassTone {

	/**
	 * Chrome that floats over the backdrop: the navigation pill, breadcrumb and filter bars.
	 *
	 * The most transparent tone, because this is the layer whose whole job is to let the artwork
	 * behind it read as depth rather than as decoration.
	 */
	CHROME,

	/** Cards and inline panels. Holds sustained reading, so it sits further from the backdrop. */
	PANEL,

	/**
	 * Menus, popovers and dialogs.
	 *
	 * Nearly opaque, and deliberately so: these float over *content* rather than over the
	 * backdrop, so they get none of the palette's help and have to hold their own contrast over
	 * an arbitrary grid of cover art. A dropdown you can read the list through is a dropdown
	 * nobody can read.
	 */
	RAISED,
	;

	/** How much of the fill colour survives compositing. See the class comment for the derivation. */
	val fillAlpha: Float
		get() = when (this) {
			CHROME -> 0.58f
			PANEL -> 0.66f
			RAISED -> 0.88f
		}

	/** Cast shadow. Glass with no shadow reads as a flat translucent rectangle, not as a layer. */
	val elevation: Dp
		get() = when (this) {
			CHROME -> 2.dp
			PANEL -> 4.dp
			RAISED -> 12.dp
		}
}

/** Tokens shared by the glass surfaces and by the test that proves they stay readable. */
object AgehaGlass {

	/**
	 * How much brand tint reaches the corner of [AgehaBackdrop]'s gradient. A hint, not a wash.
	 *
	 * This is the number that makes the rest of the system safe. The backdrop is built from theme
	 * tokens alone -- `surfaceDim`, `surface`, and this much `primaryContainer` -- so backdrop
	 * luminance is bounded to a narrow band around the theme's own surface colour, which is what
	 * lets [GlassTone.CHROME] be as transparent as it is and still pass AA.
	 *
	 * It used to be a scrim over the user's cover art instead, at 0.78. Arbitrary artwork behind
	 * the interface needed a scrim that heavy to bound it at all; nothing is drawn back there any
	 * more, so the bound comes from the palette, and the artwork keeps its own resolution in the
	 * Continue Reading hero. See [AgehaBackdrop] for why that changed.
	 */
	internal const val BACKDROP_BRAND_WASH = 0.35f

	/** The specular edge: brighter along the top, fading to a shadow by the bottom. */
	internal const val EDGE_HIGHLIGHT = 0.22f
	internal const val EDGE_SHADOW = 0.10f

	/** The pill radius used by the floating navigation and by the chips that sit beside it. */
	val PillShape: Shape = RoundedCornerShape(percent = 50)

	/** The fill a tone composites onto the backdrop, in the current theme. */
	@Composable
	fun fill(tone: GlassTone): Color = when (tone) {
		GlassTone.CHROME -> MaterialTheme.colorScheme.surfaceContainer
		GlassTone.PANEL -> MaterialTheme.colorScheme.surfaceContainerHigh
		GlassTone.RAISED -> MaterialTheme.colorScheme.surfaceContainerHighest
	}.copy(alpha = tone.fillAlpha)
}

/**
 * The glass material, as one modifier.
 *
 * Order matters, and it is the reason this is a modifier rather than a block that gets copied: the
 * shadow has to be cast before the shape clips, and the border has to be drawn after the fill or
 * the fill covers it. Getting that order wrong produces glass with no edge, which is the single
 * tell that separates this from a plain translucent rectangle.
 */
@Composable
fun Modifier.glassSurface(
	shape: Shape = MaterialTheme.shapes.large,
	tone: GlassTone = GlassTone.PANEL,
): Modifier = this
	.shadow(tone.elevation, shape, clip = false)
	.clip(shape)
	.background(AgehaGlass.fill(tone))
	.border(
		width = 1.dp,
		brush = Brush.verticalGradient(
			listOf(
				Color.White.copy(alpha = AgehaGlass.EDGE_HIGHLIGHT),
				Color.Transparent,
				Color.Black.copy(alpha = AgehaGlass.EDGE_SHADOW),
			),
		),
		shape = shape,
	)

/** A glass card. The default container for anything that sits on the backdrop and holds content. */
@Composable
fun GlassPanel(
	modifier: Modifier = Modifier,
	shape: Shape = MaterialTheme.shapes.large,
	tone: GlassTone = GlassTone.PANEL,
	contentPadding: PaddingValues = PaddingValues(AgehaSpacing.lg),
	content: @Composable () -> Unit,
) {
	Box(modifier.glassSurface(shape, tone).padding(contentPadding)) { content() }
}

/**
 * A full-width glass bar: breadcrumbs, filter rows, screen headers.
 *
 * Square-ended rather than rounded. It spans the window, and a rounded rectangle that touches both
 * window edges reads as a mistake rather than as a card.
 */
@Composable
fun GlassBar(
	modifier: Modifier = Modifier,
	tone: GlassTone = GlassTone.CHROME,
	contentPadding: PaddingValues = PaddingValues(
		horizontal = AgehaSpacing.lg,
		vertical = AgehaSpacing.sm,
	),
	content: @Composable RowScope.() -> Unit,
) {
	Row(
		modifier
			.fillMaxWidth()
			.glassSurface(RoundedCornerShape(0.dp), tone)
			.padding(contentPadding),
		verticalAlignment = Alignment.CenterVertically,
		content = content,
	)
}
