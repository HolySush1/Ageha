package app.ageha.brandkit

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.pow

/**
 * Builds every shipped icon and logo asset from `brand/ageha-logo-source.jpg`.
 *
 * The source is a raster JPEG on a *fake* checkerboard -- it has no alpha channel at all, just a
 * drawn grey-and-white grid standing in for one. Three properties of the artwork drive
 * everything below:
 *
 *  1. It is a **negative** seal. The square is inked and the butterfly is the paper showing
 *     through it. The mark is the stamp, not a free-floating butterfly, so the icon is a square
 *     and not a silhouette on transparency.
 *  2. The butterfly's washi interior and the fake checkerboard are both very light, and only
 *     about 13 units apart in red-minus-blue. Keying on colour would eat one or keep the other,
 *     so the key runs on *connectivity* instead -- see [Mask.reachableFromBorder].
 *  3. The fine wing veining is ink islands inside the washi field. Below roughly 32 pixels they
 *     merge into mud, which is why the small sizes are drawn from a hole-filled silhouette
 *     rather than downscaled from the detailed master.
 *
 * Run with `./gradlew :tools:brandkit:generateIcons`.
 */
object IconGenerator {

	/** Below this saturation a pixel is paper or checkerboard rather than ink. */
	private const val NEUTRAL_SATURATION = 0.09

	/** The fake checkerboard's darker square sits at 223; nothing in the artwork is that flat. */
	private const val BACKGROUND_MIN_VALUE = 190

	/** Ink is unambiguous well below this; the threshold only has to separate ink from washi. */
	private const val INK_SATURATION = 0.35

	/** Anything neutral and this small is JPEG ringing along the key edge, not ink spatter. */
	private const val SPECKLE_AREA = 6

	/**
	 * Half the widest ink vein, in source pixels, used to close the butterfly back together.
	 *
	 * Too small and the wings stay separate; too large and the closing reaches across the ink
	 * between the butterfly and the disc below it and fuses them into one blob. The dump task
	 * (`:tools:brandkit:dumpMasks`) prints each knockout's bounding box, which is how this was
	 * settled rather than guessed.
	 */
	private const val VEIN_BRIDGE_RADIUS = 6

	/** How far in from the seal's edge a knockout must sit to count as one. See [analyse]. */
	private const val SEAL_RIM_EROSION = 10

	private val VERMILLION = Color(Brand.VERMILLION, true)
	private val PAPER = Color(Brand.PAPER, true)

	fun run(repoRoot: File) {
		val source = ImageIO.read(File(repoRoot, "brand/ageha-logo-source.jpg"))
			?: error("brand/ageha-logo-source.jpg is missing or unreadable")
		val out = File(repoRoot, "brand/generated").apply { mkdirs() }

		val analysis = analyse(source)
		println("keyed ${analysis.opaque.count()} opaque px of ${source.width * source.height}")

		val master = renderMaster(source, analysis)
		ImageIO.write(master, "png", File(out, "ageha-master-1024.png"))

		val mark = simplifiedMark(analysis)
		File(out, "ageha-mark.svg").writeText(svg(mark, twoColour = true))
		File(out, "ageha-mono.svg").writeText(svg(mark, twoColour = false))

		writePngSet(out, master, mark)
		writeIco(out, master, mark)
		writeIcns(out, master, mark)
		writeTray(out, mark)
		writeRuntimeResources(repoRoot, master, mark)

		println("wrote ${out.listFiles()?.size ?: 0} entries to brand/generated/")
	}

	// ---------------------------------------------------------------- keying

	/** The masks every later stage is built from. */
	class Analysis(
		val argb: IntArray,
		val width: Int,
		val height: Int,
		/** Everything that is part of the artwork: the whole stamp, spatter included. */
		val opaque: Mask,
		/** The seal's outline with the butterfly and every speckle hole filled in. */
		val seal: Mask,
		/** The butterfly, flattened to a silhouette -- no interior veining. */
		val butterfly: Mask,
		/** The small disc beneath the body, likewise flattened. */
		val circle: Mask,
	)

