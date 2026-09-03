package app.ageha.core.parsers

import java.io.File

/**
 * The known-good parsers build shipped inside Ageha.
 *
 * It travels as a resource and is extracted on first use, rather than being read from inside the
 * application jar, because a classloader needs a real file URL. Extracting it also means the
 * bundled build and a downloaded one take exactly the same code path -- so the path that matters
 * most is the one exercised every launch, rather than only during an upgrade.
 *
 * Three kinds of file are extracted: the parsers library, Ageha's own child-side bridge, and the
 * parsers library's dependencies. That last group is easy to forget and fails obscurely: Android
 * excludes `org.json` because the platform supplies it, and a JVM host that does the same gets
 * `NoClassDefFoundError` from deep inside an unrelated parser. The set is resolved at build time
 * rather than listed by hand -- doing so turned up `androidx.annotation`, which nothing in the
 * documentation mentions.
 */
object BundledParsers {

	/**
	 * The parsers build compiled into this app.
	 *
	 * Upstream publishes no version tags, so this is a commit SHA on
	 * `Kotatsu-Redo/kotatsu-parsers-redo` (docs/FINDINGS.md 1). It must match `parsers` in
	 * `gradle/libs.versions.toml`, and `:core:parsers:checkBundledVersion` fails the build if it
	 * does not -- a version string that lies makes every diagnostic downstream lie too.
	 */
	const val VERSION = "434030d481"

	private const val RESOURCE_DIR = "app/ageha/bundled"
	private const val INDEX_RESOURCE = "$RESOURCE_DIR/index.txt"

	/**
	 * Extract the bundled build into [installation] if it is not already there.
	 *
	 * @return the version now on disk.
	 */
	fun ensureExtracted(installation: ParsersInstallation): String {
		if (installation.isInstalled(VERSION)) return VERSION

		val index = readIndex()
		val target = installation.directoryFor(VERSION)
		// Extract into a scratch directory and move it into place at the end, so an interrupted
		// first launch cannot leave a half-populated build that then looks installed.
		val staging = File(target.parentFile, VERSION + ".staging")
		staging.deleteRecursively()
		staging.mkdirs()
		try {
			for (entry in index) {
				extract("$RESOURCE_DIR/$entry", File(staging, entry))
			}
			target.deleteRecursively()
			check(staging.renameTo(target)) { "Could not place the bundled parsers build at $target" }
		} finally {
			staging.deleteRecursively()
		}
		return VERSION
	}

	private fun readIndex(): List<String> {
		val stream = javaClass.classLoader.getResourceAsStream(INDEX_RESOURCE)
			?: error(
				"Bundled parsers resources are missing ('$INDEX_RESOURCE'). They are staged by " +
					"the :core:parsers build; run ./gradlew :core:parsers:processResources.",
			)
		return stream.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
	}

	private fun extract(resource: String, target: File) {
		val stream = javaClass.classLoader.getResourceAsStream(resource)
			?: error("Bundled resource '$resource' is listed in the index but not present.")
		target.parentFile.mkdirs()
		stream.use { input -> target.outputStream().use(input::copyTo) }
	}
}
