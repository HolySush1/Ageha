package app.ageha.brandkit

import me.tatarka.google.material.dynamiccolor.DynamicColor
import me.tatarka.google.material.dynamiccolor.DynamicScheme
import me.tatarka.google.material.dynamiccolor.MaterialDynamicColors
import me.tatarka.google.material.dynamiccolor.Variant
import me.tatarka.google.material.hct.Hct
import me.tatarka.google.material.palettes.TonalPalette
import java.io.File
import java.util.Optional

/**
 * Derives Ageha's four colour schemes from [Brand] and writes them out as literal hex.
 *
 * Why generate rather than depend: the derivation runs once, the inputs are a handful of
 * constants, and the output is a few hundred bytes of hex. Shipping Google's colour-science
 * library in the app to recompute the same numbers on every launch would be pure cost.
 * Committing the output also means the tokens are reviewable in a diff -- a palette change shows
 * up as a colour change, not as a library bump.
 *
 * Two of the four schemes are no longer derived at all. Ember and Glass are transcribed from the
 * design handoff's `skins.css`; the generator still owns them so that the compositing, the
 * contrast repair and the emission all happen in one place, and so `skins.css` can be diffed
 * against the generated file line by line.
 *
 * Run with `./gradlew :tools:brandkit:generatePalette`.
 */
object PaletteGenerator {

	/**
	 * Material's tonal-spot variant clamps primary chroma to 36, which suits a seed picked for
	 * identity rather than for screen legibility. That is exactly our case.
	 */
	private val VARIANT = Variant.TONAL_SPOT

	/** Standard contrast. The gallery renders every pair so a real check is possible. */
	private const val CONTRAST_LEVEL = 0.0

	/** WCAG AA for body text -- the floor `AgehaContrastTest` holds the generated tokens to. */
	private const val AA = 4.5

	private val seedHct: Hct = Hct.fromInt(Brand.SEED_INDIGO)
	private val vermillionHct: Hct = Hct.fromInt(Brand.VERMILLION)
	private val paperHct: Hct = Hct.fromInt(Brand.PAPER)

	/**
	 * Indigo, chroma-clamped the way tonal-spot does it. The seed's own chroma is retained up to
	 * 36 so tone 40 (light primary) and tone 80 (dark primary) both stay recognisably *kon*.
	 */
	private val primary: TonalPalette =
		TonalPalette.fromHueAndChroma(seedHct.hue, maxOf(seedHct.chroma, 36.0).coerceAtMost(48.0))

	/** A muted indigo for supporting chrome. Deliberately low chroma so it recedes. */
	private val secondary: TonalPalette = TonalPalette.fromHueAndChroma(seedHct.hue, 16.0)

	/**
	 * The hanko red, at the vermillion's own hue and chroma. This is the one accent, and it is
	 * also the error palette -- see [errorPalette].
	 */
	private val tertiary: TonalPalette = TonalPalette.fromHct(vermillionHct)

	/**
	 * One red, not two. Material would ordinarily hand `error` its own generic red, which would
	 * land within a few degrees of hue of the hanko vermillion: two reds close enough to look
	 * like a mistake but far enough apart to look sloppy. Ageha uses the brand red for both and
	 * separates the meanings by *form* instead -- see the usage rule in docs/DESIGN.md 4.
	 */
	private val errorPalette: TonalPalette = tertiary

	/**
	 * Light neutrals are warm, from the paper tone. Dark neutrals are cool, from the indigo.
	 *
	 * The brief asks for both -- "paper, not pure white" for light surfaces and "neutrals derived
	 * from the indigo ramp so greys are subtly blue rather than dead". Those pull in opposite
	 * directions at the light end, because paper is warm and indigo is not. Splitting them by
	 * theme resolves it without compromise and is what the two rules were each written for: paper
	 * is warm because paper is warm, and a night surface reads better cool.
	 */
	/** Paper, lightened in place. See the note in [resolve]. */
	private val brighterPaper: Int = Hct.from(paperHct.hue, paperHct.chroma, 98.0).toInt()

