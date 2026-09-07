package app.ageha.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs

/**
 * "Nothing brand-coloured touches the reader view."
 *
 * This is the hardest of the project's rules to keep by discipline alone, because the failure is
 * subtle -- a 4% indigo wash behind the page looks *nice* in isolation and is only wrong when you
 * remember it is sitting on somebody's artwork. So it is tested rather than remembered.
 *
 * The test is on saturation rather than on "is not equal to the brand colours". A tinted grey is
 * the actual failure mode; nobody is going to set the reader background to `#2B3A67` outright.
 */
class ReaderNeutralityTest {

	/** Chroma, roughly: the spread between the strongest and weakest channel. */
	private fun Color.chroma(): Float = maxOf(red, green, blue) - minOf(red, green, blue)

	/**
	 * How much tint a reader colour may carry.
	 *
	 * Not zero. `PAPER` is a deliberately warm off-white -- it measures 3.5% -- and a genuinely
	 * neutral grey would look clinical next to a warm scanned page. 6% is the ceiling: enough
	 * headroom that a legitimate warm tone is not a knife-edge pass, and still nowhere near a
	 * hue you could name. For scale, the indigo seed measures 24% and the vermillion 60%, so a
	 * brand colour leaking in here would miss this bar by four times over.
	 */
	private val maxChroma = 0.06f

	@TestFactory
	fun `reader backgrounds carry no hue`(): List<DynamicTest> =
		ReaderBackground.entries.flatMap { background ->
			listOf(
				DynamicTest.dynamicTest("${background.name} background") {
					val chroma = background.color.chroma()
					assertTrue(chroma <= maxChroma) {
						"${background.name} background has chroma $chroma; the reader must stay neutral"
					}
				},
				DynamicTest.dynamicTest("${background.name} foreground") {
					val chroma = background.onColor.chroma()
					assertTrue(chroma <= maxChroma) {
						"${background.name} foreground has chroma $chroma; the reader must stay neutral"
					}
				},
			)
		}

	@TestFactory
	fun `reader chrome carries no hue`(): List<DynamicTest> =
		ReaderBackground.entries.flatMap { background ->
			val chrome = ReaderChrome.forBackground(background)
			listOf("scrim" to chrome.scrim, "content" to chrome.content, "subdued" to chrome.subdued)
				.map { (role, colour) ->
					DynamicTest.dynamicTest("${background.name} chrome $role") {
						assertTrue(colour.chroma() <= maxChroma) {
							"${background.name} chrome $role has chroma ${colour.chroma()}"
						}
					}
				}
		}

	/**
	 * The reader's paper is *not* the brand's paper, even though they are close.
	 *
	 * Reusing `Brand.PAPER` here would work today and would silently couple the reader to the
	 * brand: a future change to the brand's warmth would change what a reader sees behind their
	 * manga, which is exactly the coupling the rule forbids. Asserting they differ keeps the
	 * separation deliberate rather than incidental.
	 */
	@Test
	fun `the reader's paper is not the brand's paper`() {
		val brandPaper = AgehaColorTokens.Light.surface
		assertTrue(
			ReaderBackground.PAPER.color != brandPaper,
			"the reader background must not be a brand token, however similar it looks",
		)
	}

	@Test
	fun `reader chrome stays legible on its background`() {
		for (background in ReaderBackground.entries) {
			val chrome = ReaderChrome.forBackground(background)
			val delta = abs(chrome.content.luminance() - background.color.luminance())
			assertTrue(delta > 0.3f) {
				"${background.name}: chrome content is too close in luminance to the page ground"
			}
		}
	}

	/**
	 * No reader colour may be *any* design-system token, in any theme.
	 *
	 * This is the structural half of the rule, and it is the half that actually holds. An earlier
	 * version of this test measured RGB distance from the brand colours instead, and it failed on
	 * `PAPER`'s neutral grey foreground -- because the indigo seed is itself fairly dark and not
	 * especially saturated, so a plain grey of similar lightness lands close to it in RGB space
	 * while carrying no hue at all. Distance-from-brand punishes greys for existing. What matters
	 * is that a reader colour never *is* a theme colour: reuse is how the reader would start
	 * tracking the brand, and reuse is exactly what can be checked exactly.
	 *
	 * Tint is covered separately, by the chroma bound above.
	 */
	@Test
	fun `no reader colour is also a theme token`() {
		val tokens = listOf(
			AgehaColorTokens.Light,
			AgehaColorTokens.Ember,
			AgehaColorTokens.Glass,
			AgehaColorTokens.Amoled,
		)
			.flatMap { scheme -> scheme.all.entries.map { it.key to it.value } }
		val readerColours = ReaderBackground.entries.flatMap { background ->
			val chrome = ReaderChrome.forBackground(background)
			listOf(
				"${background.name}.color" to background.color,
				"${background.name}.onColor" to background.onColor,
				"${background.name}.chrome.content" to chrome.content,
				"${background.name}.chrome.subdued" to chrome.subdued,
			)
		}
		// True black and true white are excluded from the comparison entirely. Both sides reach
		// them independently and for the same physical reason -- the AMOLED theme's background is
		// black so the pixel is off, and the reader's black background is black so the pixel is
		// off -- so their being equal is arithmetic, not the reader borrowing a brand token.
		val universal = setOf(Color(0xFF000000), Color(0xFFFFFFFF))
		for ((label, colour) in readerColours) {
			if (colour in universal) continue
			val clash = tokens.firstOrNull { (_, token) -> token == colour }
			assertTrue(clash == null) {
				"$label is the same colour as the theme token '${clash?.first}'; " +
					"the reader must not share tokens with the brand"
			}
		}
	}

	@Test
	fun `every reader background offers a foreground`() {
		assertEquals(4, ReaderBackground.entries.size)
		for (background in ReaderBackground.entries) {
			assertTrue(background.label.isNotBlank())
		}
	}

	private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}
