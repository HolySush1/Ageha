package app.ageha.brandkit

import java.awt.image.BufferedImage

/**
 * A boolean mask over an image, with the connectivity operations the icon pipeline needs.
 *
 * Everything here is deliberately plain: the pipeline runs by hand a few times a year, so
 * clarity beats speed, and pulling in an imaging library for four operations would be a poor
 * trade against a dependency the build has to carry forever.
 */
class Mask(val width: Int, val height: Int, val bits: BooleanArray = BooleanArray(width * height)) {

	operator fun get(x: Int, y: Int): Boolean =
		x in 0 until width && y in 0 until height && bits[y * width + x]

	operator fun set(x: Int, y: Int, value: Boolean) {
		if (x in 0 until width && y in 0 until height) bits[y * width + x] = value
	}

	fun count(): Int = bits.count { it }

	fun copy(): Mask = Mask(width, height, bits.copyOf())

	fun invert(): Mask = Mask(width, height, BooleanArray(bits.size) { !bits[it] })

	fun or(other: Mask): Mask = Mask(width, height, BooleanArray(bits.size) { bits[it] || other.bits[it] })

	fun and(other: Mask): Mask = Mask(width, height, BooleanArray(bits.size) { bits[it] && other.bits[it] })

	/**
	 * Four-connected flood fill from every border pixel that satisfies the mask, returning the
	 * reached set.
	 *
	 * Reaching in from the border is what makes the background key robust. The checkerboard the
	 * source JPG fakes transparency with is a neutral grey-and-white grid, and the butterfly's
	 * washi interior is *also* very light -- the two are only 13 units apart in red-minus-blue.
	 * Keying on colour alone would either eat the butterfly or leave grey squares behind.
	 * Keying on connectivity cannot: the butterfly is enclosed by ink on every side.
	 */
	fun reachableFromBorder(): Mask {
		val out = Mask(width, height)
		val stack = ArrayDeque<Int>()
		fun push(x: Int, y: Int) {
			if (x !in 0 until width || y !in 0 until height) return
			val i = y * width + x
			if (!bits[i] || out.bits[i]) return
			out.bits[i] = true
			stack.addLast(i)
		}
		for (x in 0 until width) {
			push(x, 0); push(x, height - 1)
		}
		for (y in 0 until height) {
			push(0, y); push(width - 1, y)
		}
		while (stack.isNotEmpty()) {
			val i = stack.removeLast()
			val x = i % width
			val y = i / width
			push(x - 1, y); push(x + 1, y); push(x, y - 1); push(x, y + 1)
		}
		return out
	}

	/**
	 * Fills every enclosed hole: anything not in the mask and not reachable from the border joins
	 * the mask.
	 *
	 * This is how the butterfly's interior vein linework disappears from the simplified mark. The
	 * veins are ink islands sitting inside the butterfly's washi field, so they are exactly the
	 * "not mask, not reachable from outside" set. Filling them turns a finely detailed engraving
	 * into the flat silhouette that survives being 16 pixels wide.
	 */
	fun fillHoles(): Mask = invert().reachableFromBorder().invert()

	/**
	 * Four-connected components as pixel-index lists, largest first.
	 *
	 * Indices rather than masks, because a mask costs one boolean per pixel of the *whole image*
	 * regardless of how few pixels the component holds. On the real logo the washi splits into
	 * several hundred fragments -- every speck of paper showing through the ink is one -- and
	 * materialising a full-frame mask for each exhausted the heap outright. An index list costs
	 * what the component actually occupies.
	 */
	fun componentIndices(): List<IntArray> {
		val seen = BooleanArray(bits.size)
		val result = mutableListOf<IntArray>()
		val stack = ArrayDeque<Int>()
		for (start in bits.indices) {
			if (!bits[start] || seen[start]) continue
			val members = ArrayList<Int>()
			seen[start] = true
			stack.addLast(start)
			while (stack.isNotEmpty()) {
				val i = stack.removeLast()
				members.add(i)
				val x = i % width
				val y = i / width
				for ((dx, dy) in NEIGHBOURS) {
					val nx = x + dx
					val ny = y + dy
					if (nx !in 0 until width || ny !in 0 until height) continue
					val ni = ny * width + nx
					if (!bits[ni] || seen[ni]) continue
					seen[ni] = true
					stack.addLast(ni)
				}
			}
			result += members.toIntArray()
		}
		result.sortByDescending { it.size }
		return result
	}

