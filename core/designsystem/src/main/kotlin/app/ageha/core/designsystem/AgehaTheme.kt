package app.ageha.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The themes Ageha ships. There is no "custom" option and no dynamic colour: the palette is the
 * design, and a reader that recolours itself from the wallpaper is not the product.
 *
 * [EMBER] and [GLASS] replaced a single `DARK` theme. They are the two skins from the design
 * handoff -- same layout, same components, different colour, radius and material -- and they are
 * dark by construction, so between them and [AMOLED] there are three ways to read at night and
 * one to read in daylight. See docs/DESIGN.md 11.
 */
enum class AgehaThemeMode {
	/** Follows the operating system's light/dark preference, resolving dark to [EMBER]. */
	SYSTEM,
	LIGHT,

	/** Flat, warm near-black, opaque panels, small radii, red accent. The default dark skin. */
	EMBER,

	/** Frosted, cool blue-grey, translucent panels, fully round pills, violet accent. */
	GLASS,

	/**
	 * True black backgrounds, on the brand's own indigo. Worth having despite Ageha being a
	 * desktop app -- OLED laptop panels are now common, and people read at night.
	 */
	AMOLED,
	;

	fun isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
		SYSTEM -> systemInDarkTheme
		LIGHT -> false
		EMBER, GLASS, AMOLED -> true
	}
}

/**
 * Which set of generated tokens a mode resolves to, once the system preference is known.
 *
 * Separate from [AgehaThemeMode] because `SYSTEM` is a *rule*, not a palette, and every lookup
 * below wants the palette. Keeping the two apart is what stops `SYSTEM` having to appear in each
 * `when` over the token tables.
 */
private enum class ResolvedTheme { LIGHT, EMBER, GLASS, AMOLED }

private fun AgehaThemeMode.resolve(systemInDarkTheme: Boolean): ResolvedTheme = when (this) {
	AgehaThemeMode.SYSTEM -> if (systemInDarkTheme) ResolvedTheme.EMBER else ResolvedTheme.LIGHT
	AgehaThemeMode.LIGHT -> ResolvedTheme.LIGHT
	AgehaThemeMode.EMBER -> ResolvedTheme.EMBER
	AgehaThemeMode.GLASS -> ResolvedTheme.GLASS
	AgehaThemeMode.AMOLED -> ResolvedTheme.AMOLED
}

private fun ResolvedTheme.tokens(): AgehaScheme = when (this) {
	ResolvedTheme.LIGHT -> AgehaColorTokens.Light
	ResolvedTheme.EMBER -> AgehaColorTokens.Ember
	ResolvedTheme.GLASS -> AgehaColorTokens.Glass
	ResolvedTheme.AMOLED -> AgehaColorTokens.Amoled
}

private fun ResolvedTheme.skinTokens(): AgehaSkinScheme = when (this) {
	ResolvedTheme.LIGHT -> AgehaSkinTokens.Light
	ResolvedTheme.EMBER -> AgehaSkinTokens.Ember
	ResolvedTheme.GLASS -> AgehaSkinTokens.Glass
	ResolvedTheme.AMOLED -> AgehaSkinTokens.Amoled
}

private fun ResolvedTheme.shapes(): Shapes = when (this) {
	ResolvedTheme.EMBER -> AgehaSkin.EmberShapes
	ResolvedTheme.GLASS -> AgehaSkin.GlassShapes
	ResolvedTheme.LIGHT, ResolvedTheme.AMOLED -> AgehaSkin.BrandShapes
}

/**
 * The non-Material half of the theme, assembled.
 *
 * Ember is the only theme without a lozenge, and that is deliberate: its character is soft
 * rectangles where Glass has capsules. Light and AMOLED keep the fully round pill they have
 * always had.
 */
private fun ResolvedTheme.skin(): AgehaSkin {
	val t = skinTokens()
	return AgehaSkin(
		accent = t.accent,
		accentLine = t.accentLine,
		inkFaint = t.inkFaint,
		line = t.line,
		lineStrong = t.lineStrong,
		inset = t.inset,
		ok = t.ok,
		warn = t.warn,
		coverHigh = t.coverHigh,
		coverLow = t.coverLow,
		// Ember alone. Light and AMOLED keep the translucent chrome they were designed with --
		// AMOLED in particular *wants* it, because a nearly-black window is where a translucent
		// pill reads most clearly as a separate layer.
		isFlat = this == ResolvedTheme.EMBER,
		pill = if (this == ResolvedTheme.EMBER) AgehaSkin.EmberPill else AgehaSkin.FullyRound,
		chip = when (this) {
			ResolvedTheme.EMBER -> AgehaSkin.EmberChip
			ResolvedTheme.GLASS -> AgehaSkin.FullyRound
			ResolvedTheme.LIGHT, ResolvedTheme.AMOLED -> AgehaSkin.BrandShapes.small
		},
	)
}

/**
 * A Material `ColorScheme` from one generated table.
 *
 * Two near-identical functions rather than one taking a flag, and that is forced rather than
 * chosen: `lightColorScheme` and `darkColorScheme` each take 49 parameters, thirteen of which
 * Ageha leaves at their defaults. A function reference would drop those defaults and demand all
 * 49, so the call has to be written out with named arguments -- twice.
 */
