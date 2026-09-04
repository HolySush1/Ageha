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

	/**
	 * Glass, composited.
	 *
	 * Translucent chrome has no fixed background, so none of the pairs above say anything about
	 * it. What makes it testable is that [AgehaBackdrop] bounds the problem: it scrims arbitrary
	 * artwork down to [AgehaGlass.BACKDROP_SCRIM], so the worst backdrop a panel can ever sit on
	 * is the scrim applied to pure black or pure white. Everything real falls between those two.
	 *
	 * So this composites the actual stack -- artwork, scrim, glass fill -- and holds the result to
	 * the same 4.5:1 floor as any other body text. It is the test that stops someone lowering an
	 * alpha because it looked better on one screenshot.
	 */
	private fun over(backdrop: Color, fill: Color, alpha: Float) = Color(
		red = fill.red * alpha + backdrop.red * (1 - alpha),
		green = fill.green * alpha + backdrop.green * (1 - alpha),
		blue = fill.blue * alpha + backdrop.blue * (1 - alpha),
	)

	private fun glassFill(s: AgehaScheme, tone: GlassTone) = when (tone) {
		GlassTone.CHROME -> s.surfaceContainer
		GlassTone.PANEL -> s.surfaceContainerHigh
		GlassTone.RAISED -> s.surfaceContainerHighest
	}

	/** The two extremes of cover art. Every real cover composites to something between them. */
	private val worstArtwork = listOf("black artwork" to Color.Black, "white artwork" to Color.White)

	@TestFactory
	fun `body text on glass over the backdrop meets AA`(): List<DynamicTest> =
		themes.flatMap { (name, s) ->
			// RAISED is excluded here and tested on its own below: menus and dialogs float over
			// content, not over the scrimmed backdrop, so they get none of the scrim's help and
			// the honest test for them is the harsher one.
			listOf(GlassTone.CHROME, GlassTone.PANEL).flatMap { tone ->
				worstArtwork.map { (artLabel, art) ->
					DynamicTest.dynamicTest("$name: onSurface on $tone over $artLabel") {
						val backdrop = over(art, s.surface, AgehaGlass.BACKDROP_SCRIM)
						val glass = over(backdrop, glassFill(s, tone), tone.fillAlpha)
						val r = ratio(s.onSurface, glass)
						assertTrue(r >= 4.5) {
							"$name onSurface on $tone over $artLabel is %.2f:1, below the 4.5:1 AA floor"
								.format(r)
						}
					}
				}
			}
		}

	/**
	 * Menus and dialogs get no scrim, so they are held to the same floor over raw content.
	 *
	 * A dropdown opens over the library grid -- an arbitrary wall of cover art with no scrim
	 * between it and the menu. This is why [GlassTone.RAISED] is nearly opaque, and this is the
	 * test that keeps it that way.
	 */
	@TestFactory
	fun `body text on a raised glass panel meets AA over any content`(): List<DynamicTest> =
		themes.flatMap { (name, s) ->
			worstArtwork.map { (artLabel, art) ->
				DynamicTest.dynamicTest("$name: onSurface on RAISED over $artLabel") {
					val glass = over(art, glassFill(s, GlassTone.RAISED), GlassTone.RAISED.fillAlpha)
					val r = ratio(s.onSurface, glass)
					assertTrue(r >= 4.5) {
						"$name onSurface on RAISED over $artLabel is %.2f:1, below the 4.5:1 AA floor"
							.format(r)
					}
				}
			}
		}
}
