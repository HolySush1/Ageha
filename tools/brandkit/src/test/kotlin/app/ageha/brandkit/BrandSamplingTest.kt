package app.ageha.brandkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Pins the sampled brand colours to the image they were sampled from.
 *
 * [Brand.VERMILLION] is not a chosen colour -- it is a measurement, and a measurement with no
 * link back to its source is just a number somebody typed. If the logo is ever re-exported, re-
 * cropped, or saved at a different JPEG quality, the mean shifts and this fails, which is the
 * correct outcome: the accent and the seal it has to sit beside must not drift apart silently.
 */
class BrandSamplingTest {

	private val repoRoot = File(System.getProperty("ageha.repoRoot") ?: "../..")
	private val source by lazy { ImageIO.read(File(repoRoot, "brand/ageha-logo-source.jpg")) }

	@Test
	fun `the vermillion constant still matches the logo`() {
		val argb = source.toArgbArray()
		val ink = argb.filter { saturationOf(it) > 0.45 && it.red() > it.green() && it.red() > it.blue() }
		assertTrue(ink.size > 50_000) { "only ${ink.size} ink pixels found; the logo may have changed" }
		val mean = intArrayOf(
			ink.sumOf { it.red() } / ink.size,
			ink.sumOf { it.green() } / ink.size,
			ink.sumOf { it.blue() } / ink.size,
		)
		val expected = intArrayOf(
			Brand.VERMILLION.red(), Brand.VERMILLION.green(), Brand.VERMILLION.blue(),
		)
		for (channel in 0..2) {
			assertTrue(abs(mean[channel] - expected[channel]) <= 2) {
				"sampled vermillion drifted: measured ${mean.toList()}, Brand.VERMILLION is ${expected.toList()}"
			}
		}
	}

	/**
	 * The brief's `#F5F1E8` and the logo's own washi are within a just-noticeable difference.
	 *
	 * Asserted as a *bound* rather than an equality because they are deliberately not the same
	 * value -- see the note on [Brand.PAPER]. What must stay true is that the brief's paper is
	 * still recognisably the logo's paper, which is what a small bound says and an equality
	 * would not.
	 */
	@Test
	fun `the paper constant is still the logo's washi`() {
		val argb = source.toArgbArray()
		val w = source.width
		val washi = buildList {
			for (y in 200 until 860 step 3) {
				for (x in 200 until 860 step 3) {
					val p = argb[y * w + x]
					if (p.red() > 225 && warmthOf(p) in 7..27 && p.red() >= p.green() && p.green() >= p.blue()) {
						add(p)
					}
				}
			}
		}
		assertTrue(washi.size > 5_000) { "only ${washi.size} washi pixels found" }
		val mean = intArrayOf(
			washi.sumOf { it.red() } / washi.size,
			washi.sumOf { it.green() } / washi.size,
			washi.sumOf { it.blue() } / washi.size,
		)
		val paper = intArrayOf(Brand.PAPER.red(), Brand.PAPER.green(), Brand.PAPER.blue())
		for (channel in 0..2) {
			assertTrue(abs(mean[channel] - paper[channel]) <= 8) {
				"paper drifted from the logo's washi: measured ${mean.toList()}, Brand.PAPER is ${paper.toList()}"
			}
		}
	}

	@Test
	fun `contrast maths agrees with known WCAG values`() {
		// Black on white is exactly 21:1 by definition; it is the one value that can be checked
		// against the standard rather than against this implementation's own output.
		assertEquals(21.0, contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.001)
		assertEquals(1.0, contrastRatio(0xFF808080.toInt(), 0xFF808080.toInt()), 0.001)
	}

	private fun Int.red() = (this shr 16) and 0xFF
	private fun Int.green() = (this shr 8) and 0xFF
	private fun Int.blue() = this and 0xFF
}
