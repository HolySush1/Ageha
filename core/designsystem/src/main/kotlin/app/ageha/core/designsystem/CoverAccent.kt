package app.ageha.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.ageha.core.image.AgehaImages
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.pow

/**
 * A cover's own colour, made safe to put words on.
 *
 * The Continue Reading hero used to be the cover itself, stretched across the full width of the
 * window and cropped to a letterbox. That is the one thing a manga cover cannot survive: it is
 * drawn at 2:3, a source serves it at whatever resolution it likes -- often a 300px-wide
 * thumbnail -- and blowing that up to a desktop window's width produces exactly the smear the bug
 * report called low quality. Cropping it to a band also throws away the half of a cover that
 * carries the title.
 *
 * So the hero now shows the cover at its own proportions and its own scale, and takes only its
 * **colour** for the space beside it. Nothing is upscaled, nothing is cropped, and the panel still
 * belongs to the book it is advertising.
 *
 * ## Why the average is not used raw
 *
 * An arbitrary average is an arbitrary background, and body text over an arbitrary background has
 * no contrast guarantee -- the same problem [AgehaBackdrop] used to solve by scrimming. A pale
 * shoujo cover averages to something near white and a black-gutter seinen cover to something near
 * black, and no single text colour serves both.
 *
 * [harmonise] therefore keeps the average's *hue*, clamps its saturation into a band that reads as
 * a tint rather than as a paint chip, and moves its lightness onto the rail the current theme
 * expects -- dark in a dark theme, pale in a light one. [contentFor] then measures a real WCAG
 * ratio on the result and picks the text colour that clears 4.5:1. `CoverAccentTest` runs that end
 * to end across the colour cube, so the guarantee is a property of the code rather than of
 * whichever covers someone happened to look at.
 *
 * This does not break rule 7. The colour is not brand, not a palette token and not a screen-local
 * constant: it is derived from the user's own artwork, and the derivation lives here in the design
 * system with every other colour decision in Ageha.
 */
@Immutable
data class CoverAccentColors(
	/** The panel fill: the cover's hue, on the current theme's lightness rail. */
	val container: Color,
	/** Text on [container]. Measured at 4.5:1 or better against it. */
	val content: Color,
	/** Secondary text: the same colour, faded as far as it can go and stay legible. */
	val mutedContent: Color,
)

object CoverAccent {

	/**
	 * How many pixels across the cover is sampled at.
	 *
	 * Small on purpose. This is an average, and an average of 24x36 samples sits within a rounding
	 * error of an average of the whole image at a fraction of the decode. It also means a
	 * watermark or a signature in one corner cannot drag the result anywhere.
	 */
	internal const val SAMPLE_WIDTH = 24
	internal const val SAMPLE_HEIGHT = 36

	/** Below this saturation the source is grey, and inventing a hue for it would be a lie. */
	private const val GREY_THRESHOLD = 0.05f

	/** Saturation band. Below it reads as a rendering fault; above it fights the cover beside it. */
	private const val MIN_SATURATION = 0.14f
	private const val MAX_SATURATION = 0.46f

	/**
	 * Where the panel sits on each theme's lightness rail.
	 *
	 * Pushed further from mid-grey than a first guess would put them, because the number that has
	 * to clear AA is not the title's -- it is [mutedContent]'s, the faded second line, and that
	 * one is the same text colour composited part-way back towards this fill. A dark rail at 0.24
	 * left it at 4.6:1 on a yellow cover, which is a pass with no margin at all and therefore a
	 * failure waiting for the next constant somebody nudges.
	 */
	private const val DARK_LIGHTNESS = 0.20f
	private const val LIGHT_LIGHTNESS = 0.84f

	/** The two text colours available. Paper and sumi -- never pure white, never pure black. */
	private val PAPER = Color(0xFFF7F4EC)
	private val SUMI = Color(0xFF16161A)

	/**
	 * As faded as a secondary line may get and still be text rather than decoration.
	 *
	 * Held to the same 4.5:1 floor as the title. Metadata in Ageha is set small, and the
	 * large-text exemption at 3:1 does not apply to it -- so the alpha is bounded by the contrast
	 * test rather than by how quiet it looked in one theme.
	 */
	private const val MUTED_ALPHA = 0.80f

