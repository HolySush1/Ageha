/*
 * GENERATED FILE -- DO NOT EDIT BY HAND.
 *
 * Produced by `./gradlew :tools:brandkit:generatePalette` from the brand constants and the two
 * skin transcriptions in tools/brandkit/.../Brand.kt. Editing a value here will be overwritten
 * the next time the generator runs, and the contrast tests in this module will not know you
 * meant it.
 *
 * The derivation, the places it departs from stock Material 3, and the two skins are documented
 * in docs/DESIGN.md 2 and 11.
 */
package app.ageha.core.designsystem

import androidx.compose.ui.graphics.Color

/** One theme's worth of colour. */
internal class AgehaScheme(
	val primary: Color,
	val onPrimary: Color,
	val primaryContainer: Color,
	val onPrimaryContainer: Color,
	val inversePrimary: Color,
	val secondary: Color,
	val onSecondary: Color,
	val secondaryContainer: Color,
	val onSecondaryContainer: Color,
	val tertiary: Color,
	val onTertiary: Color,
	val tertiaryContainer: Color,
	val onTertiaryContainer: Color,
	val background: Color,
	val onBackground: Color,
	val surface: Color,
	val onSurface: Color,
	val surfaceVariant: Color,
	val onSurfaceVariant: Color,
	val surfaceTint: Color,
	val inverseSurface: Color,
	val inverseOnSurface: Color,
	val error: Color,
	val onError: Color,
	val errorContainer: Color,
	val onErrorContainer: Color,
	val outline: Color,
	val outlineVariant: Color,
	val scrim: Color,
	val surfaceBright: Color,
	val surfaceDim: Color,
	val surfaceContainer: Color,
	val surfaceContainerHigh: Color,
	val surfaceContainerHighest: Color,
	val surfaceContainerLow: Color,
	val surfaceContainerLowest: Color,
) {
	/** Every role by name, in declaration order. */
	val all: Map<String, Color> = linkedMapOf(
		"primary" to primary,
		"onPrimary" to onPrimary,
		"primaryContainer" to primaryContainer,
		"onPrimaryContainer" to onPrimaryContainer,
		"inversePrimary" to inversePrimary,
		"secondary" to secondary,
		"onSecondary" to onSecondary,
		"secondaryContainer" to secondaryContainer,
		"onSecondaryContainer" to onSecondaryContainer,
		"tertiary" to tertiary,
		"onTertiary" to onTertiary,
		"tertiaryContainer" to tertiaryContainer,
		"onTertiaryContainer" to onTertiaryContainer,
		"background" to background,
		"onBackground" to onBackground,
		"surface" to surface,
		"onSurface" to onSurface,
		"surfaceVariant" to surfaceVariant,
		"onSurfaceVariant" to onSurfaceVariant,
		"surfaceTint" to surfaceTint,
		"inverseSurface" to inverseSurface,
		"inverseOnSurface" to inverseOnSurface,
		"error" to error,
		"onError" to onError,
		"errorContainer" to errorContainer,
		"onErrorContainer" to onErrorContainer,
		"outline" to outline,
		"outlineVariant" to outlineVariant,
		"scrim" to scrim,
		"surfaceBright" to surfaceBright,
		"surfaceDim" to surfaceDim,
		"surfaceContainer" to surfaceContainer,
		"surfaceContainerHigh" to surfaceContainerHigh,
		"surfaceContainerHighest" to surfaceContainerHighest,
		"surfaceContainerLow" to surfaceContainerLow,
		"surfaceContainerLowest" to surfaceContainerLowest,
	)
}

/**
 * The tokens Material has no role for: the handoff's third ink, its two hairlines,
 * its inset fill, the literal accent and its border tint, the two status colours, and
 * the two stops of the placeholder gradient drawn where cover art has not arrived.
 *
 * Read these through `AgehaTheme.skin` rather than directly -- see AgehaSkin.kt.
 */
internal class AgehaSkinScheme(
	val accent: Color,
	val accentLine: Color,
	val inkFaint: Color,
	val line: Color,
	val lineStrong: Color,
	val inset: Color,
	val ok: Color,
	val warn: Color,
	val coverHigh: Color,
	val coverLow: Color,
)

/** Every colour Ageha uses, in every theme. No screen may define its own. */
internal object AgehaColorTokens {

