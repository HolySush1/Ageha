package app.ageha.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The half of a skin that Material has no room for.
 *
 * Material 3 gives a screen a colour scheme and a shape scale. The design handoff Ageha's two dark
 * skins come from also specifies a third ink, two hairline weights, an inset fill for chips and
 * tracks, a border tint for active pills, two status colours, and -- crucially -- a *pill* radius
 * that is not on Material's scale at all. None of those has a role to live in, and the alternative
 * to putting them here is every screen inventing its own, which is the thing rule 7 exists to stop.
 *
 * So a screen reads `AgehaTheme.skin.warn` the same way it reads `MaterialTheme.colorScheme.error`,
 * and the handoff's own review rule -- *no literal hex in component styles* -- has a Kotlin
 * equivalent it can actually be held to.
 *
 * ## Why the radii are here and not only on `MaterialTheme.shapes`
 *
 * Ember draws its pills at 10dp and Glass at 999px. Same markup, different shape, and the contrast
 * between them is a large part of what makes the two skins feel unrelated -- the handoff says
 * outright not to hardcode the radius. `MaterialTheme.shapes` carries the rectangular scale, which
 * is skin-dependent too; [pill] and [chip] carry the two shapes Material has no step for.
 */
@Immutable
class AgehaSkin(
	/**
	 * The accent, exactly as the handoff specifies it.
	 *
	 * This is the colour for dots, bars, ticks, borders, rules and icon fills -- everything held
	 * to WCAG's non-text 3:1 rather than to AA. It is deliberately *not*
	 * `MaterialTheme.colorScheme.primary`: a fill that carries a label has to clear 4.5:1, and
	 * neither skin's accent can at its published tone, so `primary` is the same hue a few tones
	 * deeper. See `PaletteGenerator.textSafeAccent`.
	 */
	val accent: Color,

	/** `--accent-line`: the border on an active pill or a tinted note box. */
	val accentLine: Color,

	/** `--ink3`: mono meta, counts, hints. The third ink step, below `onSurfaceVariant`. */
	val inkFaint: Color,

	/** `--line`: hairline divider and card border. The same value as `outlineVariant`. */
	val line: Color,

	/**
	 * `--line2`: the stronger border, used on ghost buttons and key caps.
	 *
	 * Not `outline`. This hairline measures around 1.4:1 against the window, which is under
	 * WCAG's 3:1 for a non-text boundary -- fine for an edge you are meant to feel rather than
	 * see, wrong for the role Ageha puts focus rings on. `outline` stays derived, and visible.
	 */
	val lineStrong: Color,

	/** `--glass`: the inset fill under a chip, a track, or a segmented group. */
	val inset: Color,

	/** Source health. Skin-independent: green means healthy in both skins. */
	val ok: Color,
	val warn: Color,

	/**
	 * The two stops of `--cover`: what a 2:3 rectangle shows before its artwork arrives.
	 *
	 * A gradient rather than a flat fill, drawn at 160 degrees, because a grid of identical flat
	 * rectangles reads as a rendering failure while a grid of gradients reads as art that has not
	 * loaded. The stops are the skin's own -- warm browns in Ember, cool indigos in Glass -- so a
	 * half-loaded shelf still looks like it belongs to the theme it is in.
	 */
	val coverHigh: Color,
	val coverLow: Color,

	/**
	 * Whether this skin's chrome is opaque.
	 *
	 * The one behavioural difference between the skins, and the reason it is a flag rather than a
	 * pair of alphas: Ember is specified as *flat* -- opaque panels, small radii, `--blur: none`
	 * -- and Glass as frosted. Handing both the same translucent treatment at different alphas
	 * would make Ember a dimmer Glass rather than a different material, which is the distinction
	 * the handoff says is the whole point.
	 *
	 * True here means [glassSurface] fills opaquely and draws the handoff's plain `--line` edge.
	 * False means the translucent fill and the specular gradient that stands in for the backdrop
	 * blur Compose Desktop cannot do. See docs/DESIGN.md 8.1.
	 */
	val isFlat: Boolean,

	/** `--pill-r`: the navigation pill and its items. A lozenge in Glass, a soft box in Ember. */
	val pill: Shape,

	/** `--chip-r`: small chips, badges and dots. */
	val chip: Shape,
) {
	companion object {

		/** Fully round, at any size. Both Glass's 999px and the brand themes' pills. */
		internal val FullyRound: Shape = RoundedCornerShape(percent = 50)

		/**
		 * The two swatches in the title bar's skin switcher, at their literal published values.
		 *
		 * The one deliberate exception to rule 7's "no colour outside the theme", and the handoff
		 * calls it out for the same reason: this control has to show **both** skins at once, so
		 * exactly one of the two can come from the active theme and the other cannot exist at all.
		 * Reading them from the theme would draw Ember's swatch in Glass's violet whenever Glass
		 * was on, which makes the control a toggle that describes nothing.
		 *
		 * They are `#d9432f` and `#8b7ff2` -- the raw seeds, not the contrast-corrected fills the
		 * palette derives from them. That is correct here and nowhere else: these are 15dp and
		 * 13dp of pure identity carrying no text, held to nothing but recognisability.
		 */
		val EmberSwatch: Color = Color(0xFFD9432F)
		val GlassSwatch: Color = Color(0xFF8B7FF2)

		/**
		 * The brand themes' shape scale -- Light and AMOLED.
		 *
		 * Restrained on purpose. Material 3's defaults are drawn for phones, where a 28dp dialog
		 * corner looks right next to a rounded screen; on a desktop window next to native chrome
		 * the same radius reads as a toy. These are roughly half Material's.
		 */
		internal val BrandShapes = Shapes(
			extraSmall = RoundedCornerShape(2.dp),
			small = RoundedCornerShape(4.dp),
			medium = RoundedCornerShape(6.dp),
			large = RoundedCornerShape(10.dp),
			extraLarge = RoundedCornerShape(14.dp),
		)

		/** Ember: `--r 6`, `--r-lg 9`, `--chip-r 5`. Flat and tight. */
		internal val EmberShapes = Shapes(
			extraSmall = RoundedCornerShape(3.dp),
			small = RoundedCornerShape(5.dp),
			medium = RoundedCornerShape(6.dp),
			large = RoundedCornerShape(9.dp),
			extraLarge = RoundedCornerShape(12.dp),
		)

		/** Glass: `--r 11`, `--r-lg 16`. Everything softer, and the pills fully round. */
		internal val GlassShapes = Shapes(
			extraSmall = RoundedCornerShape(6.dp),
			small = RoundedCornerShape(8.dp),
			medium = RoundedCornerShape(11.dp),
			large = RoundedCornerShape(16.dp),
			extraLarge = RoundedCornerShape(20.dp),
		)

		/** Ember's pill. A soft rectangle, not a lozenge -- that contrast is the point. */
		internal val EmberPill: Shape = RoundedCornerShape(10.dp)

		/** Ember's chip and badge. */
		internal val EmberChip: Shape = RoundedCornerShape(5.dp)
	}
}
