package app.ageha.core.parsers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Checksum verification of an installed build.
 *
 * The jar's own checksum is not enough, because a build is not one file. Resolving the POM at
 * runtime means Ageha fetches a set of jars whose membership it did not know in advance, and every
 * one of them loads into the same classloader with the same privileges. These tests cover the
 * whole set, including the case that is easy to forget: a file nobody recorded.
 */
class ParsersLockTest {

	private fun bundled(dir: File): Pair<ParsersInstallation, String> {
		val installation = ParsersInstallation(File(dir, "parsers"))
		return installation to BundledParsers.ensureExtracted(installation)
	}

	@Test
	@DisplayName("a freshly extracted build verifies, and covers every file in it")
	fun freshBuildVerifies(@TempDir dir: File) {
		val (installation, version) = bundled(dir)

		assertInstanceOf(LockVerification.Verified::class.java, installation.verify(version))

		val lock = ParsersLock.read(installation.directoryFor(version))!!
		assertEquals(version, lock.version)
		assertTrue(lock.files.containsKey("kotatsu-parsers.jar"))
		assertTrue(lock.files.containsKey("ageha-bridge.jar"))
		assertTrue(
			lock.files.keys.any { it.startsWith("libs/") },
			"the parsers library's own dependencies must be covered too; they load with the " +
				"same privileges as the parsers jar",
		)
	}

	@Test
	@DisplayName("a modified jar fails verification")
	fun modifiedJarIsDetected(@TempDir dir: File) {
		val (installation, version) = bundled(dir)
		installation.parsersJarFor(version).appendBytes(byteArrayOf(0))

		val result = assertInstanceOf(LockVerification.Failed::class.java, installation.verify(version))
		assertTrue(result.reason.contains("checksum mismatch"), result.reason)
	}

	@Test
	@DisplayName("a modified dependency fails verification, not just a modified parsers jar")
	fun modifiedDependencyIsDetected(@TempDir dir: File) {
		val (installation, version) = bundled(dir)
		val dependency = installation.libraryJarsFor(version).first()
		dependency.appendBytes(byteArrayOf(0))

		val result = assertInstanceOf(LockVerification.Failed::class.java, installation.verify(version))
		assertTrue(result.reason.contains(dependency.name), result.reason)
	}

	@Test
	@DisplayName("a missing file fails verification")
	fun missingFileIsDetected(@TempDir dir: File) {
		val (installation, version) = bundled(dir)
		assertTrue(installation.libraryJarsFor(version).first().delete())

		val result = assertInstanceOf(LockVerification.Failed::class.java, installation.verify(version))
		assertTrue(result.reason.contains("missing"), result.reason)
	}

	@Test
	@DisplayName("an unexpected extra jar fails verification")
	fun unexpectedFileIsDetected(@TempDir dir: File) {
		val (installation, version) = bundled(dir)
		// Not harmless: the classloader is handed everything in libs/, so a file nobody recorded
		// is code nobody vouched for, running with the same privileges as the rest.
		File(installation.directoryFor(version), "libs/smuggled.jar").writeText("payload")

		val result = assertInstanceOf(LockVerification.Failed::class.java, installation.verify(version))
		assertTrue(result.reason.contains("unexpected"), result.reason)
	}

	@Test
	@DisplayName("a build with no lock at all is refused")
	fun unlockedBuildIsRefused(@TempDir dir: File) {
		val (installation, version) = bundled(dir)
		assertTrue(File(installation.directoryFor(version), ParsersLock.FILE_NAME).delete())

		assertInstanceOf(LockVerification.Failed::class.java, installation.verify(version))
	}

	@Test
	@DisplayName("a corrupted bundled build is re-extracted rather than loaded")
	fun corruptedBundledBuildIsRepaired(@TempDir dir: File) {
		val (installation, version) = bundled(dir)
		installation.parsersJarFor(version).appendBytes(byteArrayOf(0))
		assertInstanceOf(LockVerification.Failed::class.java, installation.verify(version))

		// The bundled build is what everything else falls back to, so a corrupted copy is the one
		// failure with no recovery path. Re-extracting costs a moment, once.
		BundledParsers.ensureExtracted(installation)

		assertInstanceOf(LockVerification.Verified::class.java, installation.verify(version))
		assertEquals(version, installation.read().activeVersion ?: version)
	}
}