	/** The light scheme. */
	val Light = AgehaScheme(
		primary = Color(0xFF4B5C92),
		onPrimary = Color(0xFFF5F1E8),
		primaryContainer = Color(0xFFDBE1FF),
		onPrimaryContainer = Color(0xFF334478),
		inversePrimary = Color(0xFFB4C5FF),
		secondary = Color(0xFF595E72),
		onSecondary = Color(0xFFF5F1E8),
		secondaryContainer = Color(0xFFDDE1F9),
		onSecondaryContainer = Color(0xFF414659),
		tertiary = Color(0xFFAF301D),
		onTertiary = Color(0xFFF5F1E8),
		tertiaryContainer = Color(0xFFFFDAD4),
		onTertiaryContainer = Color(0xFF8D1606),
		background = Color(0xFFF5F1E8),
		onBackground = Color(0xFF1C1C14),
		surface = Color(0xFFF5F1E8),
		onSurface = Color(0xFF1C1C14),
		surfaceVariant = Color(0xFFE7E3CC),
		onSurfaceVariant = Color(0xFF494737),
		surfaceTint = Color(0xFF4B5C92),
		inverseSurface = Color(0xFF323128),
		inverseOnSurface = Color(0xFFF5F1E3),
		error = Color(0xFFAF301D),
		onError = Color(0xFFF5F1E8),
		errorContainer = Color(0xFFFFDAD4),
		onErrorContainer = Color(0xFF8D1606),
		outline = Color(0xFF7A7865),
		outlineVariant = Color(0xFFCAC7B1),
		scrim = Color(0xFF000000),
		surfaceBright = Color(0xFFFDF9EC),
		surfaceDim = Color(0xFFDEDACD),
		surfaceContainer = Color(0xFFF2EEE0),
		surfaceContainerHigh = Color(0xFFECE8DB),
		surfaceContainerHighest = Color(0xFFE6E2D5),
		surfaceContainerLow = Color(0xFFF8F4E6),
		surfaceContainerLowest = Color(0xFFFDF9F0),
	)

	/** The Ember scheme. */
	val Ember = AgehaScheme(
		primary = Color(0xFFC73624),
		onPrimary = Color(0xFFF5F1E8),
		primaryContainer = Color(0xFF3C1712),
		onPrimaryContainer = Color(0xFFF2E9E4),
		inversePrimary = Color(0xFFB42818),
		secondary = Color(0xFFE7BDB6),
		onSecondary = Color(0xFF442925),
		secondaryContainer = Color(0xFF5D3F3A),
		onSecondaryContainer = Color(0xFFFFDAD4),
		tertiary = Color(0xFFC73624),
		onTertiary = Color(0xFFF5F1E8),
		tertiaryContainer = Color(0xFF3C1712),
		onTertiaryContainer = Color(0xFFF2E9E4),
		background = Color(0xFF0F0B0A),
		onBackground = Color(0xFFF2E9E4),
		surface = Color(0xFF0F0B0A),
		onSurface = Color(0xFFF2E9E4),
		surfaceVariant = Color(0xFF241A17),
		onSurfaceVariant = Color(0xFF98908D),
		surfaceTint = Color(0xFFD9432F),
		inverseSurface = Color(0xFFEDE0DC),
		inverseOnSurface = Color(0xFF362F2D),
		error = Color(0xFFC73624),
		onError = Color(0xFFF5F1E8),
		errorContainer = Color(0xFF3C1712),
		onErrorContainer = Color(0xFFF2E9E4),
		outline = Color(0xFFA08C87),
		outlineVariant = Color(0xFF241F1D),
		scrim = Color(0xFF000000),
		surfaceBright = Color(0xFF2B1F1B),
		surfaceDim = Color(0xFF0F0B0A),
		surfaceContainer = Color(0xFF1E1715),
		surfaceContainerHigh = Color(0xFF241A17),
		surfaceContainerHighest = Color(0xFF2B1F1B),
		surfaceContainerLow = Color(0xFF1A1210),
		surfaceContainerLowest = Color(0xFF0F0B0A),
	)

	/** The Glass scheme. */
	val Glass = AgehaScheme(
		primary = Color(0xFF695DCE),
		onPrimary = Color(0xFFF5F1E8),
		primaryContainer = Color(0xFF2A2945),
		onPrimaryContainer = Color(0xFFF0F2F7),
		inversePrimary = Color(0xFF5A4DBE),
		secondary = Color(0xFFC8C3DC),
		onSecondary = Color(0xFF302E41),
		secondaryContainer = Color(0xFF474459),
		onSecondaryContainer = Color(0xFFE4DFF9),
		tertiary = Color(0xFF695DCE),
		onTertiary = Color(0xFFF5F1E8),
		tertiaryContainer = Color(0xFF2A2945),
		onTertiaryContainer = Color(0xFFF0F2F7),
		background = Color(0xFF12141A),
		onBackground = Color(0xFFF0F2F7),
		surface = Color(0xFF12141A),
		onSurface = Color(0xFFF0F2F7),
		surfaceVariant = Color(0xFF22242A),
		onSurfaceVariant = Color(0xFF9FA1A6),
		surfaceTint = Color(0xFF8B7FF2),
		inverseSurface = Color(0xFFE3E2E6),
		inverseOnSurface = Color(0xFF303034),
		error = Color(0xFF695DCE),
		onError = Color(0xFFF5F1E8),
		errorContainer = Color(0xFF2A2945),
		onErrorContainer = Color(0xFFF0F2F7),
		outline = Color(0xFF8E9099),
		outlineVariant = Color(0xFF292B30),
		scrim = Color(0xFF000000),
		surfaceBright = Color(0xFF292B30),
		surfaceDim = Color(0xFF12141A),
		surfaceContainer = Color(0xFF1C1E24),
		surfaceContainerHigh = Color(0xFF22242A),
		surfaceContainerHighest = Color(0xFF292B30),
		surfaceContainerLow = Color(0xFF191B23),
		surfaceContainerLowest = Color(0xFF12141A),
	)