	private val neutralLight: TonalPalette = TonalPalette.fromHueAndChroma(paperHct.hue, 6.0)
	private val neutralVariantLight: TonalPalette = TonalPalette.fromHueAndChroma(paperHct.hue, 10.0)
	private val neutralDark: TonalPalette = TonalPalette.fromHueAndChroma(seedHct.hue, 4.0)
	private val neutralVariantDark: TonalPalette = TonalPalette.fromHueAndChroma(seedHct.hue, 8.0)

	private fun brandScheme(dark: Boolean): DynamicScheme = DynamicScheme(
		seedHct,
		VARIANT,
		dark,
		CONTRAST_LEVEL,
		primary,
		secondary,
		tertiary,
		if (dark) neutralDark else neutralLight,
		if (dark) neutralVariantDark else neutralVariantLight,
		Optional.of(errorPalette),
	)

	/**
	 * A skin's scheme.
	 *
	 * Ember and Glass each have exactly one accent -- the handoff's `--bad` is literally
	 * `var(--accent)` -- so primary, tertiary and error all share a palette here, the same way
	 * the brand themes already collapse error into the vermillion. The neutrals take their hue
	 * from the skin's own panel colour, which is what makes Ember's greys warm and Glass's cool
	 * without either skin being handed a second colour to carry.
	 *
	 * Most of what this produces is then overwritten by [skinAnchors]. It is still worth
	 * building: it supplies the roles the handoff has no opinion about, and Material's own
	 * contrast curves compute the on-colours for those.
	 */
	private fun skinScheme(skin: Skin): DynamicScheme {
		val accentHct = Hct.fromInt(skin.accent.argb)
		val groundHct = Hct.fromInt(skin.panel.over(skin.bg.argb))
		val accentPalette = TonalPalette.fromHct(accentHct)
		return DynamicScheme(
			accentHct,
			VARIANT,
			true,
			CONTRAST_LEVEL,
			accentPalette,
			TonalPalette.fromHueAndChroma(accentHct.hue, 16.0),
			accentPalette,
			TonalPalette.fromHueAndChroma(groundHct.hue, 4.0),
			TonalPalette.fromHueAndChroma(groundHct.hue, 8.0),
			Optional.of(accentPalette),
		)
	}

	private fun scheme(theme: Theme): DynamicScheme =
		theme.skin?.let(::skinScheme) ?: brandScheme(theme != Theme.LIGHT)

	private val mdc = MaterialDynamicColors()

	/**
	 * The roles Compose's `ColorScheme` takes, in the order the generated file writes them.
	 * Anything absent here keeps Material's default, which is only true of the `*Fixed` roles --
	 * Ageha has no use for them, as they exist for content that must not re-tone across themes.
	 */
	private val roles: List<Pair<String, DynamicColor>> = listOf(
		"primary" to mdc.primary(),
		"onPrimary" to mdc.onPrimary(),
		"primaryContainer" to mdc.primaryContainer(),
		"onPrimaryContainer" to mdc.onPrimaryContainer(),
		"inversePrimary" to mdc.inversePrimary(),
		"secondary" to mdc.secondary(),
		"onSecondary" to mdc.onSecondary(),
		"secondaryContainer" to mdc.secondaryContainer(),
		"onSecondaryContainer" to mdc.onSecondaryContainer(),
		"tertiary" to mdc.tertiary(),
		"onTertiary" to mdc.onTertiary(),
		"tertiaryContainer" to mdc.tertiaryContainer(),
		"onTertiaryContainer" to mdc.onTertiaryContainer(),
		"background" to mdc.background(),
		"onBackground" to mdc.onBackground(),
		"surface" to mdc.surface(),
		"onSurface" to mdc.onSurface(),
		"surfaceVariant" to mdc.surfaceVariant(),
		"onSurfaceVariant" to mdc.onSurfaceVariant(),
		"surfaceTint" to mdc.surfaceTint(),
		"inverseSurface" to mdc.inverseSurface(),
		"inverseOnSurface" to mdc.inverseOnSurface(),
		"error" to mdc.error(),
		"onError" to mdc.onError(),
		"errorContainer" to mdc.errorContainer(),
		"onErrorContainer" to mdc.onErrorContainer(),
		"outline" to mdc.outline(),
		"outlineVariant" to mdc.outlineVariant(),
		"scrim" to mdc.scrim(),
		"surfaceBright" to mdc.surfaceBright(),
		"surfaceDim" to mdc.surfaceDim(),
		"surfaceContainer" to mdc.surfaceContainer(),
		"surfaceContainerHigh" to mdc.surfaceContainerHigh(),
		"surfaceContainerHighest" to mdc.surfaceContainerHighest(),
		"surfaceContainerLow" to mdc.surfaceContainerLow(),
		"surfaceContainerLowest" to mdc.surfaceContainerLowest(),
	)