	/** WCAG AA for body text. The floor the whole derivation is built backwards from. */
	internal const val AA_FLOOR = 4.5

	/**
	 * Derived accents, by cover url.
	 *
	 * Bounded and access-ordered: a library of ten thousand covers must not turn a colour cache
	 * into a heap leak, and the entries worth keeping are the ones recently on screen.
	 */
	private const val CACHE_SIZE = 128

	private val cache = object : LinkedHashMap<String, Color>(CACHE_SIZE, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?): Boolean =
			size > CACHE_SIZE
	}

	/**
	 * The accent for a cover, resolved for the current theme.
	 *
	 * Falls back to the theme's own container colour until the cover has been sampled, and keeps
	 * that fallback if the cover never loads. A hero that renders a hole while it waits on an
	 * image that may well 404 is worse than one that starts neutral and warms up.
	 */
	@Composable
	fun rememberFor(coverUrl: String?, imageHeaders: Map<String, String>): CoverAccentColors {
		val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
		val neutral = CoverAccentColors(
			container = MaterialTheme.colorScheme.surfaceContainerHigh,
			content = MaterialTheme.colorScheme.onSurface,
			mutedContent = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		var seed by remember(coverUrl) { mutableStateOf(coverUrl?.let(::cached)) }
		LaunchedEffect(coverUrl) {
			if (coverUrl.isNullOrEmpty() || seed != null) return@LaunchedEffect
			seed = sample(coverUrl, imageHeaders)
		}
		val resolved = seed ?: return neutral
		return remember(resolved, isDark) { colorsFor(resolved, isDark) }
	}

	/** The whole derivation, as one pure function. This is what the tests exercise. */
	internal fun colorsFor(seed: Color, isDark: Boolean): CoverAccentColors {
		val container = harmonise(seed, isDark)
		val content = contentFor(container)
		return CoverAccentColors(
			container = container,
			content = content,
			// Composited here rather than faded at draw time, so the muted line's contrast is a
			// number this function can be held to instead of a hope about what it lands on.
			mutedContent = blend(content, container, MUTED_ALPHA),
		)
	}

	private fun cached(url: String): Color? = synchronized(cache) { cache[url] }

	private suspend fun sample(url: String, headers: Map<String, String>): Color? =
		withContext(Dispatchers.Default) {
			// Failure here is ordinary -- a dead cover url, a source that gates images behind a
			// Referer we did not send, a format Skia declines. The hero stays neutral, silently:
			// there is nothing the user could do about it and nothing has been lost.
			runCatching {
				val loader = SingletonImageLoader.get(PlatformContext.INSTANCE)
				val request = AgehaImages.sampleRequest(url, headers, SAMPLE_WIDTH, SAMPLE_HEIGHT)
				val image = (loader.execute(request) as? SuccessResult)?.image
					?: return@runCatching null
				val bitmap = image.toBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT)
				val pixels = ArrayList<Int>(bitmap.width * bitmap.height)
				for (y in 0 until bitmap.height) {
					for (x in 0 until bitmap.width) pixels.add(bitmap.getColor(x, y))
				}
				average(pixels)
			}.getOrNull()?.also { seed -> synchronized(cache) { cache[url] = seed } }
		}

	/**
	 * The average of some ARGB pixels, averaged in **linear light**.
	 *
	 * Averaging sRGB values directly is the usual shortcut and it is wrong: sRGB is a perceptual
	 * encoding, so the arithmetic mean of two encoded values is not the colour halfway between
	 * them. On a high-contrast cover -- black ink on white paper, which is most of them -- the
	 * shortcut lands visibly darker than the page actually looks.
	 *
	 * Near-transparent pixels are skipped, or a cover served as a PNG with a transparent margin
	 * would average its own padding into the answer.
	 */
	internal fun average(argb: List<Int>): Color? {
		var red = 0.0
		var green = 0.0
		var blue = 0.0
		var counted = 0
		for (pixel in argb) {
			if ((pixel ushr 24 and 0xFF) < 16) continue
			red += toLinear((pixel ushr 16 and 0xFF) / 255f)
			green += toLinear((pixel ushr 8 and 0xFF) / 255f)
			blue += toLinear((pixel and 0xFF) / 255f)
			counted++
		}
		if (counted == 0) return null
		return Color(
			red = toSrgb(red / counted),
			green = toSrgb(green / counted),
			blue = toSrgb(blue / counted),
		)
	}

	/**
	 * Keep the hue, clamp the saturation, move the lightness onto the theme's rail.
	 *
	 * A grey cover stays grey. Pushing an achromatic average up to the saturation floor would
	 * invent a hue out of rounding noise, and a monochrome cover would then get a tint that
	 * changes every time the source re-encodes its thumbnail.
	 */
	internal fun harmonise(seed: Color, isDark: Boolean): Color {
		val (hue, saturation, _) = toHsl(seed)
		val target = if (saturation < GREY_THRESHOLD) {
			0f
		} else {
			saturation.coerceIn(MIN_SATURATION, MAX_SATURATION)
		}
		return fromHsl(hue, target, if (isDark) DARK_LIGHTNESS else LIGHT_LIGHTNESS)
	}

	/**
	 * Paper or sumi, whichever actually clears AA on this fill.
	 *
	 * Measured, not assumed. [harmonise] puts the fill well to one side of mid-grey so one of the
	 * two always wins comfortably -- but "always" is exactly the kind of claim that stops being
	 * true the first time somebody nudges a constant, so the ratio is computed either way.
	 */
	internal fun contentFor(container: Color): Color =
		if (contrast(PAPER, container) >= contrast(SUMI, container)) PAPER else SUMI

	/** WCAG 2.1 contrast, reimplemented rather than borrowed. See AgehaContrastTest for why. */
	internal fun contrast(a: Color, b: Color): Double {
		val la = relativeLuminance(a)
		val lb = relativeLuminance(b)
		return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
	}

	private fun relativeLuminance(color: Color): Double {
		fun channel(value: Float): Double {
			val c = value.toDouble()
			return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
		}
		return 0.2126 * channel(color.red) +
			0.7152 * channel(color.green) +
			0.0722 * channel(color.blue)
	}

	private fun blend(over: Color, under: Color, alpha: Float) = Color(
		red = over.red * alpha + under.red * (1 - alpha),
		green = over.green * alpha + under.green * (1 - alpha),
		blue = over.blue * alpha + under.blue * (1 - alpha),
	)

	private fun toLinear(channel: Float): Double {
		val c = channel.toDouble()
		return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
	}

	private fun toSrgb(linear: Double): Float {
		val c = linear.coerceIn(0.0, 1.0)
		val encoded = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
		return encoded.coerceIn(0.0, 1.0).toFloat()
	}

	/** Hue in degrees, saturation and lightness in 0..1. */
	internal fun toHsl(color: Color): Triple<Float, Float, Float> {
		val r = color.red
		val g = color.green
		val b = color.blue
		val max = maxOf(r, g, b)
		val min = minOf(r, g, b)
		val lightness = (max + min) / 2f
		if (max == min) return Triple(0f, 0f, lightness)
		val delta = max - min
		val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
		val hue = when (max) {
			r -> (g - b) / delta + if (g < b) 6f else 0f
			g -> (b - r) / delta + 2f
			else -> (r - g) / delta + 4f
		} * 60f
		return Triple(hue, saturation, lightness)
	}

	internal fun fromHsl(hue: Float, saturation: Float, lightness: Float): Color {
		if (saturation <= 0f) return Color(lightness, lightness, lightness)
		val chroma = (1f - abs(2f * lightness - 1f)) * saturation
		val sector = (((hue % 360f) + 360f) % 360f) / 60f
		val second = chroma * (1f - abs(sector % 2f - 1f))
		val (r, g, b) = when (sector.toInt()) {
			0 -> Triple(chroma, second, 0f)
			1 -> Triple(second, chroma, 0f)
			2 -> Triple(0f, chroma, second)
			3 -> Triple(0f, second, chroma)
			4 -> Triple(second, 0f, chroma)
			else -> Triple(chroma, 0f, second)
		}
		val lift = lightness - chroma / 2f
		return Color(
			(r + lift).coerceIn(0f, 1f),
			(g + lift).coerceIn(0f, 1f),
			(b + lift).coerceIn(0f, 1f),
		)
	}
}
