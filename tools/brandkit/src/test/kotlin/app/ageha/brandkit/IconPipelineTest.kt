package app.ageha.brandkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * The icon pipeline, checked against the real artwork.
 *
 * Each assertion here corresponds to a way the pipeline has actually gone wrong. The knockout
 * count caught the butterfly being found as seven disconnected wing fragments; the disc's
 * bounding box caught a hairline of JPEG blur around the seal being mistaken for a knockout and
 * hole-filled into the entire stamp. Neither failure was visible from the code -- both produced
 * a plausible-looking file with no butterfly in it.
 */
class IconPipelineTest {

	private val repoRoot = File(System.getProperty("ageha.repoRoot") ?: "../..")
	private val analysis by lazy {
		IconGenerator.analyse(ImageIO.read(File(repoRoot, "brand/ageha-logo-source.jpg")))
	}

	@Test
	fun `the fake checkerboard is keyed out and the stamp is kept`() {
		val total = analysis.width * analysis.height
		val opaque = analysis.opaque.count()
		// The stamp fills most but not all of the frame. Far outside this band means the key
		// either ate the artwork or kept the background.
		assertTrue(opaque.toDouble() / total in 0.55..0.80) {
			"kept $opaque of $total pixels; the background key is wrong"
		}
		for ((x, y) in listOf(0 to 0, analysis.width - 1 to 0, 0 to analysis.height - 1)) {
			assertTrue(!analysis.opaque[x, y]) { "($x,$y) should have been keyed out" }
		}
	}

	@Test
	fun `the butterfly is recovered as one silhouette`() {
		assertEquals(1, analysis.butterfly.componentIndices().size, "the butterfly must close into one shape")
		val box = analysis.butterfly.boundingBox()
		val width = box[2] - box[0]
		val height = box[3] - box[1]
		// A swallowtail is wider than it is tall, and spans most of the seal.
		assertTrue(width > height) { "butterfly is ${width}x$height; wings should make it wider than tall" }
		assertTrue(width > analysis.width * 0.6) { "butterfly is only $width px wide" }
		val centre = (box[0] + box[2]) / 2
		assertTrue(abs(centre - analysis.width / 2) < 40) {
			"butterfly centre is at $centre, not near the middle"
		}
	}

	@Test
	fun `the disc is small and sits below the body`() {
		val box = analysis.circle.boundingBox()
		val width = box[2] - box[0]
		val height = box[3] - box[1]
		assertTrue(width in 80..220) { "disc is $width px wide; expected the small circle" }
		assertTrue(abs(width - height) < 20) { "disc is ${width}x$height; it should be round" }
		assertTrue(box[1] > analysis.height / 2) { "the disc belongs in the lower half of the seal" }
	}

	@Test
	fun `the silhouette is solid, not a shell of the veining`() {
		val filled = analysis.butterfly.count()
		val box = analysis.butterfly.boundingBox()
		val boxArea = (box[2] - box[0]) * (box[3] - box[1])
		// A butterfly does not fill its bounding box -- the gaps between the wings are real -- but
		// if hole filling had failed, this would be the thin tracery of the veins instead.
		assertTrue(filled > boxArea * 0.3) { "the silhouette looks too sparse to have been filled" }
	}

	@Test
	fun `the simplified mark renders as ink with a knockout`() {
		val mark = IconGenerator.simplifiedMark(analysis)
		val image = IconGenerator.renderMark(mark, 32, Color(Brand.VERMILLION, true))
		var ink = 0
		var clear = 0
		for (y in 0 until 32) {
			for (x in 0 until 32) {
				if ((image.getRGB(x, y) ushr 24) > 128) ink++ else clear++
			}
		}
		assertTrue(ink > 300) { "only $ink of 1024 pixels are inked; the seal did not render" }
		assertTrue(clear > 100) { "only $clear pixels are clear; the butterfly knockout is missing" }
		// Near the top centre of the mark sits the butterfly's body, which is knocked out. If the
		// even-odd fill rule were ever lost, this pixel would come back opaque.
		assertTrue((image.getRGB(16, 13) ushr 24) < 128) { "the knockout should be transparent at the body" }
	}

	@Test
	fun `the simplified mark stays a small number of points`() {
		val mark = IconGenerator.simplifiedMark(analysis)
		val points = mark.rings.sumOf { it.size }
		// The whole purpose of simplifying is that this stays small. The unsimplified crack-
		// following outline of this artwork runs to tens of thousands of points.
		assertTrue(points in 200..2000) { "the simplified mark has $points points" }
		assertEquals(3, mark.rings.size, "expected seal, butterfly and disc")
	}
}
