package app.ageha.core.parsers

import app.ageha.core.network.AgehaHttpClient
import app.ageha.core.network.PersistentCookieJar
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The gate, and the whole path it takes when it says no.
 *
 * Rejection is a designed outcome, not an error path. Layer 1 cannot promise that source updates
 * never need an app release -- evaluateJs gaining a third parameter upstream is proof the host
 * contract does move -- so what matters is that a bad build costs the user nothing. These tests
 * assert the promise end to end: the build is refused, the previous one keeps serving sources,
 * the version is remembered so it is never fetched again, and the message says so.
 */
class CompatibilityGateTest {

	private fun installBundled(dir: File): Pair<ParsersInstallation, String> {
		val installation = ParsersInstallation(File(dir, "parsers"))
		val version = BundledParsers.ensureExtracted(installation)
		return installation to version
	}

	private val clients = mutableListOf<OkHttpClient>()

	private fun cookieJar(dir: File) = PersistentCookieJar(File(dir, "cookies.json"))

	private fun httpClient(dir: File): OkHttpClient =
		AgehaHttpClient.build(cookieJar(dir), cacheDir = File(dir, "http-cache")).also { clients += it }

	@AfterEach
	fun releaseClients() {
		clients.forEach { client ->
			runCatching { client.dispatcher.executorService.shutdown() }
			runCatching { client.connectionPool.evictAll() }
			runCatching { client.cache?.close() }
		}
		clients.clear()
	}

	@Test
	@DisplayName("the bundled build passes the gate")
	fun bundledBuildIsAccepted(@TempDir dir: File) {
		val (installation, version) = installBundled(dir)

		val verdict = CompatibilityGate.evaluate(
			parsersJar = installation.parsersJarFor(version),
			bridgeJar = installation.bridgeJarFor(version),
			extraJars = installation.libraryJarsFor(version),
			version = version,
			httpClient = httpClient(dir),
			cookieJar = cookieJar(dir),
		)

		val accepted = assertInstanceOf(GateVerdict.Accepted::class.java, verdict)
		assertTrue(accepted.sourceCount > 1000, "expected a full catalogue, got " + accepted.sourceCount)
	}

	@Test
	@DisplayName("a build whose host contract moved is rejected, not activated")
	fun incompatibleBuildIsRejected(@TempDir dir: File) {
		val (installation, version) = installBundled(dir)
		// Ahead of the genuine jar, so it shadows the real MangaLoaderContext -- the shape an
		// upstream change to the host contract actually has.
		val shadow = ParsersJarFixtures.incompatibleLoaderContextJar(dir)

		val verdict = CompatibilityGate.evaluate(
			parsersJar = shadow,
			bridgeJar = installation.bridgeJarFor(version),
			extraJars = listOf(installation.parsersJarFor(version)) + installation.libraryJarsFor(version),
			version = "deadbeef01",
			httpClient = httpClient(dir),
			cookieJar = cookieJar(dir),
		)

		val rejected = assertInstanceOf(GateVerdict.Rejected::class.java, verdict)
		assertEquals("deadbeef01", rejected.version)
		// Asserting the reason, not merely that it was rejected. A test that only checks the
		// verdict passes just as happily when the jar path is wrong, which would mean this was
		// never exercising the incompatibility at all.
		assertTrue(
			rejected.reason.contains("incompatible with this version of Ageha"),
			"expected a linkage failure against the changed host contract, got: " + rejected.reason,
		)
	}

	@Test
	@DisplayName("a build with no bridge class is rejected rather than crashing the app")
	fun jarWithoutBridgeIsRejected(@TempDir dir: File) {
		val (installation, version) = installBundled(dir)

		val verdict = CompatibilityGate.evaluate(
			parsersJar = installation.parsersJarFor(version),
			bridgeJar = ParsersJarFixtures.emptyJar(dir),
			extraJars = installation.libraryJarsFor(version),
			version = "nobridge01",
			httpClient = httpClient(dir),
			cookieJar = cookieJar(dir),
		)

		assertInstanceOf(GateVerdict.Rejected::class.java, verdict)
	}

	@Test
	@DisplayName("a truncated download is rejected rather than throwing out of the gate")
	fun corruptJarIsRejected(@TempDir dir: File) {
		val (installation, version) = installBundled(dir)

		val verdict = CompatibilityGate.evaluate(
			parsersJar = ParsersJarFixtures.corruptJar(dir),
			bridgeJar = installation.bridgeJarFor(version),
			version = "corrupt001",
			httpClient = httpClient(dir),
			cookieJar = cookieJar(dir),
		)

		assertInstanceOf(GateVerdict.Rejected::class.java, verdict)
	}

