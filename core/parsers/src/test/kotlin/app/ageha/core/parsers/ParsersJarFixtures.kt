package app.ageha.core.parsers

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/**
 * Builds parser-jar fixtures for the Layer 1 tests.
 *
 * These are **synthesised, not downloaded**. A test that fetches a real older build from JitPack
 * would need the network, would be at the mercy of a third party's uptime, and would quietly stop
 * testing what it was written to test the day upstream's history changed shape. Compiling a tiny
 * class here keeps the test hermetic and keeps the incompatibility exactly as narrow as intended.
 *
 * The JDK ships a Java compiler behind [ToolProvider], so no build-time machinery is needed.
 */
object ParsersJarFixtures {

	/**
	 * A jar containing one class that deliberately conflicts with the real parsers library.
	 *
	 * Placed ahead of the genuine jar on the child classloader's search path, it shadows the real
	 * `MangaLoaderContext` with one of a different shape -- which is precisely what an upstream
	 * change to the host contract looks like from Ageha's side. `evaluateJs` gaining a third
	 * parameter, the change that motivated this whole gate, was exactly this.
	 */
	fun incompatibleLoaderContextJar(dir: File): File = compileToJar(
		dir = dir,
		jarName = "incompatible-parsers.jar",
		className = "org.koitharu.kotatsu.parsers.MangaLoaderContext",
		source = """
			package org.koitharu.kotatsu.parsers;

			/**
			 * Stands in for a future upstream MangaLoaderContext that Ageha was not built against:
			 * it declares an abstract member no existing subclass overrides, and none of the
			 * members Ageha does override.
			 */
			public abstract class MangaLoaderContext {
			    public abstract String somethingUpstreamAddedLater();
			}
		""".trimIndent(),
	)

	/** A jar with nothing in it, standing in for a build that produced no usable output. */
	fun emptyJar(dir: File): File {
		val jar = File(dir, "empty-parsers.jar")
		JarOutputStream(jar.outputStream()).use { }
		return jar
	}

	/** A file that is not a jar at all, standing in for a truncated or corrupted download. */
	fun corruptJar(dir: File): File =
		File(dir, "corrupt-parsers.jar").apply { writeText("this is not a zip archive") }

	private fun compileToJar(dir: File, jarName: String, className: String, source: String): File {
		val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
			"No system Java compiler. These tests need a JDK, not a JRE."
		}
		val work = File(dir, "fixture-src").apply { mkdirs() }
		val classes = File(dir, "fixture-classes").apply { mkdirs() }

		val relative = className.replace('.', '/') + ".java"
		val sourceFile = File(work, relative).apply {
			parentFile.mkdirs()
			writeText(source)
		}

		val ok = compiler.run(null, null, null, "-d", classes.path, sourceFile.path)
		check(ok == 0) { "Could not compile the test fixture $className" }

		val jar = File(dir, jarName)
		JarOutputStream(jar.outputStream()).use { out ->
			classes.walkTopDown().filter { it.isFile }.forEach { file ->
				val entry = file.relativeTo(classes).invariantSeparatorsPath
				out.putNextEntry(JarEntry(entry))
				file.inputStream().use { it.copyTo(out) }
				out.closeEntry()
			}
		}
		return jar
	}
}
