package app.ageha.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.awt.GraphicsEnvironment

/**
 * Type. Restrained and editorial rather than techy: a transitional or humanist serif for titles
 * and manga names, a neutral sans for chrome and metadata.
 *
 * **One CJK face is bundled, and only with the Linux packages.**
 *
 * This file previously argued that bundling costs ~40MB on every platform to help a subset of one,
 * and settled on shipping nothing. The arithmetic was right about the cost and wrong about the
 * shape of the fix. Windows and macOS ship CJK coverage, so Skia's per-glyph fallback always has
 * somewhere to go there and a bundled face buys them nothing. A Linux machine with no CJK font
 * package installed has nowhere to fall back to and shows tofu -- which, in a manga library, is a
 * large share of the titles.
 *
 * So the Linux packages, and only the Linux packages, carry one 16MB file: `NotoSansCJKjp-Regular`,
 * the Japanese-preferred build of the pan-CJK family, which also carries hangul, both Chinese
 * variants' ideographs and a complete Latin set. Four scripts from one file, verified rather than
 * assumed -- see `CjkFontTest`. The other four machines download nothing extra.
 *
 * Because its Latin is complete, the fallback is wholesale rather than per-script: on a machine
 * with a gap, *both* families become the bundled face. Routing individual runs of text by script
 * would preserve Inter for the Latin parts, at the cost of a script detector in every text style
 * and two faces mixed inside one title. Uniform and correct beats mixed and clever here.
 *
 * The chains below still decide everything on a machine that has fonts, and [FontCoverage] still
 * reports what was found. Settings > Appearance names any script with no font in red, because a
 * user seeing boxes where a Korean title should be needs to know it is a missing font and not a
 * broken source.
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
	 * Monospace preference, best first. Only reached if the bundled JetBrains Mono is missing.
	 *
	 * Every entry is a *programming* mono rather than a typewriter face, because the role here is
	 * dense tabular data -- counts, hosts, page positions -- where a slab-serif Courier reads as a
	 * receipt. Ends at each platform's own, then the generic.
	 */
	val MONO_CHAIN = listOf(
		"JetBrains Mono",
		"IBM Plex Mono",
		"SF Mono",
		"Cascadia Mono",
		"Cascadia Code",
		"Consolas",
		"Menlo",
		"DejaVu Sans Mono",
		"Liberation Mono",
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

	/**
	 * The bundled face, as a classpath resource.
	 *
	 * Present only in the Linux packages -- `:app:desktop` adds the jar carrying it to the
	 * `linuxAmd64` and `linuxAarch64` configurations and to nothing else. Everywhere else this
	 * resolves to null and nothing below changes.
	 *
	 * A classpath resource rather than a file beside the binary, because the install layout
	 * differs between a `.deb`, a tarball and a run from Gradle, and a resource is found the same
	 * way in all three.
	 */
	const val BUNDLED_CJK_RESOURCE = "fonts/NotoSansCJKjp-Regular.otf"

	/** True when this build shipped [BUNDLED_CJK_RESOURCE]. */
	val hasBundledCjk: Boolean by lazy {
		AgehaFonts::class.java.classLoader?.getResource(BUNDLED_CJK_RESOURCE) != null
	}

	private val bundledCjk: FontFamily? by lazy {
		if (!hasBundledCjk) {
			null
		} else {
			// Never fatal. A font that will not parse is a worse-looking app, not a broken one,
			// and a reader that refuses to start because of a typeface would be absurd.
			runCatching { FontFamily(Font(BUNDLED_CJK_RESOURCE)) }.getOrNull()
		}
	}

	/**
	 * The two faces the Ember & Glass handoff names, bundled rather than hoped for.
	 *
	 * Archivo for everything that is *operated* and JetBrains Mono for everything that is *read as
	 * data* -- counts, hosts, page positions, the uppercase micro-labels over each section. The
	 * handoff's scale is specific to the pair (a `600 12.5px` card title, a `500 10px` eyebrow at
	 * `.16em`), and neither face is on a typical machine, so a preference chain would have
	 * delivered the design to almost nobody. §10 of docs/DESIGN.md argued against bundling on a
	 * 40MB CJK figure; 1.8MB of Latin is a different question with a different answer.
	 *
	 * Both are SIL Open Font Licence 1.1, which is compatible with GPL-3.0. Licences ship beside
	 * them and are credited in NOTICE.md.
	 */
	private const val UI_RESOURCE_PREFIX = "app/ageha/font/Archivo-"
	private const val MONO_RESOURCE_PREFIX = "app/ageha/font/JetBrainsMono-"

	/** The weights the handoff's scale actually calls for. Nothing is bundled speculatively. */
	private val UI_WEIGHTS = listOf(
		"Regular" to FontWeight.Normal,
		"Medium" to FontWeight.Medium,
		"SemiBold" to FontWeight.SemiBold,
		"Bold" to FontWeight.Bold,
		"ExtraBold" to FontWeight.ExtraBold,
	)

	private val MONO_WEIGHTS = listOf(
		"Regular" to FontWeight.Normal,
		"Medium" to FontWeight.Medium,
		"Bold" to FontWeight.Bold,
	)

	/**
	 * Builds a family from bundled resources, or null if any of them is missing.
	 *
	 * All-or-nothing on purpose. A family assembled from three of five weights renders the other
	 * two by synthesising them, and synthetic bold on a face that has a real bold is visibly
	 * worse than falling back to a system font wholesale.
	 *
	 * Never fatal, for the same reason [bundledCjk] is not: a typeface that will not parse is a
	 * worse-looking app, not a broken one.
	 */
	private fun bundle(prefix: String, weights: List<Pair<String, FontWeight>>): FontFamily? =
		runCatching {
			val loader = AgehaFonts::class.java.classLoader ?: return null
			val fonts = weights.map { (name, weight) ->
				val path = "$prefix$name.ttf"
				if (loader.getResource(path) == null) return null
				Font(path, weight = weight)
			}
			FontFamily(fonts)
		}.getOrNull()

	private val archivo: FontFamily? by lazy { bundle(UI_RESOURCE_PREFIX, UI_WEIGHTS) }

	private val jetBrainsMono: FontFamily? by lazy { bundle(MONO_RESOURCE_PREFIX, MONO_WEIGHTS) }

	/** True when this build shipped the handoff's faces. False means every style is a fallback. */
	val hasBundledUi: Boolean by lazy { archivo != null && jetBrainsMono != null }

	/** Scripts this machine has no font for at all. Empty on Windows and macOS in practice. */
	fun missingCjkScripts(): List<String> =
		cjkCoverage().filterValues { it == null }.keys.toList()

	/**
	 * Whether to hand both roles to the bundled face.
	 *
	 * Only when there is a real gap. A Linux user who has installed their distribution's CJK fonts
	 * keeps Inter and Source Serif; the bundled face is a floor, not a preference.
	 */
	val usesBundledCjk: Boolean by lazy {
		bundledCjk != null && missingCjkScripts().isNotEmpty()
	}

	/**
	 * Everything the user operates or reads as prose: chrome, titles, body, manga names.
	 *
	 * Archivo when it is bundled -- which is every build, since it is a classpath resource of this
	 * module rather than a per-platform extra like the CJK face. The chain below it exists so a
	 * stripped jar degrades to a system sans instead of to the generic.
	 *
	 * The bundled CJK face still wins where there is a genuine gap. Archivo carries no CJK, and on
	 * a Linux machine with no CJK package installed a manga library is mostly tofu -- a worse
	 * failure than losing the handoff's typeface, so the floor is checked first.
	 */
	val ui: FontFamily by lazy {
		bundledCjk.takeIf { usesBundledCjk } ?: archivo ?: resolve(SANS_CHAIN, FontFamily.SansSerif)
	}

	/**
	 * Data: counts, hosts, paths, page positions, status, and the uppercase micro-labels.
	 *
	 * Not routed through [bundledCjk] the way [ui] is. Everything drawn in this role is ASCII by
	 * construction -- `Ch 214 / 260`, `mangadex.org`, `12.4k titles`, `MY LIBRARY` -- so the CJK
	 * floor has nothing to do here, and handing a proportional pan-CJK face to a column of numbers
	 * would cost the tabular alignment that is the whole reason this role is monospaced.
	 */
	val mono: FontFamily by lazy {
		jetBrainsMono ?: resolve(MONO_CHAIN, FontFamily.Monospace)
	}

	/**
	 * Titles, manga names, and anything meant to be *read* rather than operated.
	 *
	 * Kept as an alias of [ui] rather than deleted. The handoff has no serif -- a manga title in
	 * its grid is Archivo SemiBold at 12.5px, not an editorial serif -- but the name is referenced
	 * across the gallery and the About dialog, and a rename there would be churn for nothing.
	 * [SERIF_CHAIN] survives for the same reason [FontCoverage] does: it still reports honestly.
	 */
	val serif: FontFamily get() = ui

	/** UI chrome, metadata, numbers. An alias of [ui]; see [serif]. */
	val sans: FontFamily get() = ui

	/** Which named family each role actually resolved to, for the gallery and for bug reports. */
	fun resolvedNames(): Map<String, String> = mapOf(
		"ui" to when {
			usesBundledCjk -> BUNDLED_CJK_NAME
			archivo != null -> BUNDLED_UI_NAME
			else -> SANS_CHAIN.firstOrNull { it in installed } ?: "(generic sans)"
		},
		"mono" to when {
			jetBrainsMono != null -> BUNDLED_MONO_NAME
			else -> MONO_CHAIN.firstOrNull { it in installed } ?: "(generic mono)"
		},
	)

	/** What the About dialog and the gallery call the two bundled faces. */
	const val BUNDLED_UI_NAME = "Archivo (bundled)"
	const val BUNDLED_MONO_NAME = "JetBrains Mono (bundled)"

	/** What the About dialog and the gallery call the bundled face. */
	const val BUNDLED_CJK_NAME = "Noto Sans CJK JP (bundled)"

	/** Per-script CJK coverage on this machine: the family found, or null if none is installed. */
	fun cjkCoverage(): Map<String, String?> =
		CJK_CHAIN.mapValues { (_, chain) -> chain.firstOrNull { it in installed } }
}

