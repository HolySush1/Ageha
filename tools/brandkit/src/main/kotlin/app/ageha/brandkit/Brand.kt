package app.ageha.brandkit

/**
 * The inputs that define Ageha's identity. Everything else in this module is derived from them.
 *
 * Two of these were measured off `brand/ageha-logo-source.jpg` rather than chosen. The brief
 * guessed the vermillion at `#C8402B`; the actual mean of the stamp's saturated pixels is
 * [VERMILLION], noticeably deeper. Measuring beats guessing for a colour whose whole job is to
 * match a logo sitting next to it, so the measured value wins. `AgehaPaletteSamplingTest` pins
 * both measurements to the image so a future re-crop or re-export cannot silently shift them.
 */
object Brand {

	/**
	 * Deep indigo, close to traditional Japanese *kon* (紺). Specified by the brief, not sampled:
	 * it is the app's identity colour rather than a colour taken off the seal.
	 *
	 * Fed to Material 3 as a *seed*. It is far too dark to use as a dark-theme `primary` -- see
	 * the tone-80 derivation in [PaletteGenerator].
	 */
	const val SEED_INDIGO = 0xFF2B3A67.toInt()

	/**
	 * The hanko red, measured as the mean of every pixel in the source logo with saturation
	 * above 0.45 (n = 117,453). The saturated core runs to `#A3200E`; the mean is the colour the
	 * seal actually reads as at a glance, which is what a UI accent needs to match.
	 */
	const val VERMILLION = 0xFFB93723.toInt()

	/**
	 * Washi -- the warm off-white of the paper showing through the stamp. The measured value is
	 * `#F0ECE3`; the brief specifies `#F5F1E8`, one step lighter. The brief's value wins because
	 * a UI surface wants slightly more headroom above it than a scanned paper tone leaves, and
	 * the two are within a just-noticeable difference of each other anyway.
	 */
	const val PAPER = 0xFFF5F1E8.toInt()

	/** Sumi -- warm near-black for dark surfaces. Never pure black outside the AMOLED variant. */
	const val SUMI = 0xFF1A1A1D.toInt()

	/** True black, used only by the AMOLED variant, where an off pixel is the entire point. */
	const val AMOLED_BLACK = 0xFF000000.toInt()

	/**
	 * Status colours. Skin-independent, because "this source is slow" means the same thing
	 * whichever skin you are in, and re-hueing it per skin would make the dot say less.
	 */
	const val OK = 0xFF5FBF7A.toInt()
	const val WARN = 0xFFD9A13F.toInt()

	/**
	 * Ember and Glass -- the two dark skins, transcribed from the design handoff's `skins.css`.
	 *
	 * These are the one part of Ageha's palette that is *not* derived from [SEED_INDIGO] and
	 * [VERMILLION]. They were chosen deliberately over the brand pairing; see docs/DESIGN.md 11
	 * and the Brand section of CLAUDE.md, both of which record that decision so a later reader
	 * does not mistake it for drift.
	 *
	 * Ember's red lands close to the hanko vermillion by coincidence rather than by derivation.
	 * Glass's violet is a colour the brand does not contain at all.
	 */
	val EMBER = Skin(
		label = "Ember",
		bg = Tint(0xFF0F0B0A.toInt()),
		chrome = Tint(0xFF1A1210.toInt()),
		panel2 = Tint(0xFF1E1715.toInt()),
		panel = Tint(0xFF241A17.toInt()),
		// Not in the handoff: its prototype has no menus, and Ageha's have to stay readable over
		// a wall of cover art. One step above `panel`, on the same warm ramp.
		panelHigh = Tint(0xFF2B1F1B.toInt()),
		inset = Tint(0xFFFFEBE1.toInt(), 0.05),
		line = Tint(0xFFFFEBE1.toInt(), 0.09),
		lineStrong = Tint(0xFFFFEBE1.toInt(), 0.14),
		ink = Tint(0xFFF2E9E4.toInt()),
		inkMuted = Tint(0xFFF2E9E4.toInt(), 0.58),
		inkFaint = Tint(0xFFF2E9E4.toInt(), 0.38),
		accent = Tint(0xFFD9432F.toInt()),
		accentSoft = Tint(0xFF3C1712.toInt()),
		accentLine = Tint(0xFFD9432F.toInt(), 0.65),
		coverHigh = Tint(0xFF4A2A22.toInt()),
		coverLow = Tint(0xFF241A17.toInt()),
	)

