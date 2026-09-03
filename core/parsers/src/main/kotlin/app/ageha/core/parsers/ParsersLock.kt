package app.ageha.core.parsers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * A checksum manifest for one installed parsers build, written when it is staged and verified
 * before it is ever loaded.
 *
 * ## Why the jar's own checksum is not enough
 *
 * A build is not one file. Resolving the POM at runtime -- which is how `androidx.annotation` was
 * discovered, a dependency no documentation mentions -- means Ageha fetches a set of jars whose
 * membership it did not know in advance. Verifying only the parsers jar would leave every
 * transitive dependency unverified, and those load into the same classloader with the same
 * privileges. A tampered `json-20240303.jar` runs exactly as freely as a tampered parsers jar.
 *
 * So the lock records every file in the build, and verification is all-or-nothing: a build with a
 * file that does not match, a file missing, or a file present that the lock does not mention, is
 * not loaded at all.
 *
 * ## What this does and does not protect against
 *
 * It protects against a partial or corrupted download, a half-applied update, disk corruption, and
 * local tampering after installation. It is **not** a substitute for trusting the source: the
 * checksums are recorded from what was downloaded, so a compromised upstream would be recorded
 * faithfully. Where the remote repository publishes its own checksum, [ParsersUpdateService]
 * compares against that at download time, which is the point where provenance can be checked at
 * all.
 */
@Serializable
data class ParsersLock(
	val version: String,
	/** Relative path within the build directory, to lowercase hex SHA-256. */
	val files: Map<String, String>,
	val createdAt: Long,
) {

	companion object {

		const val FILE_NAME = "lock.json"

		private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

		/** Record every file under [buildDir], except the lock itself. */
		fun create(version: String, buildDir: File, now: Long = System.currentTimeMillis()): ParsersLock {
			val files = buildDir.walkTopDown()
				.filter { it.isFile && it.name != FILE_NAME }
				.associate { it.relativeTo(buildDir).invariantSeparatorsPath to sha256(it) }
			return ParsersLock(version, files, now)
		}

		fun write(buildDir: File, lock: ParsersLock) {
			File(buildDir, FILE_NAME).writeText(json.encodeToString(lock))
		}

		fun read(buildDir: File): ParsersLock? = runCatching {
			val file = File(buildDir, FILE_NAME)
			if (!file.isFile) return null
			json.decodeFromString<ParsersLock>(file.readText())
		}.getOrNull()

		fun sha256(file: File): String {
			val digest = MessageDigest.getInstance("SHA-256")
			file.inputStream().use { stream ->
				val buffer = ByteArray(1 shl 16)
				while (true) {
					val read = stream.read(buffer)
					if (read <= 0) break
					digest.update(buffer, 0, read)
				}
			}
			return digest.digest().joinToString("") { "%02x".format(it) }
		}
	}
}

/** Why a build did not verify. Every case means "do not load this". */
sealed interface LockVerification {

	data object Verified : LockVerification

	data class Failed(val reason: String) : LockVerification

	companion object {

		/**
		 * Check every file in [buildDir] against [lock].
		 *
		 * Unexpected files fail verification as loudly as modified ones. An extra jar in the
		 * directory is not harmless: the classloader is handed everything in `libs/`, so a file
		 * nobody recorded is a file nobody vouched for.
		 */
		fun verify(buildDir: File, lock: ParsersLock?): LockVerification {
			if (lock == null) {
				return Failed("no lock file; this build was not staged by a version of Ageha that writes one")
			}

			val present = buildDir.walkTopDown()
				.filter { it.isFile && it.name != ParsersLock.FILE_NAME }
				.associateBy { it.relativeTo(buildDir).invariantSeparatorsPath }

			val missing = lock.files.keys - present.keys
			if (missing.isNotEmpty()) {
				return Failed("missing " + missing.sorted().joinToString(", "))
			}

			val unexpected = present.keys - lock.files.keys
			if (unexpected.isNotEmpty()) {
				return Failed("unexpected " + unexpected.sorted().joinToString(", "))
			}

			for ((path, expected) in lock.files) {
				val actual = runCatching { ParsersLock.sha256(present.getValue(path)) }
					.getOrElse { return Failed("could not read " + path) }
				if (actual != expected) {
					return Failed("checksum mismatch for " + path)
				}
			}
			return Verified
		}
	}
}