private fun lightScheme(t: AgehaScheme): ColorScheme = lightColorScheme(
		primary = t.primary,
		onPrimary = t.onPrimary,
		primaryContainer = t.primaryContainer,
		onPrimaryContainer = t.onPrimaryContainer,
		inversePrimary = t.inversePrimary,
		secondary = t.secondary,
		onSecondary = t.onSecondary,
		secondaryContainer = t.secondaryContainer,
		onSecondaryContainer = t.onSecondaryContainer,
		tertiary = t.tertiary,
		onTertiary = t.onTertiary,
		tertiaryContainer = t.tertiaryContainer,
		onTertiaryContainer = t.onTertiaryContainer,
		background = t.background,
		onBackground = t.onBackground,
		surface = t.surface,
		onSurface = t.onSurface,
		surfaceVariant = t.surfaceVariant,
		onSurfaceVariant = t.onSurfaceVariant,
		surfaceTint = t.surfaceTint,
		inverseSurface = t.inverseSurface,
		inverseOnSurface = t.inverseOnSurface,
		error = t.error,
		onError = t.onError,
		errorContainer = t.errorContainer,
		onErrorContainer = t.onErrorContainer,
		outline = t.outline,
		outlineVariant = t.outlineVariant,
		scrim = t.scrim,
		surfaceBright = t.surfaceBright,
		surfaceDim = t.surfaceDim,
		surfaceContainer = t.surfaceContainer,
		surfaceContainerHigh = t.surfaceContainerHigh,
		surfaceContainerHighest = t.surfaceContainerHighest,
		surfaceContainerLow = t.surfaceContainerLow,
		surfaceContainerLowest = t.surfaceContainerLowest,
)

private fun darkScheme(t: AgehaScheme): ColorScheme = darkColorScheme(
		primary = t.primary,
		onPrimary = t.onPrimary,
		primaryContainer = t.primaryContainer,
		onPrimaryContainer = t.onPrimaryContainer,
		inversePrimary = t.inversePrimary,
		secondary = t.secondary,
		onSecondary = t.onSecondary,
		secondaryContainer = t.secondaryContainer,
		onSecondaryContainer = t.onSecondaryContainer,
		tertiary = t.tertiary,
		onTertiary = t.onTertiary,
		tertiaryContainer = t.tertiaryContainer,
		onTertiaryContainer = t.onTertiaryContainer,
		background = t.background,
		onBackground = t.onBackground,
		surface = t.surface,
		onSurface = t.onSurface,
		surfaceVariant = t.surfaceVariant,
		onSurfaceVariant = t.onSurfaceVariant,
		surfaceTint = t.surfaceTint,
		inverseSurface = t.inverseSurface,
		inverseOnSurface = t.inverseOnSurface,
		error = t.error,
		onError = t.onError,
		errorContainer = t.errorContainer,
		onErrorContainer = t.onErrorContainer,
		outline = t.outline,
		outlineVariant = t.outlineVariant,
		scrim = t.scrim,
		surfaceBright = t.surfaceBright,
		surfaceDim = t.surfaceDim,
		surfaceContainer = t.surfaceContainer,
		surfaceContainerHigh = t.surfaceContainerHigh,
		surfaceContainerHighest = t.surfaceContainerHighest,
		surfaceContainerLow = t.surfaceContainerLow,
		surfaceContainerLowest = t.surfaceContainerLowest,
)

/**
 * Everything Ageha's design system carries that Material's own theme has nowhere to put.
 *
 * Material 3 covers colour, type and shape. It has no opinion on a spacing scale, no motion
 * tokens, no third ink, and certainly no notion of a reader background that must stay independent
 * of the app theme. Rather than scatter those as constants -- which is how a screen ends up
 * defining its own -- they ride alongside `MaterialTheme` on their own composition local.
 */
@Immutable
class AgehaExtras(
	val spacing: AgehaSpacing,
	val motion: AgehaMotion,
	val skin: AgehaSkin,
)

internal val LocalAgehaExtras = staticCompositionLocalOf {
	AgehaExtras(AgehaSpacing, AgehaMotion, ResolvedTheme.EMBER.skin())
}

/**
 * Ageha's theme. Wrap the whole window in it once.
 *
 * @param mode which theme to render. [AgehaThemeMode.SYSTEM] resolves through [systemInDarkTheme].
 * @param systemInDarkTheme the OS preference. Passed in rather than read here so the gallery can
 *   show every scheme side by side in one process, and so a screenshot test is not at the mercy
 *   of whatever the CI machine's desktop is set to.
 */
@Composable
fun AgehaTheme(
	mode: AgehaThemeMode = AgehaThemeMode.SYSTEM,
	systemInDarkTheme: Boolean = false,
	content: @Composable () -> Unit,
) {
	val resolved = mode.resolve(systemInDarkTheme)
	val extras = AgehaExtras(AgehaSpacing, AgehaMotion, resolved.skin())
	CompositionLocalProvider(LocalAgehaExtras provides extras) {
		MaterialTheme(
			colorScheme = with(resolved.tokens()) {
				if (resolved == ResolvedTheme.LIGHT) lightScheme(this) else darkScheme(this)
			},
			typography = agehaTypography(),
			shapes = resolved.shapes(),
			content = content,
		)
	}
}

/** Ageha's non-Material tokens, at the same call site style as `MaterialTheme.colorScheme`. */
object AgehaTheme {
	val spacing: AgehaSpacing
		@Composable get() = LocalAgehaExtras.current.spacing

	val motion: AgehaMotion
		@Composable get() = LocalAgehaExtras.current.motion

	val skin: AgehaSkin
		@Composable get() = LocalAgehaExtras.current.skin
}
