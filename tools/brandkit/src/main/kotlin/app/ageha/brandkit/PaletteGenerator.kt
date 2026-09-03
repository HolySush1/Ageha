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
 * Derives Ageha's three colour schemes from [Brand] and writes them out as literal hex.
 *
 * Why generate rather than depend: the derivation runs once, the inputs are two constants, and
 * the output is a few hundred bytes of hex. Shipping Google's colour-science library in the app
 * to recompute the same numbers on every launch would be pure cost. Committing the output also
 * means the tokens are reviewable in a diff -- a palette change shows up as a colour change,
 * not as a library bump.
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

	private fun scheme(dark: Boolean): DynamicScheme = DynamicScheme(
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
		val s = scheme(dark)
		val base = roles.associate { (name, color) ->
			name to warmPureExtremes(name, s.getArgb(color))
		}
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

	/** Role names in declaration order, so generated output is stable across runs. */
	fun roleNames(): List<String> = roles.map { it.first }

	enum class Theme(val kotlinName: String, val label: String) {
		LIGHT("Light", "light"),
		DARK("Dark", "dark"),
		AMOLED("Amoled", "AMOLED"),
	}
}

private const val GENERATED_HEADER = """/*
 * GENERATED FILE -- DO NOT EDIT BY HAND.
 *
 * Produced by `./gradlew :tools:brandkit:generatePalette` from the two brand constants in
 * tools/brandkit/.../Brand.kt. Editing a value here will be overwritten the next time the
 * generator runs, and the contrast tests in this module will not know you meant it.
 *
 * The derivation, and the four places it deliberately departs from stock Material 3, are
 * documented in docs/DESIGN.md 2.
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
		// One class with three instances, not three objects. Three objects have no common
		// supertype, so `if (amoled) Amoled else Dark` infers `Any` and every role access off it
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
	}
	out.writeText(text)
	println("wrote ${out.relativeTo(repoRoot).invariantSeparatorsPath}")

	// A markdown table for docs/DESIGN.md, printed rather than written so the doc stays
	// hand-authored -- the prose around the numbers is the part worth writing carefully.
	println()
	println("| Role | Light | Dark | AMOLED |")
	println("|---|---|---|---|")
	val light = PaletteGenerator.resolve(PaletteGenerator.Theme.LIGHT)
	val dark = PaletteGenerator.resolve(PaletteGenerator.Theme.DARK)
	val amoled = PaletteGenerator.resolve(PaletteGenerator.Theme.AMOLED)
	for (role in PaletteGenerator.roleNames()) {
		val a = amoled.getValue(role)
		val d = dark.getValue(role)
		val amoledCell = if (a == d) "*(as dark)*" else "`${a.toHex()}`"
		println("| `$role` | `${light.getValue(role).toHex()}` | `${d.toHex()}` | $amoledCell |")
	}
}
