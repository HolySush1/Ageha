package app.ageha.brandkit

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Dumps the pipeline's intermediate masks so a bad trace can be looked at instead of guessed at. */
fun main(args: Array<String>) {
	val root = File(args[0])
	val dbg = File(args[1]).apply { mkdirs() }
	val src = ImageIO.read(File(root, "brand/ageha-logo-source.jpg"))
	val a = IconGenerator.analyse(src)
	fun dump(name: String, m: Mask) {
		val img = BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until m.height) for (x in 0 until m.width) {
			img.setRGB(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
		}
		ImageIO.write(img, "png", File(dbg, "$name.png"))
		println("$name: ${m.count()} px, components=${m.components().size}, bbox=${m.boundingBox().joinToString()}")
	}
	dump("opaque", a.opaque)
	dump("seal", a.seal)
	dump("butterfly", a.butterfly)
	dump("circle", a.circle)
	val mark = IconGenerator.simplifiedMark(a)
	ImageIO.write(IconGenerator.renderMark(mark, 512, Color(Brand.VERMILLION, true)), "png", File(dbg, "mark512.png"))
	for ((i, r) in mark.rings.withIndex()) println("ring $i: ${r.size} pts area=${Trace.area(r).toInt()}")
}
