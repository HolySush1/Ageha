package app.ageha.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.awt.GraphicsEnvironment

/**
 * Type. Restrained and editorial rather than techy: a transitional or humanist serif for titles
 * and manga names, a neutral sans for chrome and metadata.
 *
 * **Nothing is bundled, and after packaging landed that is now a settled decision rather than a
 * deferral.** It was written here as "bundling lands with milestone 9"; milestone 9 came and the
 * answer changed, so this records why rather than leaving a promise nobody kept.
 *
 * Full CJK faces run 10-20MB each and Ageha wants four scripts' worth, so bundling is ~40MB added
 * to every download on every platform. What that buys is nothing at all on Windows and macOS,
 * where Skia's per-glyph fallback already reaches past the chosen family for glyphs it lacks and
 * the system ships CJK coverage regardless. It buys something only on a Linux machine with no CJK
 * font installed — where the right fix is the distribution's own font package, not 40MB carried by
 * every user of every platform to help a subset of one.
 *
 * So the families below stay an explicit, ordered preference chain resolved against what is
 * actually installed, and [FontCoverage] reports what was found. Settings > Appearance names any
 * script with no font in red, because a user seeing boxes where a Korean title should be needs to
 * know it is a missing font and not a broken source. That turns the remaining gap from a mystery
 * into a one-line instruction.
 *
 * Revisit if a Linux user reports tofu in practice: declaring a font dependency in the `.deb` is
 * the next step and costs no bytes, and bundling is the step after that.
 *
 * Compose Desktop's `FontFamily(String)` resolves a family by name through Skia's font manager,
 * which also supplies automatic per-glyph fallback: a Japanese title inside a Latin-only family
 * still renders, because Skia reaches past the family for glyphs it lacks. The CJK entries in
 * [CJK_CHAIN] are therefore about *quality and consistency* of that fallback, not about whether
 * text appears at all -- except on Linux, where a machine with no CJK font installed genuinely
 * has nothing to fall back to and will show tofu.
 */
object AgehaFonts {

	/**
	 * Serif preference, best first.
	 *
	 * Georgia is the safety net rather than the first choice: it is on essentially every Windows
	 * and macOS machine and was drawn for screens, so it degrades gracefully. The entries above
	 * it are simply better when present.
	 */
	val SERIF_CHAIN = listOf(
		"Source Serif 4",
		"Source Serif Pro",
		"Charter",
		"Iowan Old Style",
		"Noto Serif",
		"Georgia",
		"DejaVu Serif",
	)

	/** Sans preference, best first. Ends at each platform's own UI face. */
	val SANS_CHAIN = listOf(
		"Inter",
		"Inter Variable",
		"Segoe UI Variable Text",
		"Segoe UI",
		"SF Pro Text",
		"Helvetica Neue",
		"Noto Sans",
		"Cantarell",
		"Ubuntu",
		"DejaVu Sans",
	)