	/** One component's indices as a mask of this image's dimensions. */
	fun maskOf(indices: IntArray): Mask = Mask(width, height).also {
		for (i in indices) it.bits[i] = true
	}

	/**
	 * Four-connected components as masks, largest first.
	 *
	 * Convenient, and safe only when the count is known to be small. Anything that might face
	 * hundreds of components should walk [componentIndices] instead.
	 */
	fun components(): List<Mask> = componentIndices().map { maskOf(it) }

	/**
	 * Morphological dilation by a square of the given radius, done as two separable 1-D passes.
	 *
	 * A square structuring element rather than a disc: the difference is invisible once the
	 * result is eroded back and traced, and separability turns an O(r^2) operation into O(r).
	 */
	fun dilate(radius: Int): Mask {
		if (radius <= 0) return copy()
		val horizontal = Mask(width, height)
		for (y in 0 until height) {
			for (x in 0 until width) {
				if (!this[x, y]) continue
				for (dx in -radius..radius) horizontal[x + dx, y] = true
			}
		}
		val out = Mask(width, height)
		for (y in 0 until height) {
			for (x in 0 until width) {
				if (!horizontal[x, y]) continue
				for (dy in -radius..radius) out[x, y + dy] = true
			}
		}
		return out
	}

	/** Morphological erosion. Pixels outside the image count as unset, so edges erode inward. */
	fun erode(radius: Int): Mask = invert().dilate(radius).invert()

	/**
	 * Dilate then erode: closes gaps narrower than twice the radius while leaving the shape's
	 * outer boundary essentially where it was.
	 *
	 * This is what turns the engraved butterfly back into a butterfly. Its wings, body and
	 * antennae are separate patches of washi in the source, fully cut apart from one another by
	 * the ink vein strokes, so a plain connected-component pass finds seven-odd fragments and no
	 * butterfly at all. Closing bridges the veins -- they are only a few pixels wide -- and the
	 * fragments become the one silhouette the small icon sizes need.
	 */
	fun close(radius: Int): Mask = dilate(radius).erode(radius)

	fun boundingBox(): IntArray {
		var minX = width; var minY = height; var maxX = -1; var maxY = -1
		for (y in 0 until height) {
			for (x in 0 until width) {
				if (!this[x, y]) continue
				if (x < minX) minX = x
				if (x > maxX) maxX = x
				if (y < minY) minY = y
				if (y > maxY) maxY = y
			}
		}
		return intArrayOf(minX, minY, maxX, maxY)
	}

	private companion object {
		val NEIGHBOURS = arrayOf(
			intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1),
		)
	}
}

/** Saturation in the HSV sense, 0..1, from an opaque `0xAARRGGBB`. */
fun saturationOf(argb: Int): Double {
	val r = (argb shr 16) and 0xFF
	val g = (argb shr 8) and 0xFF
	val b = argb and 0xFF
	val mx = maxOf(r, g, b)
	val mn = minOf(r, g, b)
	return if (mx == 0) 0.0 else (mx - mn) / mx.toDouble()
}

/** Value in the HSV sense, 0..255. */
fun valueOf(argb: Int): Int {
	val r = (argb shr 16) and 0xFF
	val g = (argb shr 8) and 0xFF
	val b = argb and 0xFF
	return maxOf(r, g, b)
}

/** How warm a pixel is: red minus blue. The washi tone sits near +13, the checkerboard at 0. */
fun warmthOf(argb: Int): Int = ((argb shr 16) and 0xFF) - (argb and 0xFF)

fun BufferedImage.toArgbArray(): IntArray = IntArray(width * height).also {
	getRGB(0, 0, width, height, it, 0, width)
}
