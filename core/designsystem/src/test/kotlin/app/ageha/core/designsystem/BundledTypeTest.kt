package app.ageha.core.designsystem

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.awt.Font

/**
 * The two faces the design is drawn in are actually in the jar, and actually parse.
 *
 * This exists because the failure it guards is silent. `AgehaFonts.bundle` is deliberately
 * forgiving -- a missing or corrupt resource returns null and the app falls back to a system sans
 * rather than refusing to start -- which is right at runtime and useless as a signal. Without this
 * test, dropping a font from the resource directory, renaming a weight, or committing a Git LFS
 * pointer instead of a `.ttf` would produce an app that still runs, still looks *plausible*, and no
 * longer matches the handoff anywhere. Nothing else would say so.
 *
 * The weights checked here are the ones the handoff's scale names: Archivo 400/500/600/700/800 and
 * JetBrains Mono 400/500/700. Bundling is all-or-nothing for the reason `AgehaFonts.bundle`
 * documents, so a partial set is a failure rather than a degradation.
 */
class BundledTypeTest {

	private val archivo = listOf("Regular", "Medium", "SemiBold", "Bold", "ExtraBold")
		.map { "app/ageha/font/Archivo-$it.ttf" }

	private val jetBrainsMono = listOf("Regular", "Medium", "Bold")
		.map { "app/ageha/font/JetBrainsMono-$it.ttf" }

	@Test
	fun `every weight the scale names is on the classpath`() {
		assertAll(
			(archivo + jetBrainsMono).map { path ->
				{
					assertNotNull(
						javaClass.classLoader.getResource(path),
						"$path is missing -- agehaTypography() would silently fall back to a " +
							"system sans and the handoff's scale would be drawn in the wrong face",
					)
				}
			},
		)
	}

	/**
	 * Each file is a font AWT will parse.
	 *
	 * A `.ttf` fetched over HTTP can easily be an HTML error page with the right extension, and
	 * `Font.createFont` is the cheapest check that says so. Skia parses these separately at
	 * runtime, so this is a proxy rather than a proof -- but a file that AWT rejects is one Skia
	 * will reject too, and this catches it in CI rather than on someone's screen.
	 */
	@Test
	fun `every bundled file parses as a font`() {
		assertAll(
			(archivo + jetBrainsMono).map { path ->
				{
					val font = javaClass.classLoader.getResourceAsStream(path).use { stream ->
						Font.createFont(Font.TRUETYPE_FONT, requireNotNull(stream) { path })
					}
					assertTrue(font.numGlyphs > 100, "$path parsed but carries no glyphs")
				}
			},
		)
	}

	/**
	 * Archivo covers the Latin the interface is written in, and JetBrains Mono covers the data.
	 *
	 * The mono sample is every character class the mono role actually draws -- digits, the slash
	 * and bullet in `Ch 214 / 260`, the arrows in the reader's chapter pill, and an uppercase run
	 * for the eyebrows. Checked because a subset of a font is a normal thing to ship by accident,
	 * and the first place it would show is a chapter counter with a missing arrow.
	 */
	@Test
	fun `the bundled faces cover what they are asked to draw`() {
		val cases = listOf(
			Triple("Archivo", archivo.first(), "Ageha - Continue reading (260 chapters)"),
			Triple("JetBrains Mono", jetBrainsMono.first(), "MY LIBRARY Ch 214 / 260 - 64% <- -> 12.4k"),
		)
		assertAll(
			cases.map { (name, path, sample) ->
				{
					val font = javaClass.classLoader.getResourceAsStream(path).use { stream ->
						Font.createFont(Font.TRUETYPE_FONT, requireNotNull(stream) { path })
					}
					val missing = sample.codePoints()
						.filter { !Character.isWhitespace(it) && !font.canDisplay(it) }
						.toArray()
					assertTrue(
						missing.isEmpty(),
						"$name cannot draw ${missing.size} character(s) of \"$sample\": " +
							missing.joinToString { "U+%04X".format(it) },
					)
				}
			},
		)
	}

	/** The families assemble, so the app is drawing the handoff's type rather than a fallback. */
	@Test
	fun `the families assemble from the bundle`() {
		assertTrue(
			AgehaFonts.hasBundledUi,
			"Archivo and JetBrains Mono are on the classpath but did not assemble into families",
		)
	}
}