	/**
	 * The brand anchors. Material would put light `surface` at neutral tone 98 -- a near-white
	 * warmed only slightly. The brief names paper as *the* light surface and sumi as *the* dark
	 * one, so those two roles are pinned rather than derived, and the rest of the ramp is left to
	 * Material. Two overrides is a small enough surface to reason about; a dozen would not be.
	 */
	private fun anchors(dark: Boolean): Map<String, Int> = if (dark) {
		mapOf("background" to Brand.SUMI, "surface" to Brand.SUMI)
	} else {
		mapOf("background" to Brand.PAPER, "surface" to Brand.PAPER)
	}

	/**
	 * The accent, deepened only as far as it takes to carry a label.
	 *
	 * The handoff draws white text on its accent fills. Ember's red measures 3.87:1 against white
	 * and 3.90:1 against a near-black -- there is no text colour that clears AA on it, because it
	 * sits at the tone where both ends are equidistant. Glass's violet is fine with dark text and
	 * fails with light.
	 *
	 * So the fill *under a label* is allowed to walk a few tones off the handoff's value, and
	 * nothing else is: [Skin.accent] stays literal everywhere it is a dot, a bar, a tick or a
	 * border, where WCAG's non-text 3:1 applies and the handoff already clears it. Deepening a
	 * button by a few tones is a difference you have to hunt for; failing AA on the primary
	 * action in a reading application is not.
	 *
	 * The search goes *down* rather than to whichever side is nearer, and that is the whole
	 * decision here. Both skins are a couple of tones from clearing AA in either direction, and
	 * the nearer side is the light one -- which would leave a brighter accent carrying dark text.
	 * A dark label on a saturated fill reads as a warning, not as the primary action, and the
	 * handoff draws white on both accents. Matching how it *looks* is worth more than matching
	 * one hex, so the fill deepens and the label stays paper.
	 */
	private fun textSafeAccent(accent: Int): Pair<Int, Int> {
		if (contrastRatio(accent, Brand.PAPER) >= AA) return accent to Brand.PAPER
		val hct = Hct.fromInt(accent)
		var tone = hct.tone
		while (tone > 0.0) {
			tone -= 1.0
			val candidate = Hct.from(hct.hue, hct.chroma, tone).toInt()
			if (contrastRatio(candidate, Brand.PAPER) >= AA) return candidate to Brand.PAPER
		}
		// Unreachable for any accent with a tone above black, and a lie if it ever fires, so it
		// falls back to the one pairing that cannot be wrong rather than to the literal accent.
		return Brand.SUMI to Brand.PAPER
	}

