package app.ageha.feature.reader

/**
 * How pages are grouped into what the reader shows at once.
 *
 * A [Spread] is one or two pages side by side. Single-page mode makes every spread one page;
 * double-page mode pairs them, and the pairing is where the interesting problem is.
 */
data class Spread(val pages: List<Int>) {
	val first: Int get() = pages.first()
	val last: Int get() = pages.last()
	val isDouble: Boolean get() = pages.size == 2

	operator fun contains(page: Int): Boolean = page in pages
}

/**
 * Grouping pages into spreads.
 *
 * **Cover offset is the whole difficulty.** Printed manga is a bound book: page 1 is a
 * right-hand page on its own, and every pair after it is a physical sheet -- 2-3, 4-5, and so on.
 * Pairing naively from zero gives 0-1, 2-3, which puts every spread half a page out of step, so
 * artwork drawn across a fold is split down the middle and shown as two unrelated halves. It is
 * the single most visible way a double-page reader can be wrong.
 *
 * The offset is a setting rather than an assumption because it is not universal: digital-first
 * releases and many scanlations have no cover page, and web-scraped chapters frequently begin
 * mid-volume where the parity is anyone's guess. The reader can toggle it and see the result
 * immediately, which is faster than any heuristic and always right.
 */
object PageLayout {

	fun spreads(pageCount: Int, doublePage: Boolean, coverOffset: Boolean): List<Spread> {
		if (pageCount <= 0) return emptyList()
		if (!doublePage) return (0 until pageCount).map { Spread(listOf(it)) }

		val result = mutableListOf<Spread>()
		var index = 0
		// The cover stands alone, which is what shifts the parity of everything after it.
		if (coverOffset) {
			result += Spread(listOf(0))
			index = 1
		}
		while (index < pageCount) {
			if (index + 1 < pageCount) {
				result += Spread(listOf(index, index + 1))
				index += 2
			} else {
				// A trailing odd page has no partner. Shown alone rather than paired with the
				// first page of the next chapter, which would be a different manga entirely if
				// the chapter is the last one.
				result += Spread(listOf(index))
				index += 1
			}
		}
		return result
	}

	/** Which spread a page falls in. -1 if the page is out of range. */
	fun spreadOf(spreads: List<Spread>, page: Int): Int = spreads.indexOfFirst { page in it }

	/**
	 * Page order within a spread, for the reading direction.
	 *
	 * Right-to-left is not a mirror of the layout -- the *pages* swap. In a Japanese book the
	 * lower-numbered page of a pair sits on the right, so rendering them in ascending order left
	 * to right shows them in the wrong order even though each page is individually correct.
	 */
	fun orderedPages(spread: Spread, rightToLeft: Boolean): List<Int> =
		if (rightToLeft) spread.pages.reversed() else spread.pages
}
