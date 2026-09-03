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
