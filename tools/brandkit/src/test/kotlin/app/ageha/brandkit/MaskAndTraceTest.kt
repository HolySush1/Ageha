package app.ageha.brandkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The geometry primitives, on shapes whose right answer is known by construction.
 *
 * The pipeline's real inputs are a photograph of a stamp, where "correct" is a judgement call.
 * These use squares and rings instead, where it is not.
 */
class MaskAndTraceTest {

	private fun mask(width: Int, height: Int, fill: (Int, Int) -> Boolean) =
		Mask(width, height).also {
			for (y in 0 until height) for (x in 0 until width) it[x, y] = fill(x, y)
		}

	@Test
	fun `flood fill from the border cannot reach an enclosed region`() {
		// A hollow square: the interior is unreachable from outside, which is the property the
		// background key depends on to tell the butterfly from the checkerboard.
		val ring = mask(20, 20) { x, y ->
			(x in 4..15 && y in 4..15) && !(x in 6..13 && y in 6..13)
		}
		val outside = ring.invert().reachableFromBorder()
		assertTrue(outside[0, 0], "the border itself is outside")
		assertTrue(!outside[10, 10], "the enclosed centre must not be reachable")
	}

	@Test
	fun `filling holes closes an enclosed cavity and leaves the exterior alone`() {
		val ring = mask(20, 20) { x, y ->
			(x in 4..15 && y in 4..15) && !(x in 6..13 && y in 6..13)
		}
		val filled = ring.fillHoles()
		assertTrue(filled[10, 10], "the cavity should be filled")
		assertTrue(!filled[0, 0], "the exterior must stay empty")
		assertEquals(12 * 12, filled.count(), "the result should be the solid square")
	}

	@Test
	fun `closing bridges a thin gap without inflating the shape`() {
		// Two blocks separated by a 3px channel -- the same relationship the butterfly's wings
		// have across an ink vein.
		val split = mask(40, 20) { x, y -> y in 5..14 && (x in 5..17 || x in 21..33) }
		assertEquals(2, split.componentIndices().size)

		val closed = split.close(radius = 2)
		assertEquals(1, closed.componentIndices().size, "a radius-2 close should bridge a 3px gap")

		val box = closed.boundingBox()
		// The outer boundary must land where it started. Dilation alone would push it outward by
		// the radius; the erosion half of the close is what pulls it back.
		assertEquals(5, box[0]); assertEquals(5, box[1])
		assertEquals(33, box[2]); assertEquals(14, box[3])
	}

	@Test
	fun `closing leaves a gap wider than the structuring element alone`() {
		val split = mask(40, 20) { x, y -> y in 5..14 && (x in 5..15 || x in 25..35) }
		assertEquals(2, split.close(radius = 2).componentIndices().size) {
			"a 9px gap must survive a radius-2 close, or the disc would fuse with the butterfly"
		}
	}

	@Test
	fun `tracing a square yields one ring at the pixel boundary`() {
		val square = mask(20, 20) { x, y -> x in 5..14 && y in 5..14 }
		val rings = Trace.rings(square)
		assertEquals(1, rings.size)
		assertEquals(100.0, Trace.area(rings[0]), 0.001, "the ring should enclose the square's area")
	}

	@Test
	fun `simplifying a square keeps its four corners`() {
		val square = mask(40, 40) { x, y -> x in 10..29 && y in 10..29 }
		val simplified = Trace.simplify(Trace.rings(square).first(), epsilon = 0.5)
		// Four corners, plus the split point RDP needs to work on a closed ring.
		assertTrue(simplified.size in 4..6) { "a square simplified to ${simplified.size} points" }
		assertEquals(400.0, Trace.area(simplified), 1.0)
	}

	@Test
	fun `a shape with a hole traces as two rings`() {
		val ring = mask(30, 30) { x, y ->
			(x in 5..24 && y in 5..24) && !(x in 12..17 && y in 12..17)
		}
		val rings = Trace.rings(ring)
		assertEquals(2, rings.size, "outer boundary and hole")
		// Sorted largest first, so the outer ring leads. Even-odd filling then makes the inner
		// ring a knockout, which is how the butterfly is cut out of the seal.
		assertTrue(Trace.area(rings[0]) > Trace.area(rings[1]))
		assertEquals(400.0, Trace.area(rings[0]), 0.001)
		assertEquals(36.0, Trace.area(rings[1]), 0.001)
	}

	@Test
	fun `components come back largest first`() {
		val blobs = mask(40, 20) { x, y ->
			(y in 2..17 && x in 2..17) || (y in 8..11 && x in 25..28)
		}
		val components = blobs.components()
		assertEquals(2, components.size)
		assertTrue(components[0].count() > components[1].count())
	}
}