	/**
	 * A skin pins almost its whole surface ramp, because the ramp *is* the design.
	 *
	 * Where the brand themes anchor two roles and leave the rest to Material, `skins.css`
	 * specifies every step by hand, and landing on those values is the entire point of
	 * transcribing it. What is still left derived: the on-colours Material guarantees pairs for,
	 * `outline`, and the inverse roles.
	 *
	 * `outline` is deliberately *not* the handoff's `--line2`. That hairline measures 1.39:1
	 * against the background -- well under WCAG's 3:1 for a non-text boundary -- which is fine
	 * for a divider and not fine for the role Ageha puts on focus rings. So `--line` becomes
	 * `outlineVariant`, which the contrast test exempts precisely because it separates rather
	 * than delimits; `--line2` is carried on `lineStrong` for the components that want the
	 * handoff's exact edge; and `outline` stays derived, and visible.
	 */
	private fun skinAnchors(skin: Skin): Map<String, Int> {
		val bg = skin.bg.argb
		val panel2 = skin.panel2.over(bg)
		val panel = skin.panel.over(bg)
		val panelHigh = skin.panelHigh.over(bg)
		val (accentFill, onAccent) = textSafeAccent(skin.accent.argb)
		val accentSoft = skin.accentSoft.over(bg)
		val ink = skin.ink.argb
		return mapOf(
			"background" to bg,
			"surface" to bg,
			"surfaceDim" to bg,
			"surfaceContainerLowest" to bg,
			"surfaceContainerLow" to skin.chrome.over(bg),
			"surfaceContainer" to panel2,
			"surfaceContainerHigh" to panel,
			"surfaceContainerHighest" to panelHigh,
			"surfaceBright" to panelHigh,
			// `surfaceVariant` is the ground Material pairs `onSurfaceVariant` against, and the
			// handoff's muted ink is only readable on a panel, not on the window. Pinning it to
			// the panel keeps that pair honest instead of brightening the ink to compensate.
			"surfaceVariant" to panel,
			"onBackground" to ink,
			"onSurface" to ink,
			"onSurfaceVariant" to skin.inkMuted.over(panel2),
			"outlineVariant" to skin.line.over(bg),
			"primary" to accentFill,
			"onPrimary" to onAccent,
			"primaryContainer" to accentSoft,
			"onPrimaryContainer" to ink,
			"tertiary" to accentFill,
			"onTertiary" to onAccent,
			"tertiaryContainer" to accentSoft,
			"onTertiaryContainer" to ink,
			"error" to accentFill,
			"onError" to onAccent,
			"errorContainer" to accentSoft,
			"onErrorContainer" to ink,
			"surfaceTint" to skin.accent.argb,
		)
	}

	/**
	 * AMOLED overrides. Only the roles that actually sit at the back of the window go to true
	 * black; every elevated container stays on the sumi ramp, so cards and sheets remain visible
	 * as separate objects instead of dissolving into the background.
	 */
	private fun amoledOverrides(): Map<String, Int> = mapOf(
		"background" to Brand.AMOLED_BLACK,
		"surface" to Brand.AMOLED_BLACK,
		"surfaceDim" to Brand.AMOLED_BLACK,
		"surfaceContainerLowest" to Brand.AMOLED_BLACK,
		"surfaceContainerLow" to neutralDark.tone(5),
		"surfaceContainer" to neutralDark.tone(8),
		"surfaceContainerHigh" to neutralDark.tone(12),
		"surfaceContainerHighest" to neutralDark.tone(17),
	)

	/**
	 * Roles exempt from [warmPureExtremes]. `scrim` is a translucent overlay: a warm scrim reads
	 * as a stain over artwork rather than as a dimming, so it stays true black. The AMOLED
	 * surfaces are true black on purpose, which is the whole reason that variant exists.
	 */
	private val PURE_EXTREME_EXEMPT = setOf(
		"scrim",
		"background",
		"surface",
		"surfaceDim",
		"surfaceContainerLowest",
	)

	/**
	 * Ageha has no pure white and no pure black. Paper stands in for white and sumi for black,
	 * everywhere, including on filled buttons -- a paper-toned label on an indigo fill is the
	 * seal's own figure/ground relationship, and it still clears AA by a wide margin.
	 *
	 * Applying this as one uniform rule rather than as a handful of hand-picked overrides means
	 * a future palette change cannot quietly reintroduce `#FFFFFF` in a role nobody thought to
	 * check. `AgehaPaletteTest` asserts the absence directly.
	 */
	private fun warmPureExtremes(role: String, argb: Int): Int = when {
		role in PURE_EXTREME_EXEMPT -> argb
		argb and 0xFFFFFF == 0xFFFFFF -> Brand.PAPER
		argb and 0xFFFFFF == 0x000000 -> Brand.SUMI
		else -> argb
	}

