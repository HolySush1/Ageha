package app.ageha.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The themes Ageha ships. There is no "custom" option and no dynamic colour: the palette is the
 * brand, and a reader that recolours itself from the wallpaper is not the product.
 */
enum class AgehaThemeMode {
	/** Follows the operating system's light/dark preference. */
	SYSTEM,
	LIGHT,
	DARK,

	/**
	 * True black backgrounds. Worth having despite Ageha being a desktop app -- OLED laptop
	 * panels are now common, and people read at night.
	 */
	AMOLED,
	;

	fun isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
		SYSTEM -> systemInDarkTheme
		LIGHT -> false
		DARK, AMOLED -> true
	}
}

private fun lightScheme(): ColorScheme = with(AgehaColorTokens.Light) {
	lightColorScheme(
		primary = primary,
		onPrimary = onPrimary,
		primaryContainer = primaryContainer,
		onPrimaryContainer = onPrimaryContainer,
		inversePrimary = inversePrimary,
		secondary = secondary,
		onSecondary = onSecondary,
		secondaryContainer = secondaryContainer,
		onSecondaryContainer = onSecondaryContainer,
		tertiary = tertiary,
		onTertiary = onTertiary,
		tertiaryContainer = tertiaryContainer,
		onTertiaryContainer = onTertiaryContainer,
		background = background,
		onBackground = onBackground,
		surface = surface,
		onSurface = onSurface,
		surfaceVariant = surfaceVariant,
		onSurfaceVariant = onSurfaceVariant,
		surfaceTint = surfaceTint,
		inverseSurface = inverseSurface,
		inverseOnSurface = inverseOnSurface,
		error = error,
		onError = onError,
		errorContainer = errorContainer,
		onErrorContainer = onErrorContainer,
		outline = outline,
		outlineVariant = outlineVariant,
		scrim = scrim,
		surfaceBright = surfaceBright,
		surfaceDim = surfaceDim,
		surfaceContainer = surfaceContainer,
		surfaceContainerHigh = surfaceContainerHigh,
		surfaceContainerHighest = surfaceContainerHighest,
		surfaceContainerLow = surfaceContainerLow,
		surfaceContainerLowest = surfaceContainerLowest,
	)
}

private fun darkScheme(amoled: Boolean): ColorScheme {
	val t = if (amoled) AgehaColorTokens.Amoled else AgehaColorTokens.Dark
	return darkColorScheme(
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
}

/**
 * Everything Ageha's design system carries that Material's own theme has nowhere to put.
 *
 * Material 3 covers colour, type and shape. It has no opinion on a spacing scale, no motion
 * tokens, and certainly no notion of a reader background that must stay independent of the app
 * theme. Rather than scatter those as constants -- which is how a screen ends up defining its
 * own -- they ride alongside `MaterialTheme` on their own composition locals.
 */
@Immutable
class AgehaExtras(
	val spacing: AgehaSpacing,
	val motion: AgehaMotion,
)

internal val LocalAgehaExtras = staticCompositionLocalOf {
	AgehaExtras(AgehaSpacing, AgehaMotion)
}

/**
 * Ageha's theme. Wrap the whole window in it once.
 *
 * @param mode which theme to render. [AgehaThemeMode.SYSTEM] resolves through [systemInDarkTheme].
 * @param systemInDarkTheme the OS preference. Passed in rather than read here so the gallery can
 *   show all three schemes side by side in one process, and so a screenshot test is not at the
 *   mercy of whatever the CI machine's desktop is set to.
 */
@Composable
fun AgehaTheme(
	mode: AgehaThemeMode = AgehaThemeMode.SYSTEM,
	systemInDarkTheme: Boolean = false,
	content: @Composable () -> Unit,
) {
	val scheme = when {
		mode == AgehaThemeMode.AMOLED -> darkScheme(amoled = true)
		mode.isDark(systemInDarkTheme) -> darkScheme(amoled = false)
		else -> lightScheme()
	}
	CompositionLocalProvider(LocalAgehaExtras provides AgehaExtras(AgehaSpacing, AgehaMotion)) {
		MaterialTheme(
			colorScheme = scheme,
			typography = agehaTypography(),
			shapes = AgehaShapes,
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
}
