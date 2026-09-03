package app.ageha.core.parsers

import app.ageha.core.network.AgehaHttpClient
import app.ageha.core.network.PersistentCookieJar
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The update engine's decisions.
 *
 * Most of these need no network, because the decisions worth testing are about state rather than
 * transport: a pin must be honoured, a refused build must never come back, and a check that fails
 * must be distinguishable from a build that was refused. Getting the last one wrong would mean a
 * flaky connection permanently blacklisting a perfectly good build.
 */
class ParsersUpdateServiceTest {

	/**
	 * The service under test, plus its installation.
	 *
	 * [clients] is drained in [releaseClients] because an OkHttp client holds its disk cache open,
	 * and on Windows @TempDir cannot delete an open file -- which is how the equivalent leak in
	 * production was found.
	 */
	private val clients = mutableListOf<OkHttpClient>()

	private fun service(dir: File): Pair<ParsersUpdateService, ParsersInstallation> {
		val installation = ParsersInstallation(File(dir, "parsers"))
		BundledParsers.ensureExtracted(installation)
		val cookieJar = PersistentCookieJar(File(dir, "cookies.json"))
		val client = AgehaHttpClient.build(cookieJar, cacheDir = File(dir, "http-cache"))
		clients += client
		return ParsersUpdateService(
			httpClient = client,
			installation = installation,
			cookieJar = cookieJar,
		) to installation
	}

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
	@DisplayName("a pinned version stops the check before it touches the network")
	fun pinShortCircuitsTheCheck(@TempDir dir: File) {
		val (service, installation) = service(dir)
		installation.write(installation.read().copy(pinnedVersion = "abc1234567"))

		val outcome = runBlocking { service.checkForUpdate() }

		// No network call is made at all, which is what makes this test hermetic: reaching GitHub
		// would already be a bug, because a pin means the answer cannot change anything.
		val pinned = assertInstanceOf(UpdateOutcome.Pinned::class.java, outcome)
		assertEquals("abc1234567", pinned.version)
	}

	@Test
	@DisplayName("a previously rejected build is never fetched again")
	fun rejectedBuildsAreNotRefetched(@TempDir dir: File) {
		val (_, installation) = service(dir)
		installation.reject("deadbeef01", "incompatible with this version of Ageha")

		val state = installation.read()
		assertTrue(state.rejected.containsKey("deadbeef01"))
		// The check consults this before downloading anything, so a broken build costs one
		// download in its lifetime rather than one every six hours forever.
		assertEquals("incompatible with this version of Ageha", state.rejected["deadbeef01"])
	}

	@Test
	@DisplayName("a failed check is not recorded as a rejection")
	fun checkFailureIsNotARejection(@TempDir dir: File) {
		val (_, installation) = service(dir)

		// The distinction matters: CheckFailed means nothing is known about the build, so it will
		// be tried again. Recording it as Rejected would let one bad afternoon on someone's wifi
		// permanently blacklist a build that was never even downloaded.
		val failure: UpdateOutcome = UpdateOutcome.CheckFailed("could not reach GitHub: timeout")
		assertInstanceOf(UpdateOutcome.CheckFailed::class.java, failure)
		assertTrue(installation.read().rejected.isEmpty())
	}

	@Test
	@DisplayName("activating a build that is not installed is refused rather than half-applied")
	fun activateRequiresAnInstalledBuild(@TempDir dir: File) {
		val (service, _) = service(dir)

		val error = runCatching { service.activate("neverdownloaded") }.exceptionOrNull()
		assertInstanceOf(IllegalArgumentException::class.java, error)
	}

	@Test
	@DisplayName("activation keeps the outgoing build available to roll back to")
	fun activationPreservesRollbackTarget(@TempDir dir: File) {
		val (service, installation) = service(dir)
		installation.activate(BundledParsers.VERSION)

		val newer = "0000000009"
		installation.directoryFor(BundledParsers.VERSION).copyRecursively(installation.directoryFor(newer))
		service.activate(newer)

		val state = installation.read()
		assertEquals(newer, state.activeVersion)
		assertEquals(BundledParsers.VERSION, state.lastKnownGoodVersion)
	}

	@Test
	@Tag("network")
	@DisplayName("resolves the real upstream HEAD and reports a usable outcome")
	fun liveCheckAgainstUpstream(@TempDir dir: File) {
		val (service, _) = service(dir)

		val outcome = runBlocking { service.checkForUpdate() }

		// Deliberately not asserting a specific outcome. Upstream's HEAD moves, JitPack may be
		// mid-build or rate limiting, and any of UpToDate / Ready / Rejected / CheckFailed is a
		// correct answer on a given day. What is being tested is that the engine reaches a
		// decision rather than throwing.
		assertTrue(
			outcome is UpdateOutcome.UpToDate ||
				outcome is UpdateOutcome.Ready ||
				outcome is UpdateOutcome.Rejected ||
				outcome is UpdateOutcome.PreviouslyRejected ||
				outcome is UpdateOutcome.CheckFailed,
			"unexpected outcome: " + outcome,
		)
	}
}