	/**
	 * CJK coverage, grouped by script, best first within each.
	 *
	 * Grouped rather than flattened because coverage is not all-or-nothing: a Windows machine
	 * almost always has Japanese and Korean, and a Linux machine may have installed only the
	 * Simplified Chinese Noto package. Reporting per script is the difference between "CJK is
	 * fine" and "Korean titles will be boxes on this machine".
	 */
	val CJK_CHAIN: Map<String, List<String>> = linkedMapOf(
		"Japanese" to listOf("Hiragino Sans", "Yu Gothic UI", "Yu Gothic", "Meiryo", "Noto Sans CJK JP", "Noto Sans JP", "Source Han Sans JP", "MS Gothic"),
		"Korean" to listOf("Apple SD Gothic Neo", "Malgun Gothic", "Noto Sans CJK KR", "Noto Sans KR", "Source Han Sans KR"),
		"Chinese (Simplified)" to listOf("PingFang SC", "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "Noto Sans SC", "Source Han Sans SC"),
		"Chinese (Traditional)" to listOf("PingFang TC", "Microsoft JhengHei UI", "Microsoft JhengHei", "Noto Sans CJK TC", "Noto Sans TC", "Source Han Sans TC"),
	)

	private val installed: Set<String> by lazy {
		runCatching {
			GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
		}.getOrElse { emptySet() } // Headless CI with no fonts at all: fall back to generics.
	}

	// FontFamily(String) -- resolve a system family by name -- is still marked experimental on
	// desktop. It is the only way to honour a named preference chain, and the fallback below
	// means an API removal degrades to the generic family rather than failing to build.
	@OptIn(ExperimentalTextApi::class)
	private fun resolve(chain: List<String>, fallback: FontFamily): FontFamily =
		chain.firstOrNull { it in installed }?.let { FontFamily(it) } ?: fallback

	/** Titles, manga names, and anything meant to be *read* rather than operated. */
	val serif: FontFamily by lazy { resolve(SERIF_CHAIN, FontFamily.Serif) }

	/** UI chrome, metadata, numbers. */
	val sans: FontFamily by lazy { resolve(SANS_CHAIN, FontFamily.SansSerif) }

	/** Which named family each role actually resolved to, for the gallery and for bug reports. */
	fun resolvedNames(): Map<String, String> = mapOf(
		"serif" to (SERIF_CHAIN.firstOrNull { it in installed } ?: "(generic serif)"),
		"sans" to (SANS_CHAIN.firstOrNull { it in installed } ?: "(generic sans)"),
	)

	/** Per-script CJK coverage on this machine: the family found, or null if none is installed. */
	fun cjkCoverage(): Map<String, String?> =
		CJK_CHAIN.mapValues { (_, chain) -> chain.firstOrNull { it in installed } }
}

/** What [AgehaFonts] found on this machine. Surfaced in the gallery and in the About dialog. */
data class FontCoverage(
	val serif: String,
	val sans: String,
	val cjk: Map<String, String?>,
) {
	val missingScripts: List<String> get() = cjk.filterValues { it == null }.keys.toList()

	companion object {
		fun detect(): FontCoverage {
			val names = AgehaFonts.resolvedNames()
			return FontCoverage(
				serif = names.getValue("serif"),
				sans = names.getValue("sans"),
				cjk = AgehaFonts.cjkCoverage(),
			)
		}
	}
}

/**
 * The Material scale, retuned for desktop.
 *
 * Material's defaults are drawn for a phone held at arm's length. On a monitor the same sizes read
 * as oversized -- `bodyLarge` at 16sp is a comfortable phone paragraph and a shouty desktop list
 * row. Everything here is a step or two down, and line heights are tightened to match, because
 * the library grid's job is to show a lot of covers at once.
 */
@Composable
internal fun agehaTypography(): Typography {
	val serif = AgehaFonts.serif
	val sans = AgehaFonts.sans
	return Typography(
		displayLarge = TextStyle(fontFamily = serif, fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Normal),
		displayMedium = TextStyle(fontFamily = serif, fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.Normal),
		displaySmall = TextStyle(fontFamily = serif, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal),
		headlineLarge = TextStyle(fontFamily = serif, fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.Normal),
		headlineMedium = TextStyle(fontFamily = serif, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal),
		headlineSmall = TextStyle(fontFamily = serif, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
		titleLarge = TextStyle(fontFamily = serif, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
		titleMedium = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
		titleSmall = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
		bodyLarge = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp),
		bodyMedium = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 18.sp),
		bodySmall = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp),
		labelLarge = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
		labelMedium = TextStyle(fontFamily = sans, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
		labelSmall = TextStyle(fontFamily = sans, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
	)
}

/**
 * Styles Material has no slot for.
 *
 * A manga title is not `titleLarge`. It is frequently long, frequently Japanese, and appears
 * under a cover in a grid where it gets two lines and no more -- a role specific enough to
 * deserve its own token rather than a `titleMedium` with four modifiers bolted on at every call
 * site.
 */
object AgehaTextStyles {

	/**
	 * Under a cover in the library grid.
	 *
	 * Truncation is not expressed here: `TextStyle` in this Compose version carries no `overflow`,
	 * so the two-line clamp belongs on the `Text` call as `maxLines = 2` with
	 * `TextOverflow.Ellipsis`. Noted because it is the one part of this token a call site has to
	 * remember for itself.
	 */
	val mangaTitle: TextStyle
		@Composable get() = TextStyle(
			fontFamily = AgehaFonts.serif,
			fontSize = 13.sp,
			lineHeight = 17.sp,
			fontWeight = FontWeight.Medium,
		)

	/** Source name, chapter count, last-read date. Deliberately quiet. */
	val metadata: TextStyle
		@Composable get() = TextStyle(
			fontFamily = AgehaFonts.sans,
			fontSize = 11.sp,
			lineHeight = 15.sp,
		)

	/**
	 * The reader's page counter and chapter label.
	 *
	 * Tabular figures so the counter does not reflow as the page number ticks past 9 -- a number
	 * that jitters in the corner of the screen is exactly the kind of small irritation that
	 * compounds over a few hundred pages.
	 */
	val readerHud: TextStyle
		@Composable get() = TextStyle(
			fontFamily = AgehaFonts.sans,
			fontSize = 12.sp,
			lineHeight = 16.sp,
			fontFeatureSettings = "tnum",
		)
}