	fun resolve(theme: Theme): Map<String, Int> {
		val dark = theme != Theme.LIGHT
		val s = scheme(theme)
		val base = roles.associate { (name, color) ->
			name to warmPureExtremes(name, s.getArgb(color))
		}
		theme.skin?.let { return base + skinAnchors(it) }
		val overrides = anchors(dark) +
			// Material puts the lowest light container at tone 100, i.e. `#FFFFFF`. Asking the
			// neutral palette for tone 99 instead does not help -- HCT cannot hold chroma that
			// close to white, and it comes back `#FFFBFF`, a *cool* near-white, which is worse
			// than the problem. Lightening paper itself keeps the warmth: same hue, same chroma,
			// three tones up. This is the brightest surface in the app and it still reads as paper.
			(if (dark) emptyMap() else mapOf("surfaceContainerLowest" to brighterPaper)) +
			if (theme == Theme.AMOLED) amoledOverrides() else emptyMap()
		return base + overrides
	}

	/**
	 * The tokens that have no Material role: the handoff's third ink, its two hairlines, its
	 * inset fill, the literal accent and its border tint, and the two status colours.
	 *
	 * Every theme gets a set, derived the same way, so a screen can read `AgehaTheme.skin.warn`
	 * without first asking which theme it is in. For a skin these are transcribed; for the brand
	 * themes they fall out of the resolved scheme at the same alphas the handoff uses.
	 */
	/**
	 * Every surface the third ink is drawn on.
	 *
	 * The same set `AgehaContrastTest` already holds `onSurface` to, and for the same reason: a
	 * card is `surfaceContainer`, a dialog is `surfaceContainerHigh`, the title bar is
	 * `surfaceContainerLow`, and the window itself is `surface`. An ink that clears AA on one
	 * step of the ramp and fails on the next is an ink that is legible on whichever screen
	 * somebody happened to check.
	 */
	private fun inkSurfaces(resolved: Map<String, Int>): List<Int> = listOf(
		"surface",
		"surfaceDim",
		"surfaceBright",
		"surfaceVariant",
		"surfaceContainerLowest",
		"surfaceContainerLow",
		"surfaceContainer",
		"surfaceContainerHigh",
		"surfaceContainerHighest",
	).map(resolved::getValue)

	/**
	 * The third ink, lifted only as far as it takes to be readable.
	 *
	 * `--ink3` is the handoff's quietest text colour and Ageha draws real words in it -- the title
	 * bar's context line, every mono count, every settings hint, the chapter and page meta under a
	 * cover. As transcribed it measures 2.33:1 in Light, 2.83:1 in AMOLED, 3.49:1 in Ember and
	 * 4.11:1 in Glass against the surfaces it sits on. All four are under WCAG AA, and the two
	 * worst are under the 3:1 that even a *non-text* mark is held to: in Light this is grey text
	 * on cream that a lot of people simply cannot read.
	 *
	 * So it walks in tone -- lighter in a dark theme, darker in a light one -- until the worst
	 * pairing across [inkSurfaces] clears [AA], and stops there. Same shape as [textSafeAccent]
	 * and the same bargain: the handoff's exact hex is worth having right up to the point where
	 * holding it makes the text unreadable, and then it is not.
	 *
	 * Hue and chroma are held, so the ink stays the theme's own -- warm in Ember, cool in Glass --
	 * rather than collapsing to a neutral grey. It stays a *third* ink too: it is lifted to the
	 * threshold and no further, so it still reads as quieter than `onSurfaceVariant` above it.
	 */
	private fun textSafeInk(ink: Int, resolved: Map<String, Int>, dark: Boolean): Int {
		val surfaces = inkSurfaces(resolved)
		fun worst(candidate: Int) = surfaces.minOf { contrastRatio(candidate, it) }
		if (worst(ink) >= AA) return ink
		val hct = Hct.fromInt(ink)
		// Toward the ink, away from the paper: a dark theme's text gets lighter and a light
		// theme's gets darker. Walking the *nearer* direction would sometimes invert the ink
		// against its own background, which is not a quieter colour, it is a different design.
		val step = if (dark) 1.0 else -1.0
		var tone = hct.tone
		while (tone > 0.0 && tone < 100.0) {
			tone += step
			val candidate = Hct.from(hct.hue, hct.chroma, tone).toInt()
			if (worst(candidate) >= AA) return candidate
		}
		// Unreachable while `onSurface` itself clears AA on this ramp, which the contrast test
		// asserts independently. Falling back to it rather than to the failing ink means a broken
		// palette ships a *readable* third ink, not an invisible one.
		return resolved.getValue("onSurface")
	}

