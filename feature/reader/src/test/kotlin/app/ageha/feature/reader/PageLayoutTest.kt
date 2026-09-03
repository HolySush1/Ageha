package app.ageha.feature.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Double-page pairing, which is the part of a paged reader that is easy to get subtly wrong.
 *
 * Cover offset is worth this much testing because being off by one page does not *look* broken --
 * every page renders, the count is right, and the reader just sees artwork that does not line up.
 * That is far harder to notice in review than a crash.
 */
class PageLayoutTest {

	private fun pagesOf(spreads: List<Spread>) = spreads.map { it.pages }

	@Test
	fun `single page mode is one spread per page`() {
		val spreads = PageLayout.spreads(pageCount = 4, doublePage = false, coverOffset = true)
		assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), pagesOf(spreads))
	}

	/**
	 * The bound-book layout. Page 1 is a right-hand page on its own, and every pair after it is
	 * one physical sheet: 2-3, 4-5. This is what makes artwork drawn across a fold line up.
	 */
	@Test
	fun `cover offset leaves the first page alone and pairs the rest`() {
		val spreads = PageLayout.spreads(pageCount = 7, doublePage = true, coverOffset = true)
		assertEquals(
			listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5, 6)),
			pagesOf(spreads),
		)
	}

	@Test
	fun `without cover offset pairing starts from the first page`() {
		val spreads = PageLayout.spreads(pageCount = 6, doublePage = true, coverOffset = false)
		assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5)), pagesOf(spreads))
	}

	/**
	 * The two settings must actually differ for the same input -- if they ever agreed, the toggle
	 * would be a control that does nothing, which is worse than not having it.
	 */
	@Test
	fun `the offset setting changes the pairing`() {
		val on = PageLayout.spreads(8, doublePage = true, coverOffset = true)
		val off = PageLayout.spreads(8, doublePage = true, coverOffset = false)
		assertTrue(pagesOf(on) != pagesOf(off))
	}

	@Test
	fun `a trailing odd page stands alone rather than being dropped`() {
		val spreads = PageLayout.spreads(pageCount = 4, doublePage = true, coverOffset = true)
		assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), pagesOf(spreads))
		// Every page must appear exactly once, whatever the pairing.
		assertEquals(listOf(0, 1, 2, 3), spreads.flatMap { it.pages })
	}

	@Test
	fun `every page appears exactly once for any count and setting`() {
		for (count in 0..25) {
			for (double in listOf(true, false)) {
				for (offset in listOf(true, false)) {
					val flat = PageLayout.spreads(count, double, offset).flatMap { it.pages }
					assertEquals((0 until count).toList(), flat) {
						"count=$count double=$double offset=$offset lost or repeated a page"
					}
				}
			}
		}
	}

	@Test
	fun `an empty chapter has no spreads`() {
		assertTrue(PageLayout.spreads(0, doublePage = true, coverOffset = true).isEmpty())
	}

	@Test
	fun `a page maps back to the spread that holds it`() {
		val spreads = PageLayout.spreads(7, doublePage = true, coverOffset = true)
		assertEquals(0, PageLayout.spreadOf(spreads, 0))
		assertEquals(1, PageLayout.spreadOf(spreads, 1))
		assertEquals(1, PageLayout.spreadOf(spreads, 2))
		assertEquals(3, PageLayout.spreadOf(spreads, 6))
		assertEquals(-1, PageLayout.spreadOf(spreads, 99))
	}

	/**
	 * In a right-to-left book the lower-numbered page of a pair sits on the *right*. Rendering
	 * them ascending left-to-right shows two correct pages in the wrong order.
	 */
	@Test
	fun `right to left swaps the pages within a spread`() {
		val spread = Spread(listOf(3, 4))
		assertEquals(listOf(3, 4), PageLayout.orderedPages(spread, rightToLeft = false))
		assertEquals(listOf(4, 3), PageLayout.orderedPages(spread, rightToLeft = true))
	}

	@Test
	fun `a single page spread is unaffected by direction`() {
		val spread = Spread(listOf(0))
		assertEquals(listOf(0), PageLayout.orderedPages(spread, rightToLeft = true))
		assertEquals(listOf(0), PageLayout.orderedPages(spread, rightToLeft = false))
	}
}
