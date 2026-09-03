package app.ageha.core.parsers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What is installed on disk, which build is live, and which ones must never be tried again.
 *
 * Layout under `AgehaPaths.parsersDir`:
 *
 * ```
 * parsers/
 *   state.json              which build is active, which is the fallback, what was rejected
 *   <sha>/
 *     kotatsu-parsers.jar
 *     ageha-bridge.jar      Ageha's child-side code, loaded with that build
 *     libs/                 the parsers library's own dependencies, at the versions it expects
 * ```
 *
 * The bridge jar is stored per build, not once. It is compiled against the parsers API, so a build
 * and the bridge that speaks to it belong together; sharing one bridge across builds would
 * reintroduce the version skew this whole layer exists to contain.
 */
class ParsersInstallation(
	private val root: File,
) {

	private val stateFile = File(root, "state.json")

	fun directoryFor(version: String) = File(root, version)

	fun parsersJarFor(version: String) = File(directoryFor(version), PARSERS_JAR)

	fun bridgeJarFor(version: String) = File(directoryFor(version), BRIDGE_JAR)

	/**
	 * The parsers library's own dependencies for this build.
	 *
	 * Stored per build rather than shared, because a future build may need different versions of
	 * them, and resolving that at load time is the entire point of keeping them in the child.
	 */
	fun libraryJarsFor(version: String): List<File> =
		File(directoryFor(version), LIBS_DIR).listFiles()
			?.filter { it.isFile && it.name.endsWith(".jar") }
			.orEmpty()
			.sorted()

	fun isInstalled(version: String): Boolean =
		parsersJarFor(version).isFile && bridgeJarFor(version).isFile

	fun read(): ParsersState = runCatching {
		if (!stateFile.isFile) return@runCatching ParsersState()
		json.decodeFromString<ParsersState>(stateFile.readText())
	}.getOrElse {
		// Unreadable state must not stop the app. Falling back to the bundled build costs the user
		// nothing but a re-check; refusing to start would cost them the app.
		ParsersState()
	}

	fun write(state: ParsersState) {
		root.mkdirs()
		val tmp = File(root, "state.json.tmp")
		tmp.writeText(json.encodeToString(state))
		if (!tmp.renameTo(stateFile)) {
			stateFile.delete()
			tmp.renameTo(stateFile)
		}
	}

	/**
	 * Promote [version] to active, keeping the outgoing build as the fallback.
	 *
	 * The previous build is deliberately not deleted. One-click rollback is only possible if the
	 * thing to roll back to is still on disk.
	 */
	fun activate(version: String) {
		val current = read()
		write(
			current.copy(
				activeVersion = version,
				lastKnownGoodVersion = current.activeVersion ?: current.lastKnownGoodVersion,
			),
		)
	}

	/**
	 * Record that [version] failed the gate, so no scheduled check ever downloads it again.
	 *
	 * Without this, a six-hourly update check re-fetches and re-rejects the same broken build
	 * forever: bandwidth, CPU, and a notification the user has already dismissed.
	 */
	fun reject(version: String, reason: String) {
		val current = read()
		write(
			current.copy(
				rejected = (current.rejected + (version to reason)).entries
					.sortedByDescending { it.key }
					.take(MAX_REMEMBERED_REJECTIONS)
					.associate { it.key to it.value },
			),
		)
		// The staged files are useless now, and they are not small.
		runCatching { directoryFor(version).deleteRecursively() }
	}

	/** Roll back to the last build known to work, if there is one. */
	fun rollBack(): String? {
		val current = read()
		val target = current.lastKnownGoodVersion ?: return null
		if (!isInstalled(target)) return null
		write(current.copy(activeVersion = target, lastKnownGoodVersion = null))
		return target
	}

	/** Remove installed builds that are neither active, the fallback, nor pinned. */
	fun pruneTo(keep: Set<String>) {
		root.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach {
			runCatching { it.deleteRecursively() }
		}
	}

	companion object {
		const val PARSERS_JAR = "kotatsu-parsers.jar"
		const val BRIDGE_JAR = "ageha-bridge.jar"

		/** Named here so the downloader stages into the same layout the loader reads from. */
		const val LIBS_DIR = "libs"
		private const val MAX_REMEMBERED_REJECTIONS = 20
		private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
	}
}

@Serializable
data class ParsersState(
	/** The build currently loaded, or null to mean the bundled one. */
	val activeVersion: String? = null,
	/** What [ParsersInstallation.rollBack] returns to. */
	val lastKnownGoodVersion: String? = null,
	/**
	 * The user has fixed the version deliberately. Update checks still report what is available
	 * but must not act, or a pin is not a pin.
	 */
	val pinnedVersion: String? = null,
	/** Builds that failed the gate, keyed by version, valued by why. Never retried. */
	val rejected: Map<String, String> = emptyMap(),
	/** Epoch millis of the last completed update check. */
	val lastCheckedAt: Long = 0L,
) {
	fun isRejected(version: String) = version in rejected

	/** Whether an update check is allowed to act on what it finds. */
	val isPinned: Boolean get() = pinnedVersion != null
}
