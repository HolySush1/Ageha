package app.ageha.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.math.pow

/**
 * WCAG contrast, enforced rather than eyeballed.
 *
 * The brief asks for AA on body text in all three themes and says explicitly to verify with a
 * checker. A checker run once verifies the palette on the day someone ran it; this verifies it on
 * every build, which is the difference that matters, because the failure mode here is a later
 * tweak to one token quietly dropping one pair below threshold.
 *
 * The luminance maths is reimplemented here from the WCAG 2.1 definition rather than reusing
 * Compose's `Color.luminance()`. The gallery uses Compose's; this uses its own. If either ever
 * drifted the two would disagree, and an independent implementation is the only way a test of a
 * value can catch the value's own helper being wrong.
 */
class AgehaContrastTest {

	private fun luminance(color: Color): Double {
		fun channel(c: Float): Double {
			val s = c.toDouble()
			return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
		}
		return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
	}

	private fun ratio(a: Color, b: Color): Double {
		val la = luminance(a)
		val lb = luminance(b)
		return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
	}

	private val themes = listOf(
		"light" to AgehaColorTokens.Light,
		"dark" to AgehaColorTokens.Dark,
		"amoled" to AgehaColorTokens.Amoled,
	)

	/** The pairs Material guarantees, plus the ones it does not. */
	private fun textPairs(s: AgehaScheme): List<Triple<String, Color, Color>> = listOf(
		Triple("onPrimary/primary", s.onPrimary, s.primary),
		Triple("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
		Triple("onSecondary/secondary", s.onSecondary, s.secondary),
		Triple("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
		Triple("onTertiary/tertiary", s.onTertiary, s.tertiary),
		Triple("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
		Triple("onError/error", s.onError, s.error),
		Triple("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer),
		Triple("onBackground/background", s.onBackground, s.background),
		Triple("onSurface/surface", s.onSurface, s.surface),
		Triple("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant),
		Triple("inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface),
		// Material's own contrast curves cover on-colour against the *matched* surface. They do
		// not cover onSurface against every step of the container ramp, and Ageha puts body text
		// on all of them -- a dialog is surfaceContainerHigh, a card is surfaceContainer. Two of
		// Ageha's anchors (paper, sumi) also move `surface` off the tone Material assumed, so
		// these are the pairs most likely to break and the least likely to be noticed.
		Triple("onSurface/surfaceContainerLowest", s.onSurface, s.surfaceContainerLowest),
		Triple("onSurface/surfaceContainerLow", s.onSurface, s.surfaceContainerLow),
		Triple("onSurface/surfaceContainer", s.onSurface, s.surfaceContainer),
		Triple("onSurface/surfaceContainerHigh", s.onSurface, s.surfaceContainerHigh),
		Triple("onSurface/surfaceContainerHighest", s.onSurface, s.surfaceContainerHighest),
		Triple("onSurface/surfaceBright", s.onSurface, s.surfaceBright),
		Triple("onSurface/surfaceDim", s.onSurface, s.surfaceDim),
		Triple("onSurfaceVariant/surface", s.onSurfaceVariant, s.surface),
	)

	@TestFactory
	fun `body text meets WCAG AA in every theme`(): List<DynamicTest> = themes.flatMap { (name, scheme) ->
		textPairs(scheme).map { (label, fg, bg) ->
			DynamicTest.dynamicTest("$name: $label") {
				val r = ratio(fg, bg)
				assertTrue(r >= 4.5) { "$name $label is %.2f:1, below the 4.5:1 AA floor".format(r) }
			}
		}
	}

	/**
	 * Borders and dividers are not text, so AA does not apply, but they still have to be visible.
	 * WCAG's non-text threshold is 3:1; `outlineVariant` is deliberately allowed to be quieter
	 * than that because it separates rather than delimits, so only `outline` is held to it.
	 */
	@TestFactory
	fun `outline is visible against its surfaces`(): List<DynamicTest> = themes.flatMap { (name, s) ->
		listOf("surface" to s.surface, "surfaceContainer" to s.surfaceContainer).map { (label, bg) ->
			DynamicTest.dynamicTest("$name: outline/$label") {
				val r = ratio(s.outline, bg)
				assertTrue(r >= 3.0) { "$name outline on $label is %.2f:1, below the 3:1 floor".format(r) }
			}
		}
	}
}