	/** The AMOLED scheme. */
	val Amoled = AgehaScheme(
		primary = Color(0xFFB4C5FF),
		onPrimary = Color(0xFF1B2D60),
		primaryContainer = Color(0xFF334478),
		onPrimaryContainer = Color(0xFFDBE1FF),
		inversePrimary = Color(0xFF4B5C92),
		secondary = Color(0xFFC1C5DD),
		onSecondary = Color(0xFF2B3042),
		secondaryContainer = Color(0xFF414659),
		onSecondaryContainer = Color(0xFFDDE1F9),
		tertiary = Color(0xFFFFB4A6),
		onTertiary = Color(0xFF660700),
		tertiaryContainer = Color(0xFF8D1606),
		onTertiaryContainer = Color(0xFFFFDAD4),
		background = Color(0xFF000000),
		onBackground = Color(0xFFE4E2E6),
		surface = Color(0xFF000000),
		onSurface = Color(0xFFE4E2E6),
		surfaceVariant = Color(0xFF45464F),
		onSurfaceVariant = Color(0xFFC5C6D0),
		surfaceTint = Color(0xFFB4C5FF),
		inverseSurface = Color(0xFFE4E2E6),
		inverseOnSurface = Color(0xFF303034),
		error = Color(0xFFFFB4A6),
		onError = Color(0xFF660700),
		errorContainer = Color(0xFF8D1606),
		onErrorContainer = Color(0xFFFFDAD4),
		outline = Color(0xFF8F909A),
		outlineVariant = Color(0xFF45464F),
		scrim = Color(0xFF000000),
		surfaceBright = Color(0xFF39393C),
		surfaceDim = Color(0xFF000000),
		surfaceContainer = Color(0xFF17171B),
		surfaceContainerHigh = Color(0xFF1F1F23),
		surfaceContainerHighest = Color(0xFF292A2D),
		surfaceContainerLow = Color(0xFF101114),
		surfaceContainerLowest = Color(0xFF000000),
	)
}

/** The non-Material tokens, per theme. */
internal object AgehaSkinTokens {

	/** The light scheme's. */
	val Light = AgehaSkinScheme(
		accent = Color(0xFFAF301D),
		accentLine = Color(0xFFC77364),
		inkFaint = Color(0xFF626059),
		line = Color(0xFFCAC7B1),
		lineStrong = Color(0xFF7A7865),
		inset = Color(0xFFEAE6DD),
		ok = Color(0xFF5FBF7A),
		warn = Color(0xFFD9A13F),
		coverHigh = Color(0xFFE6E2D5),
		coverLow = Color(0xFFECE8DB),
	)

	/** The Ember scheme's. */
	val Ember = AgehaSkinScheme(
		accent = Color(0xFFD9432F),
		accentLine = Color(0xFF922F22),
		inkFaint = Color(0xFF908783),
		line = Color(0xFF241F1D),
		lineStrong = Color(0xFF302A28),
		inset = Color(0xFF1B1614),
		ok = Color(0xFF5FBF7A),
		warn = Color(0xFFD9A13F),
		coverHigh = Color(0xFF4A2A22),
		coverLow = Color(0xFF241A17),
	)

	/** The Glass scheme's. */
	val Glass = AgehaSkinScheme(
		accent = Color(0xFF8B7FF2),
		accentLine = Color(0xFF6762A3),
		inkFaint = Color(0xFF919398),
		line = Color(0xFF292B30),
		lineStrong = Color(0xFF3C3E43),
		inset = Color(0xFF202227),
		ok = Color(0xFF5FBF7A),
		warn = Color(0xFFD9A13F),
		coverHigh = Color(0xFF3B3F63),
		coverLow = Color(0xFF1B1D2A),
	)

	/** The AMOLED scheme's. */
	val Amoled = AgehaSkinScheme(
		accent = Color(0xFFFFB4A6),
		accentLine = Color(0xFFA5756B),
		inkFaint = Color(0xFFB6B3B5),
		line = Color(0xFF45464F),
		lineStrong = Color(0xFF8F909A),
		inset = Color(0xFF0B0B0B),
		ok = Color(0xFF5FBF7A),
		warn = Color(0xFFD9A13F),
		coverHigh = Color(0xFF292A2D),
		coverLow = Color(0xFF1F1F23),
	)
}
