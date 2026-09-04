package app.ageha.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs

/**
 * The Continue Reading hero paints itself with a colour taken from somebody else's artwork.
 *
 * That is the whole risk in [CoverAccent], and it is not a risk a screenshot can retire: the input
 * is every cover on every source, and the failure mode is a title nobody can read on a panel that
 * looked fine on the four covers someone happened to try. So the guarantee is stated as a property
 * and checked across the colour cube -- if any seed at all produces a panel whose text drops below
 * AA, one of these fails and names the seed that did it.
 */
class CoverAccentTest {

	/**
	 * A coarse sweep of the RGB cube: 6 steps per channel, 216 seeds.
	 *
	 * Coarse deliberately. [CoverAccent.harmonise] is continuous in hue and clamps saturation and
	 * lightness outright, so the interesting behaviour is at the corners and along the greys --
	 * both of which this hits -- and a finer sweep would only make the failure list longer.
	 */
	private val seeds: List<Color> = buildList {
		val steps = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
		for (r in steps) for (g in steps) for (b in steps) add(Color(r, g, b))
	}

	@TestFactory
	fun `hero text meets AA on any cover in any theme`(): List<DynamicTest> =
		listOf("dark" to true, "light" to false).flatMap { (themeName, isDark) ->
			seeds.map { seed ->
				DynamicTest.dynamicTest("$themeName: ${seed.hex()}") {
					val accent = CoverAccent.colorsFor(seed, isDark)
					val title = CoverAccent.contrast(accent.content, accent.container)
					assertTrue(title >= CoverAccent.AA_FLOOR) {
						"$themeName title on ${seed.hex()} is %.2f:1, below the AA floor".format(title)
					}
					// The one that actually binds the constants. See MUTED_ALPHA.
					val muted = CoverAccent.contrast(accent.mutedContent, accent.container)
					assertTrue(muted >= CoverAccent.AA_FLOOR) {
						"$themeName metadata on ${seed.hex()} is %.2f:1, below the AA floor"
							.format(muted)
					}
				}
			}
		}

	/**
	 * The panel has to sit on the theme's side of the room.
	 *
	 * A dark theme with a pale hero, or the reverse, is not a contrast bug -- both pass the test
	 * above -- but it is the hero shouting over the window it sits in, which is the thing that
	 * made the old blurred banner feel wrong in the first place.
	 */
	@TestFactory
	fun `the panel follows the theme rather than the cover`(): List<DynamicTest> =
		seeds.map { seed ->
			DynamicTest.dynamicTest(seed.hex()) {
				val dark = CoverAccent.colorsFor(seed, isDark = true).container
				val light = CoverAccent.colorsFor(seed, isDark = false).container
				assertTrue(CoverAccent.toHsl(dark).third < 0.35f) {
					"dark panel for ${seed.hex()} is too light"
				}
				assertTrue(CoverAccent.toHsl(light).third > 0.65f) {
					"light panel for ${seed.hex()} is too dark"
				}
			}
		}

	/** Hue survives. Losing it would make every hero the same colour, which is the point of none. */
	@Test
	fun `the cover's hue is kept`() {
		val vermillion = Color(0xFFE34A33)
		val hue = CoverAccent.toHsl(vermillion).first
		for (isDark in listOf(true, false)) {
			val panel = CoverAccent.toHsl(CoverAccent.colorsFor(vermillion, isDark).container).first
			assertTrue(abs(panel - hue) < 1f) {
				"hue drifted from $hue to $panel (isDark=$isDark)"
			}
		}
	}

	/**
	 * A grey cover stays grey.
	 *
	 * Saturating an achromatic average would invent a hue out of rounding noise, and the tint a
	 * monochrome cover got would then change every time its source re-encoded the thumbnail.
	 */
	@Test
	fun `a grey cover is not given a hue`() {
		val panel = CoverAccent.colorsFor(Color(0.5f, 0.5f, 0.5f), isDark = true).container
		assertEquals(0f, CoverAccent.toHsl(panel).second, 0.001f)
	}

	/**
	 * The average is taken in linear light, not in sRGB.
	 *
	 * Half black and half white is the case that separates the two: averaging the encoded values
	 * gives 0.5, which is much darker than the mid-grey those pixels actually look like. In linear
	 * light the answer encodes back to roughly 0.74 -- and a page of black ink on white paper,
	 * which is most covers, should hand the panel the paper rather than the ink.
	 */
	@Test
	fun `black and white average to perceptual mid-grey`() {
		val pixels = List(50) { 0xFF000000.toInt() } + List(50) { 0xFFFFFFFF.toInt() }
		val average = requireNotNull(CoverAccent.average(pixels))
		assertEquals(0.735f, average.red, 0.02f)
		assertEquals(average.red, average.blue, 0.001f)
	}

	/** Transparent padding is not part of the artwork and must not be averaged into it. */
	@Test
	fun `fully transparent pixels are ignored`() {
		val red = 0xFFFF0000.toInt()
		val transparent = 0x00FFFFFF
		val average = requireNotNull(CoverAccent.average(List(10) { red } + List(90) { transparent }))
		assertEquals(1f, average.red, 0.001f)
		assertEquals(0f, average.green, 0.001f)
	}

	/** Nothing to average is not the same as black, and must not be reported as a colour. */
	@Test
	fun `an entirely transparent cover yields no accent`() {
		assertEquals(null, CoverAccent.average(List(20) { 0x00000000 }))
	}

	private fun Color.hex(): String = "#%02X%02X%02X".format(
		(red * 255).toInt(),
		(green * 255).toInt(),
		(blue * 255).toInt(),
	)
}