	val GLASS = Skin(
		label = "Glass",
		bg = Tint(0xFF12141A.toInt()),
		chrome = Tint(0xFF1C1F27.toInt(), 0.72),
		panel2 = Tint(0xFFFFFFFF.toInt(), 0.045),
		panel = Tint(0xFFFFFFFF.toInt(), 0.07),
		panelHigh = Tint(0xFFFFFFFF.toInt(), 0.10),
		inset = Tint(0xFFFFFFFF.toInt(), 0.06),
		line = Tint(0xFFFFFFFF.toInt(), 0.10),
		lineStrong = Tint(0xFFFFFFFF.toInt(), 0.18),
		ink = Tint(0xFFF0F2F7.toInt()),
		inkMuted = Tint(0xFFF0F2F7.toInt(), 0.62),
		inkFaint = Tint(0xFFF0F2F7.toInt(), 0.42),
		accent = Tint(0xFF8B7FF2.toInt()),
		accentSoft = Tint(0xFF8B7FF2.toInt(), 0.20),
		accentLine = Tint(0xFFA096FF.toInt(), 0.60),
		coverHigh = Tint(0xFF3B3F63.toInt()),
		coverLow = Tint(0xFF1B1D2A.toInt()),
	)
}

/**
 * A colour as the stylesheet writes it: an `0xAARRGGBB` and the alpha CSS applies to it.
 *
 * The handoff builds most of its surfaces out of translucent white over one background, which is
 * how a stylesheet keeps a ramp consistent. Compose's `ColorScheme` has nowhere to put an alpha,
 * so every one of these gets composited over its own ground before it is emitted -- see
 * [Tint.over]. Keeping the alpha here rather than pre-multiplying by hand means the generated
 * tokens can be checked against `skins.css` line by line.
 */
class Tint(val argb: Int, val alpha: Double = 1.0)

/** One skin's inputs, in the order `skins.css` declares them. */
class Skin(
	val label: String,
	/** `--bg`: the window itself. */
	val bg: Tint,
	/** `--chrome`: title bar, floating pills, reader overlay. */
	val chrome: Tint,
	/** `--panel2`: list containers, one step down from a card. */
	val panel2: Tint,
	/** `--panel`: raised card, banner, filter rail. */
	val panel: Tint,
	/** Ageha's own step above `--panel`, for menus and dialogs. */
	val panelHigh: Tint,
	/** `--glass`: inset chip and track fill. */
	val inset: Tint,
	/** `--line`: hairline divider and card border. */
	val line: Tint,
	/** `--line2`: stronger border, ghost button. */
	val lineStrong: Tint,
	/** `--ink`, `--ink2`, `--ink3`. */
	val ink: Tint,
	val inkMuted: Tint,
	val inkFaint: Tint,
	/** `--accent`, `--accent-soft`, `--accent-line`. */
	val accent: Tint,
	val accentSoft: Tint,
	val accentLine: Tint,
	/**
	 * `--cover`: the two stops of the placeholder gradient drawn where artwork has not arrived.
	 *
	 * The handoff uses this everywhere a cover would be, because its prototype ships no images.
	 * Ageha has real covers, so here it is the *fallback* -- what a card shows while a thumbnail
	 * loads, and what it keeps showing if the source never serves one. That makes it load-bearing
	 * in a way the handoff's version is not: it has to hold a 2:3 rectangle in a grid of real
	 * artwork without looking like a broken image.
	 */
	val coverHigh: Tint,
	val coverLow: Tint,
)

/**
 * Composite this tint over an opaque ground, the way a browser would.
 *
 * Straight source-over in sRGB rather than in linear light. That is what CSS does, and matching
 * the handoff matters more here than being right about colour science -- the numbers are being
 * transcribed from a stylesheet whose appearance is the specification.
 */
fun Tint.over(ground: Int): Int {
	if (alpha >= 1.0) return argb
	fun mix(shift: Int): Int {
		val f = (argb shr shift) and 0xFF
		val b = (ground shr shift) and 0xFF
		return (f * alpha + b * (1 - alpha)).toInt().coerceIn(0, 255)
	}
	return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

/** `0xAARRGGBB` -> `#RRGGBB`. */
fun Int.toHex(): String = "#%06X".format(this and 0xFFFFFF)

/** Relative luminance per WCAG 2.1, from an opaque `0xAARRGGBB`. */
fun Int.relativeLuminance(): Double {
	fun channel(c: Int): Double {
		val s = c / 255.0
		return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
	}
	val r = channel((this shr 16) and 0xFF)
	val g = channel((this shr 8) and 0xFF)
	val b = channel(this and 0xFF)
	return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG 2.1 contrast ratio between two opaque colours. 4.5 is the AA threshold for body text. */
fun contrastRatio(a: Int, b: Int): Double {
	val la = a.relativeLuminance()
	val lb = b.relativeLuminance()
	return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
