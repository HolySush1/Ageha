package app.ageha.core.parsers

import kotlinx.coroutines.runBlocking
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

	/**
	 * A downloaded build that looks exactly like one accepted under a previous release: installed,
	 * locked, active -- and carrying that release's bridge rather than this one's.
	 */
	private fun downloadedWithStaleBridge(installation: ParsersInstallation, bundled: String): String {
		val downloaded = "downloaded-test"
		installation.directoryFor(bundled).copyRecursively(installation.directoryFor(downloaded))
		val bridge = installation.bridgeJarFor(downloaded)
		bridge.writeBytes(bridge.readBytes() + "the previous release's bridge".toByteArray())
		installation.writeLock(downloaded)
		installation.activate(downloaded)
		return downloaded
	}

	@Test
	@DisplayName("a downloaded build's stale bridge is replaced at launch, and the build stays active")
	fun downloadedBuildGetsCurrentBridge(@TempDir dir: File) {
		val installation = installationIn(dir)
		val bundled = BundledParsers.ensureExtracted(installation)
		val downloaded = downloadedWithStaleBridge(installation, bundled)

		val stack = Ageha.createSourceStack(
			parsersDir = File(dir, "parsers"),
			cookieFile = File(dir, "cookies.json"),
		)
		try {
			assertEquals(
				downloaded,
				stack.registry.parsersVersion,
				"a build that passes the gate with the new bridge must stay the one in use",
			)
			assertArrayEquals(
				installation.bridgeJarFor(bundled).readBytes(),
				installation.bridgeJarFor(downloaded).readBytes(),
				"the downloaded build must now carry this release's bridge",
			)
			assertInstanceOf(
				LockVerification.Verified::class.java,
				installation.verify(downloaded),
				"the refreshed build must be relocked, or the next launch steps over it",
			)
		} finally {
			runBlocking { stack.close() }
		}
	}

	@Test
	@DisplayName("a downloaded build the current bridge cannot vouch for is stepped over")
	fun unvouchedDownloadedBuildFallsBack(@TempDir dir: File) {
		val installation = installationIn(dir)
		val bundled = BundledParsers.ensureExtracted(installation)
		val downloaded = downloadedWithStaleBridge(installation, bundled)
		// A parsers jar the gate cannot load at all, relocked so that the lock check alone would
		// happily let it through -- which isolates the gate as the thing that has to refuse it.
		installation.parsersJarFor(downloaded).writeText("not a jar")
		installation.writeLock(downloaded)

		val stack = Ageha.createSourceStack(
			parsersDir = File(dir, "parsers"),
			cookieFile = File(dir, "cookies.json"),
		)
		try {
			assertEquals(
				bundled,
				stack.registry.parsersVersion,
				"a build that fails the gate with the new bridge must give way to the bundled one, " +
					"not crash the launch",
			)
		} finally {
			runBlocking { stack.close() }
		}
	}
}