	@Test
	@DisplayName("the rejection message tells the user their sources still work")
	fun rejectionMessageIsAboutTheUser() {
		val rejected = GateVerdict.Rejected("abc1234567", "incompatible with this version of Ageha")
		val message = rejected.userMessage()

		assertTrue(message.contains("abc1234567"), "the message must name the build")
		assertTrue(
			message.contains("newer version of Ageha"),
			"the user needs to know the fix is an app update, not something they did",
		)
		assertTrue(
			message.contains("still working"),
			"the user needs to know nothing has broken for them",
		)
	}

	@Test
	@DisplayName("a rejected build is remembered, so a scheduled check never fetches it again")
	fun rejectionIsRemembered(@TempDir dir: File) {
		val (installation, _) = installBundled(dir)

		installation.reject("deadbeef01", "incompatible with this version of Ageha")

		val state = installation.read()
		assertTrue(state.isRejected("deadbeef01"))
		assertEquals("incompatible with this version of Ageha", state.rejected["deadbeef01"])
		assertFalse(
			installation.directoryFor("deadbeef01").exists(),
			"a rejected build's files are dead weight and should not be kept",
		)

		// Survives a restart: a six-hourly check must not re-download it tomorrow either.
		assertTrue(ParsersInstallation(File(dir, "parsers")).read().isRejected("deadbeef01"))
	}

	@Test
	@DisplayName("after a rejection the previously active build is still the one serving sources")
	fun rejectionLeavesTheRunningBuildAlone(@TempDir dir: File) {
		val (installation, bundled) = installBundled(dir)
		installation.activate(bundled)

		installation.reject("deadbeef01", "incompatible")

		assertEquals(bundled, installation.read().activeVersion)
		assertTrue(installation.isInstalled(bundled), "the working build must still be on disk")
	}

	@Test
	@DisplayName("rollback returns to the last build that worked")
	fun rollbackRestoresLastKnownGood(@TempDir dir: File) {
		val (installation, bundled) = installBundled(dir)
		installation.activate(bundled)

		// A second build that installs cleanly but turns out to misbehave in use.
		val newer = "0000000002"
		installation.directoryFor(bundled).copyRecursively(installation.directoryFor(newer))
		installation.activate(newer)
		assertEquals(newer, installation.read().activeVersion)

		assertEquals(bundled, installation.rollBack())
		assertEquals(bundled, installation.read().activeVersion)
	}

	@Test
	@DisplayName("rollback with nothing to roll back to reports that rather than half-doing it")
	fun rollbackWithoutFallbackIsNull(@TempDir dir: File) {
		val (installation, bundled) = installBundled(dir)
		installation.activate(bundled)

		assertNull(installation.rollBack())
		assertEquals(bundled, installation.read().activeVersion)
	}

	@Test
	@DisplayName("a pin is honoured, so an update check cannot quietly move off it")
	fun pinIsRespected(@TempDir dir: File) {
		val (installation, bundled) = installBundled(dir)
		installation.write(installation.read().copy(pinnedVersion = bundled))

		assertTrue(installation.read().isPinned)
		assertEquals(bundled, installation.read().pinnedVersion)
	}

	@Test
	@DisplayName("unreadable state degrades to the bundled build rather than refusing to start")
	fun corruptStateIsSurvivable(@TempDir dir: File) {
		val (installation, _) = installBundled(dir)
		File(File(dir, "parsers"), "state.json").writeText("{ not json at all")

		val state = installation.read()
		assertNotNull(state)
		assertNull(state.activeVersion, "an unreadable state means 'use the bundled build'")
	}

	@Test
	@DisplayName("gating a candidate while a build is live does not disturb the live one")
	fun gateDoesNotDisturbTheLiveStack(@TempDir dir: File) {
		// The scenario the single-owner rule exists for. The gate constructs a second context
		// while the live one is serving, so before this was fixed both built their own OkHttp
		// Cache over the same directory -- which OkHttp's own documentation calls an error, and
		// which classloader isolation does nothing to prevent, because the directory is shared.
		val stack = Ageha.createSourceStack(
			cookieFile = File(dir, "live-cookies.json"),
			parsersDir = File(dir, "live-parsers"),
		)
		try {
			val before = stack.registry.availableSources().size
			assertTrue(before > 1000)

			val installation = stack.installation
			val version = BundledParsers.VERSION
			repeat(3) {
				val verdict = CompatibilityGate.evaluate(
					parsersJar = installation.parsersJarFor(version),
					bridgeJar = installation.bridgeJarFor(version),
					extraJars = installation.libraryJarsFor(version),
					version = version,
					httpClient = stack.httpClient,
					cookieJar = cookieJar(dir),
				)
				assertInstanceOf(GateVerdict.Accepted::class.java, verdict)
			}

			assertEquals(
				before,
				stack.registry.availableSources().size,
				"the live build must be unaffected by gating a candidate",
			)
			assertNotNull(stack.httpClient.cache, "the shared cache must survive the gate runs")
		} finally {
			runBlocking { stack.close() }
		}
	}
}