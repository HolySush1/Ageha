package app.ageha.core.parsers

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The bridge jar on disk must be the one this build of Ageha ships.
 *
 * ## The bug this exists to stop coming back
 *
 * The extracted build is keyed on [BundledParsers.VERSION], which names the *parsers* commit. It
 * does not change when Ageha's own child-side code does -- so upgrading Ageha left the previous
 * release's `ageha-bridge.jar` in the cache directory, permanently. The lock check could not catch
 * it, because an older extraction is entirely self-consistent: it verifies perfectly, it is simply
 * the wrong one.
 *
 * That matters because of where the classloader boundary falls. The bridge implements
 * `MangaLoaderContext` and calls `JsRuntime`, and `app.ageha.core.js` is parent-first -- so the
 * interface comes from the *new* application while the caller came from the old jar. Change a
 * signature on it and the old bridge invokes a method that no longer exists. What the user sees is
 * a `NoSuchMethodError` surfacing as a source that returned something unreadable, which reads like
 * a broken parser and sends you off to look at somebody else's repository.
 */
class BundledBridgeFreshnessTest {

	private fun installationIn(dir: File) = ParsersInstallation(File(dir, "parsers"))

	@Test
	@DisplayName("a stale bridge jar is replaced even though its lock still verifies")
	fun staleBridgeIsReplaced(@TempDir dir: File) {
		val installation = installationIn(dir)
		val version = BundledParsers.ensureExtracted(installation)
		val shipped = installation.bridgeJarFor(version).readBytes()

		// Stand in for the previous release's bridge: different bytes, same name. Then re-lock, so
		// what is on disk is *consistent* -- which is exactly the state an ordinary upgrade leaves
		// behind, and the state the existing verification is blind to.
		installation.bridgeJarFor(version)
			.writeBytes(shipped + "an older build of Ageha".toByteArray())
		val buildDir = installation.directoryFor(version)
		ParsersLock.write(buildDir, ParsersLock.create(version, buildDir))
		assertInstanceOf(
			LockVerification.Verified::class.java,
			installation.verify(version),
			"the tampered state must still verify, or this test is not reproducing the bug",
		)

		BundledParsers.ensureExtracted(installation)

		assertArrayEquals(
			shipped,
			installation.bridgeJarFor(version).readBytes(),
			"the bridge jar must be re-extracted when it differs from the one bundled in this " +
				"build, or an upgraded Ageha keeps calling into its predecessor's code",
		)
	}

	@Test
	@DisplayName("an installation that already matches is left alone")
	fun freshInstallationIsNotReExtracted(@TempDir dir: File) {
		val installation = installationIn(dir)
		val version = BundledParsers.ensureExtracted(installation)

		// The witness is a backdated timestamp rather than a marker file. A file nobody recorded
		// makes the lock fail verification -- which is deliberate, and which would re-extract for
		// a reason that has nothing to do with the check under test. A modification time is
		// invisible to a content-based lock, so it survives everything except an actual rewrite.
		val jar = installation.parsersJarFor(version)
		val backdated = jar.lastModified() - 10_000
		jar.setLastModified(backdated)

		BundledParsers.ensureExtracted(installation)

		// The freshness check must not cost a re-extraction on every launch. Re-extracting is
		// cheap but not free, and a check that always fails is indistinguishable from no check at
		// all the moment somebody times a cold start.
		assertEquals(
			backdated,
			installation.parsersJarFor(version).lastModified(),
			"an installation that already matches the bundled build must not be re-extracted",
		)
	}
}
