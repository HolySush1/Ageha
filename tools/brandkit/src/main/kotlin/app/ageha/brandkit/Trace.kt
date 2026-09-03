package app.ageha.brandkit

import java.awt.geom.Path2D
import kotlin.math.abs
import kotlin.math.hypot

/** A closed ring of points in image coordinates. */
typealias Ring = List<DoubleArray>

/**
 * Turns a [Mask] into closed outlines.
 *
 * The method is crack following: walk the unit edges that separate a set pixel from an unset one.
 * That is exact -- no threshold, no resampling -- and it yields a staircase polygon that
 * [simplify] then relaxes into something a human would call a curve. Marching squares would
 * produce a smoother first pass but blurs the very speckles and rough stamped edges that make
 * this logo look stamped rather than printed, and those are worth keeping.
 */
object Trace {

	/**
	 * Every closed ring bounding [mask], outer boundaries and holes alike.
	 *
	 * Orientation is consistent -- outer rings run one way, holes the other -- but nothing
	 * downstream relies on it, because every path is filled even-odd. That is what lets the
	 * butterfly be a *knockout* through the seal rather than a shape painted on top of it: one
	 * path, two rings, and the overlap is simply absent.
	 */
	fun rings(mask: Mask): List<Ring> {
		// Directed unit edges, interior kept on a consistent side.
		val outgoing = HashMap<Long, MutableList<Long>>()
		fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
		fun edge(x0: Int, y0: Int, x1: Int, y1: Int) {
			outgoing.getOrPut(key(x0, y0)) { mutableListOf() }.add(key(x1, y1))
		}
		for (y in 0 until mask.height) {
			for (x in 0 until mask.width) {
				if (!mask[x, y]) continue
				if (!mask[x, y - 1]) edge(x, y, x + 1, y)
				if (!mask[x + 1, y]) edge(x + 1, y, x + 1, y + 1)
				if (!mask[x, y + 1]) edge(x + 1, y + 1, x, y + 1)
				if (!mask[x - 1, y]) edge(x, y + 1, x, y)
			}
		}

		val result = mutableListOf<Ring>()
		while (outgoing.isNotEmpty()) {
			val start = outgoing.keys.first()
			val ring = mutableListOf<DoubleArray>()
			var current = start
			while (true) {
				val nexts = outgoing[current] ?: break
				val next = nexts.removeLast()
				if (nexts.isEmpty()) outgoing.remove(current)
				ring += doubleArrayOf(
					(current shr 32).toDouble(),
					(current and 0xFFFFFFFFL).toInt().toDouble(),
				)
				current = next
				if (current == start) break
			}
			if (ring.size >= 4) result += ring
		}
		return result.sortedByDescending { area(it) }
	}

	/** Absolute polygon area by the shoelace formula, used only to rank rings by size. */
	fun area(ring: Ring): Double {
		var sum = 0.0
		for (i in ring.indices) {
			val a = ring[i]
			val b = ring[(i + 1) % ring.size]
			sum += a[0] * b[1] - b[0] * a[1]
		}
		return abs(sum) / 2.0
	}

	/**
	 * Ramer-Douglas-Peucker, applied around the ring.
	 *
	 * [epsilon] is in source pixels and is the one knob that decides how much of the stamp's
	 * roughness survives. Too small and the SVG carries thousands of one-pixel steps; too large
	 * and the seal turns into a rounded rectangle and stops looking hand-pressed.
	 */
	fun simplify(ring: Ring, epsilon: Double): Ring {
		if (ring.size < 4) return ring
		// Split the closed ring at its two most distant points so RDP, which is defined on open
		// polylines, cannot collapse the whole thing to a single segment.
		val start = 0
		var far = 0
		var farDist = -1.0
		for (i in ring.indices) {
			val d = hypot(ring[i][0] - ring[start][0], ring[i][1] - ring[start][1])
			if (d > farDist) { farDist = d; far = i }
		}
		val first = rdp(ring.subList(minOf(start, far), maxOf(start, far) + 1), epsilon)
		val secondRaw = ring.subList(maxOf(start, far), ring.size) + ring[0]
		val second = rdp(secondRaw, epsilon)
		return first + second.subList(1, second.size - 1)
	}

	private fun rdp(points: List<DoubleArray>, epsilon: Double): List<DoubleArray> {
		if (points.size < 3) return points
		val a = points.first()
		val b = points.last()
		var index = 0
		var maxDist = 0.0
		for (i in 1 until points.size - 1) {
			val d = perpendicularDistance(points[i], a, b)
			if (d > maxDist) { maxDist = d; index = i }
		}
		return if (maxDist > epsilon) {
			val left = rdp(points.subList(0, index + 1), epsilon)
			val right = rdp(points.subList(index, points.size), epsilon)
			left.subList(0, left.size - 1) + right
		} else {
			listOf(a, b)
		}
	}

	private fun perpendicularDistance(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
		val dx = b[0] - a[0]
		val dy = b[1] - a[1]
		val len = hypot(dx, dy)
		if (len == 0.0) return hypot(p[0] - a[0], p[1] - a[1])
		return abs(dy * p[0] - dx * p[1] + b[0] * a[1] - b[1] * a[0]) / len
	}

	/** Rings as one even-odd [Path2D], in image coordinates. */
	fun toPath(rings: List<Ring>): Path2D.Double {
		val path = Path2D.Double(Path2D.WIND_EVEN_ODD)
		for (ring in rings) {
			if (ring.isEmpty()) continue
			path.moveTo(ring[0][0], ring[0][1])
			for (i in 1 until ring.size) path.lineTo(ring[i][0], ring[i][1])
			path.closePath()
		}
		return path
	}

	/** Rings as an SVG `d` attribute, scaled by [scale] and rounded to [decimals]. */
	fun toSvgPath(rings: List<Ring>, scale: Double = 1.0, decimals: Int = 2): String {
		val fmt = "%.${decimals}f"
		val sb = StringBuilder()
		for (ring in rings) {
			if (ring.isEmpty()) continue
			for ((i, p) in ring.withIndex()) {
				sb.append(if (i == 0) "M" else "L")
				sb.append(fmt.format(p[0] * scale))
				sb.append(' ')
				sb.append(fmt.format(p[1] * scale))
				sb.append(' ')
			}
			sb.append("Z ")
		}
		return sb.toString().trim()
	}
}