	fun resolveSkinTokens(theme: Theme): Map<String, Int> {
		val resolved = resolve(theme)
		val surface = resolved.getValue("surface")
		val skin = theme.skin
			?: return mapOf(
				"accent" to resolved.getValue("tertiary"),
				"accentLine" to Tint(resolved.getValue("tertiary"), 0.65).over(surface),
				"inkFaint" to textSafeInk(
					Tint(resolved.getValue("onSurface"), 0.38).over(surface),
					resolved,
					theme != Theme.LIGHT,
				),
				"line" to resolved.getValue("outlineVariant"),
				"lineStrong" to resolved.getValue("outline"),
				"inset" to Tint(resolved.getValue("onSurface"), 0.05).over(surface),
				"ok" to Brand.OK,
				"warn" to Brand.WARN,
				// A brand theme has no transcribed `--cover`, so it gets the same shape derived
				// from its own ramp: the container step above the panel, falling to the panel.
				// Two adjacent tones rather than an invented pair, so the placeholder reads as an
				// empty surface in this theme instead of as a coloured rectangle from another.
				"coverHigh" to resolved.getValue("surfaceContainerHighest"),
				"coverLow" to resolved.getValue("surfaceContainerHigh"),
			)
		return mapOf(
			"accent" to skin.accent.argb,
			"accentLine" to skin.accentLine.over(surface),
			"inkFaint" to textSafeInk(
				skin.inkFaint.over(skin.panel2.over(surface)),
				resolved,
				dark = true,
			),
			"line" to skin.line.over(surface),
			"lineStrong" to skin.lineStrong.over(surface),
			"inset" to skin.inset.over(surface),
			"ok" to Brand.OK,
			"warn" to Brand.WARN,
			"coverHigh" to skin.coverHigh.over(surface),
			"coverLow" to skin.coverLow.over(surface),
		)
	}

	/** Role names in declaration order, so generated output is stable across runs. */
	fun roleNames(): List<String> = roles.map { it.first }

	/** The non-Material token names, likewise. */
	fun skinTokenNames(): List<String> = listOf(
		"accent", "accentLine", "inkFaint", "line", "lineStrong", "inset", "ok", "warn",
		"coverHigh", "coverLow",
	)

	/**
	 * The four schemes Ageha ships.
	 *
	 * `DARK` used to sit between light and AMOLED; the two skins replace it. A theme with a
	 * [skin] is transcribed from the handoff, one without is derived from [Brand].
	 */
	enum class Theme(val kotlinName: String, val label: String, val skin: Skin? = null) {
		LIGHT("Light", "light"),
		EMBER("Ember", "Ember", Brand.EMBER),
		GLASS("Glass", "Glass", Brand.GLASS),
		AMOLED("Amoled", "AMOLED"),
	}
}

private const val GENERATED_HEADER = """/*
 * GENERATED FILE -- DO NOT EDIT BY HAND.
 *
 * Produced by `./gradlew :tools:brandkit:generatePalette` from the brand constants and the two
 * skin transcriptions in tools/brandkit/.../Brand.kt. Editing a value here will be overwritten
 * the next time the generator runs, and the contrast tests in this module will not know you
 * meant it.
 *
 * The derivation, the places it departs from stock Material 3, and the two skins are documented
 * in docs/DESIGN.md 2 and 11.
 */"""