/** What [AgehaFonts] found on this machine. Surfaced in the gallery and in the About dialog. */
data class FontCoverage(
	/** The family drawing chrome, titles and body. `Archivo (bundled)` in a normal build. */
	val ui: String,
	/** The family drawing counts, hosts and micro-labels. `JetBrains Mono (bundled)` normally. */
	val mono: String,
	val cjk: Map<String, String?>,
	/** True when the Linux packages' bundled face is standing in for missing system fonts. */
	val usesBundledCjk: Boolean = false,
) {
	/**
	 * Scripts that will genuinely render as boxes.
	 *
	 * Empty when the bundled face is in use, because it covers all four -- and telling a user
	 * "no font installed for Korean" while Korean is rendering correctly from the bundled face
	 * would be a warning about nothing.
	 */
	val missingScripts: List<String>
		get() = if (usesBundledCjk) emptyList() else cjk.filterValues { it == null }.keys.toList()

	companion object {
		fun detect(): FontCoverage {
			val names = AgehaFonts.resolvedNames()
			return FontCoverage(
				ui = names.getValue("ui"),
				mono = names.getValue("mono"),
				cjk = AgehaFonts.cjkCoverage(),
				usesBundledCjk = AgehaFonts.usesBundledCjk,
			)
		}
	}
}