	fun analyse(source: BufferedImage): Analysis {
		val w = source.width
		val h = source.height
		val argb = source.toArgbArray()

		val backgroundCandidate = Mask(w, h)
		for (i in argb.indices) {
			val p = argb[i]
			backgroundCandidate.bits[i] =
				saturationOf(p) < NEUTRAL_SATURATION &&
				valueOf(p) >= BACKGROUND_MIN_VALUE &&
				abs(warmthOf(p)) <= 6
		}
		val background = backgroundCandidate.reachableFromBorder()
		var opaque = background.invert()

		// Drop the JPEG ringing the key leaves behind: tiny, neutral, disconnected. The genuine
		// ink spatter around the stamp is saturated, so this cannot eat it.
		val kept = Mask(w, h)
		for (component in opaque.componentIndices()) {
			val keepIt = component.size >= SPECKLE_AREA ||
				component.any { saturationOf(argb[it]) > INK_SATURATION }
			if (keepIt) for (i in component) kept.bits[i] = true
		}
		opaque = kept

		val seal = opaque.maskOf(opaque.componentIndices().first()).fillHoles()

		// Knockouts are washi *strictly inside* the seal.
		//
		// The "strictly" is load-bearing. JPEG blur along the seal's outer edge leaves a
		// hairline of desaturated pixels that reads as washi and forms a closed ring around the
		// entire stamp. Left in, it is the second-largest knockout, and filling its holes turns
		// it into the whole seal -- which is how the first attempt produced an icon with no
		// butterfly in it at all. Eroding the seal before intersecting removes the rim without
		// touching anything genuinely interior.
		val sealInterior = seal.erode(SEAL_RIM_EROSION)
		val washi = Mask(w, h)
		for (i in argb.indices) {
			washi.bits[i] = sealInterior.bits[i] && opaque.bits[i] && saturationOf(argb[i]) <= INK_SATURATION
		}
		// These are *fragments* -- the ink veining cuts the butterfly into wings, body and
		// antennae -- so they get closed together before anything asks which is the butterfly.
		val knockoutFragments = Mask(w, h)
		for (component in washi.componentIndices()) {
			if (component.size < SPECKLE_AREA) continue
			for (i in component) knockoutFragments.bits[i] = true
		}
		val closed = knockoutFragments.close(VEIN_BRIDGE_RADIUS)
		val knockouts = closed.componentIndices()
		val butterfly = knockouts.getOrNull(0)?.let { closed.maskOf(it).fillHoles() } ?: Mask(w, h)
		val circle = knockouts.getOrNull(1)?.let { closed.maskOf(it).fillHoles() } ?: Mask(w, h)

		return Analysis(argb, w, h, opaque, seal, butterfly, circle)
	}

	// ---------------------------------------------------------------- master