fun main(args: Array<String>) {
	val repoRoot = File(args.firstOrNull() ?: ".")
	val out = File(
		repoRoot,
		"core/designsystem/src/main/kotlin/app/ageha/core/designsystem/AgehaColorTokens.kt",
	)
	out.parentFile.mkdirs()

	val text = buildString {
		appendLine(GENERATED_HEADER)
		appendLine("package app.ageha.core.designsystem")
		appendLine()
		appendLine("import androidx.compose.ui.graphics.Color")
		appendLine()
		// One class with several instances, not several objects. Separate objects have no common
		// supertype, so `if (amoled) Amoled else Ember` infers `Any` and every role access off it
		// fails to resolve -- which is exactly what happened the first time this was generated.
		appendLine("/** One theme's worth of colour. */")
		appendLine("internal class AgehaScheme(")
		for (role in PaletteGenerator.roleNames()) appendLine("	val $role: Color,")
		appendLine(") {")
		// A name -> colour map alongside the properties. The tests that police the palette need to
		// walk every role, and generating the map is both cheaper and stricter than reflecting over
		// the class at runtime: it cannot drift from the property list, because it is emitted from
		// the same one, and it keeps kotlin-reflect out of the module entirely.
		appendLine("	/** Every role by name, in declaration order. */")
		appendLine("	val all: Map<String, Color> = linkedMapOf(")
		for (role in PaletteGenerator.roleNames()) appendLine("		\"$role\" to $role,")
		appendLine("	)")
		appendLine("}")
		appendLine()
		appendLine("/**")
		appendLine(" * The tokens Material has no role for: the handoff's third ink, its two hairlines,")
		appendLine(" * its inset fill, the literal accent and its border tint, the two status colours, and")
		appendLine(" * the two stops of the placeholder gradient drawn where cover art has not arrived.")
		appendLine(" *")
		appendLine(" * Read these through `AgehaTheme.skin` rather than directly -- see AgehaSkin.kt.")
		appendLine(" */")
		appendLine("internal class AgehaSkinScheme(")
		for (token in PaletteGenerator.skinTokenNames()) appendLine("	val $token: Color,")
		appendLine(")")
		appendLine()
		appendLine("/** Every colour Ageha uses, in every theme. No screen may define its own. */")
		appendLine("internal object AgehaColorTokens {")
		for (theme in PaletteGenerator.Theme.entries) {
			val resolved = PaletteGenerator.resolve(theme)
			appendLine()
			appendLine("	/** The ${theme.label} scheme. */")
			appendLine("	val ${theme.kotlinName} = AgehaScheme(")
			for (role in PaletteGenerator.roleNames()) {
				val argb = resolved.getValue(role)
				appendLine("		$role = Color(0xFF%06X),".format(argb and 0xFFFFFF))
			}
			appendLine("	)")
		}
		appendLine("}")
		appendLine()
		appendLine("/** The non-Material tokens, per theme. */")
		appendLine("internal object AgehaSkinTokens {")
		for (theme in PaletteGenerator.Theme.entries) {
			val resolved = PaletteGenerator.resolveSkinTokens(theme)
			appendLine()
			appendLine("	/** The ${theme.label} scheme's. */")
			appendLine("	val ${theme.kotlinName} = AgehaSkinScheme(")
			for (token in PaletteGenerator.skinTokenNames()) {
				val argb = resolved.getValue(token)
				appendLine("		$token = Color(0xFF%06X),".format(argb and 0xFFFFFF))
			}
			appendLine("	)")
		}
		appendLine("}")
	}
	out.writeText(text)
	println("wrote ${out.relativeTo(repoRoot).invariantSeparatorsPath}")

	// A markdown table for docs/DESIGN.md, printed rather than written so the doc stays
	// hand-authored -- the prose around the numbers is the part worth writing carefully.
	println()
	val themes = PaletteGenerator.Theme.entries
	val resolved = themes.associateWith { PaletteGenerator.resolve(it) }
	println("| Role | " + themes.joinToString(" | ") { it.label } + " |")
	println("|---".repeat(themes.size + 1) + "|")
	for (role in PaletteGenerator.roleNames()) {
		val cells = themes.joinToString(" | ") { "`${resolved.getValue(it).getValue(role).toHex()}`" }
		println("| `$role` | $cells |")
	}
}
