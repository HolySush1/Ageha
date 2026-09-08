package app.ageha.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * Checking GitHub for a newer Ageha.
 *
 * The version comparison gets its own tests because it is the part that fails silently and
 * embarrassingly: a lexicographic comparison looks correct for months and then tells everyone on
 * 0.10 that they are ahead of 0.9.
 */
class AppUpdateCheckerTest {

	private lateinit var server: HttpServer
	private lateinit var http: OkHttpClient
	private var response: Pair<Int, String> = 200 to ""

	@BeforeEach
	fun open() {
		server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			val (status, body) = response
			val bytes = body.toByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			if (bytes.isEmpty()) {
				exchange.sendResponseHeaders(status, -1)
			} else {
				exchange.sendResponseHeaders(status, bytes.size.toLong())
				exchange.responseBody.use { it.write(bytes) }
			}
			exchange.close()
		}
		server.start()
		http = OkHttpClient()
	}

	@AfterEach
	fun close() {
		server.stop(0)
		http.dispatcher.executorService.shutdown()
		http.connectionPool.evictAll()
	}

	private fun checker(current: String) = AppUpdateChecker(
		httpClient = http,
		repo = "HolySush1/Ageha",
		currentVersion = current,
		apiBase = "http://127.0.0.1:" + server.address.port,
	)

	@Test
	@DisplayName("a newer release is offered, with the url to get it")
	fun newerRelease() = runBlocking {
		response = 200 to """{"tag_name":"v0.2.0","html_url":"https://example.org/releases/v0.2.0"}"""

		val outcome = checker("0.1.0").check()

		assertEquals(AppUpdateOutcome.Available("0.2.0", "https://example.org/releases/v0.2.0"), outcome)
	}

	@Test
	@DisplayName("the same version is not offered as an update")
	fun sameVersion() = runBlocking {
		response = 200 to """{"tag_name":"v0.1.0","html_url":"https://example.org/x"}"""

		assertTrue(checker("0.1.0").check() is AppUpdateOutcome.UpToDate)
	}

	@Test
	@DisplayName("running ahead of the newest release is not an update")
	fun runningAhead() = runBlocking {
		// A developer build, or someone who installed a pre-release. Telling them to "upgrade"
		// backwards is worse than saying nothing.
		response = 200 to """{"tag_name":"v0.1.0","html_url":"https://example.org/x"}"""

		assertTrue(checker("0.3.0").check() is AppUpdateOutcome.UpToDate)
	}

	@Test
	@DisplayName("a repository with no releases yet is not an error the user sees")
	fun noReleasesYet() = runBlocking {
		// The state this project is in right now: GitHub answers 404 for `releases/latest`.
		response = 404 to """{"message":"Not Found"}"""

		val outcome = checker("0.1.0").check()

		assertTrue(outcome is AppUpdateOutcome.Failed, outcome.describe())
		assertTrue((outcome as AppUpdateOutcome.Failed).reason.contains("404"), outcome.reason)
	}

	@Test
	@DisplayName("an unreachable GitHub is a failure value, not a thrown exception")
	fun unreachable() = runBlocking {
		server.stop(0)

		assertTrue(checker("0.1.0").check() is AppUpdateOutcome.Failed)
	}

	@Test
	@DisplayName("a reply that is not a release is a failure value too")
	fun malformed() = runBlocking {
		response = 200 to "<html>rate limited</html>"

		assertTrue(checker("0.1.0").check() is AppUpdateOutcome.Failed)
	}

	@Test
	@DisplayName("versions compare numerically, not as strings")
	fun numericComparison() {
		// The whole reason this function exists. Lexicographically "0.10.0" < "0.9.0", which would
		// tell everyone on 0.9 to upgrade to 0.10 *and* everyone on 0.10 that they were ahead.
		assertTrue(AppUpdateChecker.isNewer("0.10.0", "0.9.0"))
		assertFalse(AppUpdateChecker.isNewer("0.9.0", "0.10.0"))
		assertTrue(AppUpdateChecker.isNewer("1.0.0", "0.99.99"))
		assertTrue(AppUpdateChecker.isNewer("0.2.0", "0.1.9"))
	}

	@Test
	@DisplayName("a missing version part counts as zero")
	fun missingParts() {
		assertFalse(AppUpdateChecker.isNewer("1.2", "1.2.0"))
		assertFalse(AppUpdateChecker.isNewer("1.2.0", "1.2"))
		assertTrue(AppUpdateChecker.isNewer("1.2.1", "1.2"))
	}

	@Test
	@DisplayName("a pre-release suffix is not a version part, and junk offers nothing")
	fun suffixesAndJunk() {
		// The running build is `0.1.0-SNAPSHOT` outside a package, and it must not be treated as
		// older than the 0.1.0 release it was built from.
		assertFalse(AppUpdateChecker.isNewer("0.1.0", "0.1.0-SNAPSHOT"))
		assertTrue(AppUpdateChecker.isNewer("0.2.0", "0.1.0-SNAPSHOT"))
		// Unparseable offers nothing rather than guessing: failing to mention an update beats
		// nagging about one that does not exist.
		assertFalse(AppUpdateChecker.isNewer("nightly", "0.1.0"))
		assertFalse(AppUpdateChecker.isNewer("0.2.0", "nightly"))
	}
}