/**
 * The Material scale, carrying the Ember & Glass handoff's own sizes.
 *
 * The handoff is specific to the point of naming half-pixels -- a `600 12.5px/1.35` card title, a
 * `700 21px` section head, `400 13.5px/1.65` body -- and those numbers are the design rather than a
 * starting point, so they are transcribed rather than rounded to a scale. CSS pixels map to `sp`
 * one for one here: Compose Desktop's default density is 1, and Ageha never scales type by a user
 * preference, so the two units describe the same thing.
 *
 * Everything is Archivo. The handoff has no serif role at all -- what was an editorial serif for
 * manga titles is now `titleSmall` at SemiBold, which is what its grid actually draws. Data does
 * not appear on this scale: it lives in [AgehaTextStyles]'s mono styles, because a monospaced count
 * is a different role from a label that happens to be small, and collapsing the two is how the
 * mono micro-labels quietly become sans.
 */
@Composable
internal fun agehaTypography(): Typography {
	val ui = AgehaFonts.ui
	return Typography(
		// The Continue banner's H1. `800 40px/1.06` at `-.02em`, and the one place in Ageha where
		// type is the loudest thing on screen.
		displayLarge = TextStyle(
			fontFamily = ui,
			fontSize = 40.sp,
			lineHeight = 42.sp,
			fontWeight = FontWeight.ExtraBold,
			letterSpacing = (-0.02).em,
		),
		displayMedium = TextStyle(fontFamily = ui, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
		displaySmall = TextStyle(fontFamily = ui, fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
		// Section heads -- "Reading & read", "Page and layout". `700 21-22px`.
		headlineLarge = TextStyle(fontFamily = ui, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
		headlineMedium = TextStyle(fontFamily = ui, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
		headlineSmall = TextStyle(fontFamily = ui, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
		// The storage card's used figure. `700 19px`.
		titleLarge = TextStyle(fontFamily = ui, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
		// Row titles: a source name, a download, a settings label. `600 13.5-14px`.
		titleMedium = TextStyle(fontFamily = ui, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
		// A library card's title. `600 12.5px/1.35`, clamped to two lines at the call site.
		titleSmall = TextStyle(fontFamily = ui, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
		// Body. `400 13.5px/1.65` -- the banner synopsis and every settings blurb.
		bodyLarge = TextStyle(fontFamily = ui, fontSize = 13.5.sp, lineHeight = 22.sp),
		bodyMedium = TextStyle(fontFamily = ui, fontSize = 12.5.sp, lineHeight = 19.sp),
		bodySmall = TextStyle(fontFamily = ui, fontSize = 11.5.sp, lineHeight = 17.sp),
		// A nav pill item. `600 13px`.
		labelLarge = TextStyle(fontFamily = ui, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
		// A chip. `500-600 11-12px`.
		labelMedium = TextStyle(fontFamily = ui, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
		labelSmall = TextStyle(fontFamily = ui, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
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
			fontFamily = AgehaFonts.ui,
			fontSize = 12.5.sp,
			lineHeight = 17.sp,
			fontWeight = FontWeight.SemiBold,
		)

	/** Source name, chapter count, last-read date. Deliberately quiet. */
	val metadata: TextStyle
		@Composable get() = TextStyle(
			fontFamily = AgehaFonts.ui,
			fontSize = 11.sp,
			lineHeight = 15.sp,
		)

	/**
	 * The uppercase micro-label over a section: `MY LIBRARY`, `FILTERS`, `ON THIS DEVICE`.
	 *
	 * `500 10px` at `.16em`. The tracking is most of the effect -- at 10px an uppercase run set
	 * solid reads as a smudge, and opening it up is what turns it into a label rather than shouted
	 * text. Uppercasing is left to the call site, so a screen reader is handed the cased string.
	 */
	val monoEyebrow: TextStyle
		@Composable get() = TextStyle(
			fontFamily = AgehaFonts.mono,
			fontSize = 10.sp,
			lineHeight = 14.sp,
			fontWeight = FontWeight.Medium,
			letterSpacing = 0.16.em,
		)

	/**
	 * Data drawn as data: `Ch 214 / 260`, `12.4k titles`, `mangadex.org`, `page 14 / 22 - 64%`.
	 *
	 * Tabular figures, for the reason [readerHud] gives: these sit in columns and in rows that
	 * update, and proportional digits make both jitter.
	 */
	val monoMeta: TextStyle
		@Composable get() = TextStyle(
			fontFamily = AgehaFonts.mono,
			fontSize = 10.5.sp,
			lineHeight = 15.sp,
			fontFeatureSettings = "tnum",
		)

	/** [monoMeta] where the row has space for it -- a source's host, a download's size. */
	val monoData: TextStyle
		@Composable get() = TextStyle(
			fontFamily = AgehaFonts.mono,
			fontSize = 11.5.sp,
			lineHeight = 16.sp,
			fontFeatureSettings = "tnum",
		)

	/**
	 * The value inside a MODE / FIT / BACKGROUND chip, and a key cap's `CTRL K`.
	 *
	 * Bold and small. This is the one mono style that is a *control's* label rather than a
	 * readout, so it carries the weight the surrounding chip needs to look pressable.
	 */
	val monoControl: TextStyle
		@Composable get() = TextStyle(
			fontFamily = AgehaFonts.mono,
			fontSize = 9.5.sp,
			lineHeight = 13.sp,
			fontWeight = FontWeight.Medium,
			letterSpacing = 0.14.em,
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
			fontFamily = AgehaFonts.mono,
			fontSize = 12.sp,
			lineHeight = 16.sp,
			fontFeatureSettings = "tnum",
		)
}
