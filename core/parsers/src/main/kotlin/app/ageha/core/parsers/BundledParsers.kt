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
		// Re-extract if what is on disk no longer matches its lock. The bundled build is the
		// fallback everything else falls back to, so a corrupted copy of it is the one failure
		// with no recovery path -- and re-extracting costs a few hundred milliseconds once.
		if (installation.isInstalled(VERSION) &&
			installation.verify(VERSION) is LockVerification.Verified &&
			bridgeMatchesBundle(installation)
		) {
			return VERSION
		}

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
			// Locked while still staged, so the checksums describe exactly what was extracted.
			ParsersLock.write(staging, ParsersLock.create(VERSION, staging))
			target.deleteRecursively()
			check(staging.renameTo(target)) { "Could not place the bundled parsers build at $target" }
		} finally {
			staging.deleteRecursively()
		}
		return VERSION
	}

	/**
	 * Whether the extracted bridge is the one this build of Ageha ships.
	 *
	 * The lock check above cannot answer this. It asks whether what is on disk still matches what
	 * was written when it was extracted -- an *older* bridge, extracted cleanly, passes it
	 * perfectly. And the directory is keyed on [VERSION], which names the parsers commit and does
	 * not change when Ageha's own child-side code does. So without this, upgrading Ageha leaves
	 * the previous release's bridge jar in place permanently.
	 *
	 * That is not a theoretical staleness. The bridge implements `MangaLoaderContext` and calls
	 * `JsRuntime`, which is loaded parent-first and therefore comes from the *new* application:
	 * change a signature on that interface and the old bridge calls a method that no longer
	 * exists. It surfaces at runtime as `NoSuchMethodError` inside a source, which reads as a
	 * broken parser and sends you looking upstream at somebody else's code. It cost most of an
	 * afternoon exactly once.
	 *
	 * Hashing 80KB on launch is a fair price for never doing that again.
	 */
	private fun bridgeMatchesBundle(installation: ParsersInstallation): Boolean {
		val onDisk = installation.bridgeJarFor(VERSION).takeIf { it.isFile } ?: return false
		val bundled = javaClass.classLoader
			.getResourceAsStream("$RESOURCE_DIR/${ParsersInstallation.BRIDGE_JAR}")
		// No bundled copy is not a mismatch. It means this is a downloaded build rather than the
		// one shipped inside the app, and re-extracting over it would be actively wrong.
			?: return true
		val bundledDigest = bundled.use(::sha256)
		return bundledDigest == runCatching { ParsersLock.sha256(onDisk) }.getOrNull()
	}

	private fun sha256(stream: java.io.InputStream): String {
		val digest = java.security.MessageDigest.getInstance("SHA-256")
		val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
		while (true) {
			val read = stream.read(buffer)
			if (read < 0) break
			digest.update(buffer, 0, read)
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
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