	/**
	 * The detailed master: the source artwork with a real alpha channel, cropped to the stamp and
	 * centred on a square 1024 canvas.
	 *
	 * Edge pixels get partial alpha from how many of their neighbours the key removed. Without
	 * that the stamp acquires a hard jagged border, which reads as a bad cut-out and undoes the
	 * texture the artwork is carrying.
	 */
	fun renderMaster(source: BufferedImage, analysis: Analysis): BufferedImage {
		val w = analysis.width
		val h = analysis.height
		val keyed = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until h) {
			for (x in 0 until w) {
				if (!analysis.opaque[x, y]) continue
				var neighbours = 0
				var solid = 0
				for (dy in -1..1) for (dx in -1..1) {
					if (x + dx !in 0 until w || y + dy !in 0 until h) continue
					neighbours++
					if (analysis.opaque[x + dx, y + dy]) solid++
				}
				val alpha = (255.0 * solid / neighbours).toInt().coerceIn(0, 255)
				val rgb = analysis.argb[y * w + x] and 0xFFFFFF
				keyed.setRGB(x, y, (alpha shl 24) or rgb)
			}
		}
		val box = analysis.opaque.boundingBox()
		val cropped = keyed.getSubimage(box[0], box[1], box[2] - box[0] + 1, box[3] - box[1] + 1)
		return fitSquare(cropped, 1024, inset = 0.965)
	}

	// ---------------------------------------------------------------- simplified mark

	/** The simplified seal: outer outline, butterfly knockout, disc knockout. Nothing else. */
	class Mark(val rings: List<Ring>, val sourceSize: Int)

	/**
	 * Traces the flattened masks into one even-odd path.
	 *
	 * The epsilons differ on purpose. The seal's outline is simplified loosely, because its
	 * roughness is the point -- it has to look pressed by hand. The butterfly is simplified more
	 * tightly, because its shape is what makes the mark recognisable at 16 pixels and a
	 * over-relaxed wing stops reading as a wing.
	 */
	fun simplifiedMark(analysis: Analysis): Mark {
		val size = maxOf(analysis.width, analysis.height)
		val sealRings = Trace.rings(analysis.seal).take(1).map { Trace.simplify(it, epsilon = 1.6) }
		val butterflyRings = Trace.rings(analysis.butterfly).take(1).map { Trace.simplify(it, epsilon = 1.1) }
		val circleRings = Trace.rings(analysis.circle).take(1).map { Trace.simplify(it, epsilon = 0.8) }
		val rings = sealRings + butterflyRings + circleRings
		println(
			"simplified mark: ${rings.sumOf { it.size }} points " +
				"(seal ${sealRings.sumOf { it.size }}, butterfly ${butterflyRings.sumOf { it.size }}, " +
				"disc ${circleRings.sumOf { it.size }})",
		)
		return Mark(rings, size)
	}

	private fun svg(mark: Mark, twoColour: Boolean): String {
		val fill = if (twoColour) Brand.VERMILLION.toHex() else "currentColor"
		val d = Trace.toSvgPath(mark.rings, scale = 1024.0 / mark.sourceSize)
		return buildString {
			appendLine("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">""")
			appendLine("""  <title>Ageha</title>""")
			appendLine(
				if (twoColour) {
					"""  <!-- The butterfly and disc are knockouts, not painted shapes: even-odd fill leaves the surface behind them showing through, exactly as the seal does on paper. -->"""
				} else {
					"""  <!-- Single-colour variant for tray icons, disabled states and watermarks. Inherits currentColor. -->"""
				},
			)
			appendLine("""  <path fill-rule="evenodd" fill="$fill" d="$d"/>""")
			append("</svg>")
		}
	}

	/** Rasterises the simplified mark at [size], in [colour], on a transparent canvas. */
	fun renderMark(mark: Mark, size: Int, colour: Color, inset: Double = 0.94): BufferedImage {
		val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
		val g = image.createGraphics()
		g.quality()
		val scale = size * inset / mark.sourceSize
		val offset = size * (1 - inset) / 2
		g.transform = AffineTransform(scale, 0.0, 0.0, scale, offset, offset)
		g.color = colour
		g.fill(Trace.toPath(mark.rings))
		g.dispose()
		return image
	}

	// ---------------------------------------------------------------- outputs

	/**
	 * The size below which the detailed master is abandoned for the simplified mark.
	 *
	 * 32 and under is where the wing veining stops being detail and becomes noise. This is the
	 * one number in the pipeline that was set by looking rather than by measuring, and the
	 * gallery renders both variants side by side at every size so the call stays reviewable.
	 */
	private const val SIMPLIFIED_BELOW = 48

	private val LINUX_SIZES = listOf(16, 24, 32, 48, 64, 128, 256, 512)

	private fun iconAt(size: Int, master: BufferedImage, mark: Mark): BufferedImage =
		if (size < SIMPLIFIED_BELOW) renderMark(mark, size, VERMILLION) else scaleTo(master, size)

	private fun writePngSet(out: File, master: BufferedImage, mark: Mark) {
		val dir = File(out, "linux").apply { mkdirs() }
		for (size in LINUX_SIZES) {
			ImageIO.write(iconAt(size, master, mark), "png", File(dir, "ageha-$size.png"))
		}
	}

	private fun writeIco(out: File, master: BufferedImage, mark: Mark) {
		val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
		IconContainers.writeIco(sizes.map { iconAt(it, master, mark) }, File(out, "ageha.ico"))
	}

	/**
	 * macOS wants its own shape grid: a rounded square occupying about 80% of the canvas, with
	 * the rest left as margin for the system's shadow.
	 *
	 * Clipping the seal itself to that shape would shave off the rough stamped edge that gives it
	 * its character. So the seal is *placed on* a paper-coloured squircle instead of being cut
	 * into one -- which is also the more honest picture of the thing: a stamp pressed onto paper.
	 */
	private fun writeIcns(out: File, master: BufferedImage, mark: Mark) {
		val sizes = listOf(16, 32, 64, 128, 256, 512, 1024)
		val bySize = sizes.associateWith { size ->
			val canvas = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
			val g = canvas.createGraphics()
			g.quality()
			g.color = PAPER
			g.fill(squircle(size * 0.805, size))
			val artSize = (size * 0.66).toInt().coerceAtLeast(1)
			val art = if (size < SIMPLIFIED_BELOW) {
				renderMark(mark, artSize, VERMILLION, inset = 1.0)
			} else {
				scaleTo(master, artSize)
			}
			g.drawImage(art, (size - artSize) / 2, (size - artSize) / 2, null)
			g.dispose()
			canvas
		}
		IconContainers.writeIcns(bySize, File(out, "ageha.icns"))
	}

	/**
	 * Tray icons, monochrome.
	 *
	 * System trays tint or invert whatever they are given depending on the platform and the
	 * user's theme, so a two-colour mark is a liability there. Both polarities are written and
	 * the app picks by measured tray background at runtime.
	 */
	private fun writeTray(out: File, mark: Mark) {
		val dir = File(out, "tray").apply { mkdirs() }
		for (size in listOf(16, 20, 24, 32)) {
			ImageIO.write(renderMark(mark, size, Color(Brand.SUMI, true)), "png", File(dir, "tray-dark-$size.png"))
			ImageIO.write(renderMark(mark, size, Color(Brand.PAPER, true)), "png", File(dir, "tray-light-$size.png"))
		}
	}

	/**
	 * The subset the running application needs on its classpath, as opposed to the matrix the
	 * *installer* needs.
	 *
	 * These two audiences want different things and conflating them is why icon directories rot.
	 * Packaging wants `.ico`, `.icns` and a full PNG ladder, consumed once at build time by
	 * Conveyor. The app itself only ever needs a window icon and the tray pair, and needs them
	 * loadable from the classpath at runtime. So they are written separately rather than the app
	 * reaching into a directory laid out for a packager.
	 */
	private fun writeRuntimeResources(repoRoot: File, master: BufferedImage, mark: Mark) {
		val dir = File(repoRoot, "core/designsystem/src/main/resources/app/ageha/brand").apply { mkdirs() }
		// The window icon is handed to the OS, which rescales it for the taskbar and the
		// alt-tab switcher itself. Give it enough pixels to do that well.
		for (size in listOf(32, 64, 128, 256, 512)) {
			ImageIO.write(iconAt(size, master, mark), "png", File(dir, "icon-$size.png"))
		}
		for (size in listOf(16, 20, 24, 32)) {
			ImageIO.write(renderMark(mark, size, Color(Brand.SUMI, true)), "png", File(dir, "tray-dark-$size.png"))
			ImageIO.write(renderMark(mark, size, Color(Brand.PAPER, true)), "png", File(dir, "tray-light-$size.png"))
		}
		// The gallery shows the simplified mark against the detailed master at matched sizes, so
		// the crossover at SIMPLIFIED_BELOW stays a reviewable decision rather than a constant.
		for (size in listOf(16, 24, 32, 48, 64)) {
			ImageIO.write(renderMark(mark, size, VERMILLION), "png", File(dir, "simplified-$size.png"))
			ImageIO.write(scaleTo(master, size), "png", File(dir, "detailed-$size.png"))
		}
	}

	// ---------------------------------------------------------------- geometry helpers

	/**
	 * Apple's icon corner is a continuous-curvature squircle, not a rounded rectangle -- a
	 * circular-arc corner next to a real macOS icon reads as visibly wrong. A superellipse at
	 * exponent 5 is the usual close approximation and is well within a pixel at these sizes.
	 */
	fun squircle(side: Double, canvas: Int, exponent: Double = 5.0): Path2D.Double {
		val path = Path2D.Double()
		val radius = side / 2
		val centre = canvas / 2.0
		val steps = 512
		for (i in 0 until steps) {
			val theta = 2 * Math.PI * i / steps
			val c = Math.cos(theta)
			val s = Math.sin(theta)
			val x = centre + radius * Math.signum(c) * abs(c).pow(2.0 / exponent)
			val y = centre + radius * Math.signum(s) * abs(s).pow(2.0 / exponent)
			if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
		}
		path.closePath()
		return path
	}

	/** Centres [image] on a transparent square canvas of [size], scaled to [inset] of it. */
	fun fitSquare(image: BufferedImage, size: Int, inset: Double): BufferedImage {
		val target = (size * inset).toInt()
		val scale = target.toDouble() / maxOf(image.width, image.height)
		val w = (image.width * scale).toInt().coerceAtLeast(1)
		val h = (image.height * scale).toInt().coerceAtLeast(1)
		val scaled = scaleTo(image, w, h)
		val canvas = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
		val g = canvas.createGraphics()
		g.quality()
		g.drawImage(scaled, (size - w) / 2, (size - h) / 2, null)
		g.dispose()
		return canvas
	}

	fun scaleTo(image: BufferedImage, size: Int): BufferedImage = scaleTo(image, size, size)

	/**
	 * Progressive halving down to the target.
	 *
	 * A single bicubic step from 1024 to 48 samples far too sparsely and drops thin features --
	 * on this artwork it eats the antennae outright. Halving repeatedly keeps every pass close to
	 * a 2:1 ratio, where bilinear filtering actually sees all the source pixels.
	 */
	fun scaleTo(image: BufferedImage, width: Int, height: Int): BufferedImage {
		var current = image
		while (current.width / 2 >= width && current.height / 2 >= height && current.width > 2) {
			current = resample(current, current.width / 2, current.height / 2)
		}
		return if (current.width == width && current.height == height) current else resample(current, width, height)
	}

	private fun resample(image: BufferedImage, width: Int, height: Int): BufferedImage {
		val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		val g = out.createGraphics()
		g.quality()
		g.drawImage(image, 0, 0, width, height, null)
		g.dispose()
		return out
	}

	private fun Graphics2D.quality() {
		setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
		setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
		setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
		stroke = BasicStroke(1f)
	}
}

fun main(args: Array<String>) {
	IconGenerator.run(File(args.firstOrNull() ?: "."))
}
