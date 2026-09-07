package app.ageha.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The palette's structural rules -- the ones a contrast check cannot see.
 *
 * Every one of these corresponds to a line in CLAUDE.md or the brief. They are asserted rather
 * than trusted because the tokens are generated: a change to the generator, or to Material's
 * colour library, can silently move a value that a human last looked at months ago.
 */
class AgehaPaletteTest {

	private val paper = Color(0xFFF5F1E8)
	private val sumi = Color(0xFF1A1A1D)
	private val black = Color(0xFF000000)
	private val white = Color(0xFFFFFFFF)

	/** The two skins' `--bg`, straight out of the handoff's `skins.css`. */
	private val emberBackground = Color(0xFF0F0B0A)
	private val glassBackground = Color(0xFF12141A)

	private val schemes = mapOf(
		"light" to AgehaColorTokens.Light,
		"ember" to AgehaColorTokens.Ember,
		"glass" to AgehaColorTokens.Glass,
		"amoled" to AgehaColorTokens.Amoled,
	)

	/**
	 * Every theme's window is the tone it was specified as.
	 *
	 * Light is still paper, from the brief. The two skins are their handoff values rather than
	 * sumi -- that is the whole substance of the decision recorded in docs/DESIGN.md 11, so it is
	 * asserted here where a future "fix" back to the brand tone would trip over it.
	 */
	@Test
	fun `each theme's window is the tone it was specified as`() {
		assertEquals(paper, AgehaColorTokens.Light.surface, "light surface must be the paper tone")
		assertEquals(paper, AgehaColorTokens.Light.background, "light background must be the paper tone")
		assertEquals(emberBackground, AgehaColorTokens.Ember.surface, "Ember must be the handoff's --bg")
		assertEquals(glassBackground, AgehaColorTokens.Glass.surface, "Glass must be the handoff's --bg")
		assertNotEquals(sumi, AgehaColorTokens.Ember.surface, "Ember is not the brand's sumi")
	}

	/**
	 * "Never pure white or pure black in chrome."
	 *
	 * Three roles are exempt and each for a stated reason: `scrim` is a translucent dimming
	 * overlay, where a warm tint would read as a stain over artwork; and the AMOLED variant's
	 * backmost surfaces are true black because an unlit pixel is the entire purpose of that
	 * variant. Everything else -- including the label colour on a filled button, which Material
	 * would ordinarily make `#FFFFFF` -- uses paper and sumi instead.
	 */
	@Test
	fun `no pure white or pure black outside the stated exemptions`() {
		val amoledExempt = setOf("background", "surface", "surfaceDim", "surfaceContainerLowest")
		for ((themeName, scheme) in schemes) {
			for ((role, colour) in scheme.all) {
				if (role == "scrim") continue
				if (themeName == "amoled" && role in amoledExempt) continue
				assertNotEquals(white, colour, "$themeName.$role is pure white")
				assertNotEquals(black, colour, "$themeName.$role is pure black")
			}
		}
	}

	@Test
	fun `amoled puts only the backmost surfaces at true black`() {
		val a = AgehaColorTokens.Amoled
		assertEquals(black, a.background)
		assertEquals(black, a.surface)
		assertEquals(black, a.surfaceContainerLowest)
		// Elevated containers stay on the sumi ramp. If they went black too, a card and a sheet
		// would be indistinguishable from the window behind them, and the variant would stop
		// being a theme and start being a bug.
		assertNotEquals(black, a.surfaceContainer, "amoled surfaceContainer must stay visible")
		assertNotEquals(black, a.surfaceContainerHigh, "amoled surfaceContainerHigh must stay visible")
		assertTrue(
			a.surfaceContainerHigh.luminanceApprox() > a.surfaceContainer.luminanceApprox(),
			"the amoled container ramp must still ascend",
		)
	}

	/**
	 * The dark theme's primary must be the derived light tone, not the raw seed.
	 *
	 * The brief calls this out specifically -- `#2B3A67` is far too dark to sit as `primary` on a
	 * dark surface. This asserts the derivation actually happened rather than the seed being
	 * pasted in, which is the single most likely way for a "use it as a seed" instruction to be
	 * quietly ignored.
	 */
	@Test
	fun `the brand dark primary is a light tone, not the raw seed`() {
		val seed = Color(0xFF2B3A67)
		assertNotEquals(seed, AgehaColorTokens.Amoled.primary)
		assertTrue(
			AgehaColorTokens.Amoled.primary.luminanceApprox() > 0.4,
			"the brand dark primary is too dark to carry text or sit on a dark surface",
		)
	}

	/**
	 * One accent. Error is deliberately the same hue as the accent in every theme.
	 *
	 * For the brand themes that is the hanko red standing in for Material's generic error red.
	 * For the skins it falls out of the handoff, whose `--bad` is literally `var(--accent)`.
	 */
	@Test
	fun `error and tertiary share the hanko red`() {
		for ((name, scheme) in schemes) {
			assertEquals(scheme.tertiary, scheme.error, "$name: error and tertiary must be one colour")
		}
	}

	/**
	 * Light neutrals are warm and the brand's dark neutrals are cool -- both halves of the brief.
	 *
	 * The skins add a third claim, and it is the one that makes them read as two designs rather
	 * than as one design in two accent colours: Ember's greys are warm all the way down and
	 * Glass's are cool. Neither is tinted by hand; each takes its hue from its own panel colour.
	 */
	@Test
	fun `neutrals carry the temperature their theme was built for`() {
		val lightWarmth = AgehaColorTokens.Light.surfaceContainer.warmth()
		val amoledWarmth = AgehaColorTokens.Amoled.surfaceContainer.warmth()
		val emberWarmth = AgehaColorTokens.Ember.surfaceContainer.warmth()
		val glassWarmth = AgehaColorTokens.Glass.surfaceContainer.warmth()
		assertTrue(lightWarmth > 0.01f, "light neutrals should be warm, got $lightWarmth")
		assertTrue(amoledWarmth < 0.0f, "the brand dark neutrals should be cool, got $amoledWarmth")
		assertTrue(emberWarmth > 0.0f, "Ember's neutrals should be warm, got $emberWarmth")
		assertTrue(glassWarmth < 0.0f, "Glass's neutrals should be cool, got $glassWarmth")
	}

	@Test
	fun `every role is defined in every theme`() {
		val expected = AgehaColorTokens.Light.all.keys
		assertTrue(expected.size >= 36) { "expected the full Material role set, found ${expected.size}" }
		for ((name, scheme) in schemes) {
			assertEquals(expected, scheme.all.keys, "$name is missing roles")
		}
	}

	private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

	/** Positive is warm (red above blue), negative is cool. */
	private fun Color.warmth(): Float = red - blue

	@Suppress("unused")
	private fun Color.distanceTo(other: Color): Float =
		abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)
}
