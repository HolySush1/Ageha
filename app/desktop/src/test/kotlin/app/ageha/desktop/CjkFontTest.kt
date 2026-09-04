package app.ageha.desktop

import app.ageha.core.designsystem.AgehaFonts
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.awt.Font

/**
 * The face bundled with the Linux packages covers what it is bundled for.
 *
 * Ageha ships exactly one CJK file, and only to Linux, on the claim that
 * `NotoSansCJKjp-Regular.otf` -- nominally the *Japanese* build of the pan-CJK family -- also
 * carries hangul, both Chinese variants' ideographs, and a complete Latin set. That claim is the
 * entire reason there is one file here instead of four, and it is not obvious from the filename.
 * If it were wrong, a Linux user with no system fonts would get Japanese titles and boxes
 * everywhere else, which is most of the problem the bundle exists to solve.
 *
 * The samples are real manga titles rather than lorem: `斗罗大陆` and `鬥羅大陸` are the same series
 * in Simplified and Traditional, which is what makes them a useful pair to check.
 *
 * This runs on every platform because `:app:desktop` puts the font jar on the test classpath. The
 * shipped packages are the ones that carry it for real; this checks the same artifact.
 */
class CjkFontTest {

	@Test
	fun `the bundled face is on the classpath`() {
		assertNotNull(
			javaClass.classLoader.getResource(AgehaFonts.BUNDLED_CJK_RESOURCE),
			"the font jar should be on the test classpath -- see :app:desktop:cjkFontJar",
		)
	}

	@Test
	fun `one file covers all four scripts and Latin`() {
		val font = javaClass.classLoader
			.getResourceAsStream(AgehaFonts.BUNDLED_CJK_RESOURCE)
			.use { stream -> Font.createFont(Font.TRUETYPE_FONT, requireNotNull(stream)) }

		val samples = mapOf(
			"Japanese kana" to "ソードアート・オンライン",
			"Japanese kanji" to "鋼の錬金術師",
			"Korean hangul" to "신의 탑",
			"Chinese Simplified" to "斗罗大陆",
			"Chinese Traditional" to "鬥羅大陸",
			// Checked because the wholesale fallback hands this face *all* text on a machine with
			// no CJK fonts, Latin UI chrome included. A CJK face with a thin Latin set would make
			// the entire interface worse in exchange for fixing the titles.
			"Latin" to "Ageha - Continue reading",
		)

		assertAll(
			samples.map { (script, text) ->
				{
					val missing = text.codePoints()
						.filter { !Character.isWhitespace(it) && !font.canDisplay(it) }
						.toArray()
					assertTrue(
						missing.isEmpty(),
						"$script (\"$text\") has ${missing.size} glyph(s) the bundled face " +
							"cannot draw: " + missing.joinToString { "U+%04X".format(it) },
					)
				}
			},
		)
	}

	/**
	 * On a machine that has its own CJK fonts, the bundle stays out of the way.
	 *
	 * The bundled face is a floor, not a preference: a Linux user who installed their
	 * distribution's Noto package should keep Inter and Source Serif. Every development machine
	 * this runs on has CJK fonts, so this is the branch the test suite can actually check.
	 */
	@Test
	fun `a machine with its own CJK fonts does not use the bundled face`() {
		val missing = AgehaFonts.missingCjkScripts()
		if (missing.isNotEmpty()) {
			// Not a failure. It means this machine is the case the bundle exists for, and the
			// other branch is the correct one here.
			assertTrue(
				AgehaFonts.usesBundledCjk,
				"this machine has no font for $missing, so the bundled face should be in use",
			)
			return
		}
		assertTrue(
			!AgehaFonts.usesBundledCjk,
			"every CJK script resolved to a system font, so the bundled face should be unused",
		)
	}
}
