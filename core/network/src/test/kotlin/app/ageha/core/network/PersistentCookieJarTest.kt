package app.ageha.core.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

class PersistentCookieJarTest {

	private val url = "https://example.org/manga".toHttpUrl()

	private fun persistentCookie(name: String, value: String) = Cookie.Builder()
		.name(name)
		.value(value)
		.domain("example.org")
		.path("/")
		.expiresAt(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30))
		.build()

	@Test
	@DisplayName("cookies survive a restart")
	fun roundTrip(@TempDir dir: File) {
		val file = File(dir, "cookies.json")
		PersistentCookieJar(file).apply {
			saveFromResponse(url, listOf(persistentCookie("cf_clearance", "abc123")))
			persist()
		}

		// A second jar over the same file is what a restart looks like. This is the whole reason
		// the jar is persistent: a Cloudflare clearance cookie that does not survive a restart
		// means solving a challenge on every launch.
		val reopened = PersistentCookieJar(file)
		val loaded = reopened.loadForRequest(url)
		assertEquals(1, loaded.size)
		assertEquals("cf_clearance", loaded.single().name)
		assertEquals("abc123", loaded.single().value)
	}

	@Test
	@DisplayName("session cookies are kept in memory but never written to disk")
	fun sessionCookiesAreNotPersisted(@TempDir dir: File) {
		val file = File(dir, "cookies.json")
		val jar = PersistentCookieJar(file)
		val session = Cookie.Builder()
			.name("PHPSESSID")
			.value("temporary")
			.domain("example.org")
			.path("/")
			.build()
		jar.saveFromResponse(url, listOf(session))

		assertEquals(1, jar.loadForRequest(url).size, "session cookie should work in this run")
		assertTrue(
			PersistentCookieJar(file).loadForRequest(url).isEmpty(),
			"session cookie should not outlive the process",
		)
	}

	@Test
	@DisplayName("expired cookies are dropped on read")
	fun expiredCookiesAreDropped(@TempDir dir: File) {
		val jar = PersistentCookieJar(File(dir, "cookies.json"))
		val expired = Cookie.Builder()
			.name("stale")
			.value("x")
			.domain("example.org")
			.path("/")
			.expiresAt(System.currentTimeMillis() - 1000)
			.build()
		jar.saveFromResponse(url, listOf(expired))

		assertTrue(jar.loadForRequest(url).isEmpty())
	}

	@Test
	@DisplayName("cookies are not leaked to other hosts")
	fun cookiesAreScopedToTheirHost(@TempDir dir: File) {
		val jar = PersistentCookieJar(File(dir, "cookies.json"))
		jar.saveFromResponse(url, listOf(persistentCookie("session", "secret")))

		assertTrue(jar.loadForRequest("https://elsewhere.test/".toHttpUrl()).isEmpty())
	}

	@Test
	@DisplayName("a re-issued cookie replaces the old one instead of accumulating")
	fun reissuedCookieReplaces(@TempDir dir: File) {
		val jar = PersistentCookieJar(File(dir, "cookies.json"))
		jar.saveFromResponse(url, listOf(persistentCookie("token", "old")))
		jar.saveFromResponse(url, listOf(persistentCookie("token", "new")))

		val loaded = jar.loadForRequest(url)
		assertEquals(1, loaded.size)
		assertEquals("new", loaded.single().value)
	}

	@Test
	@DisplayName("a corrupt jar file does not stop the app from starting")
	fun corruptFileIsSurvivable(@TempDir dir: File) {
		val file = File(dir, "cookies.json")
		file.writeText("{ this is not json")

		val jar = PersistentCookieJar(file)
		assertTrue(jar.loadForRequest(url).isEmpty())

		// and it still works afterwards
		jar.saveFromResponse(url, listOf(persistentCookie("fresh", "ok")))
		assertEquals(1, jar.loadForRequest(url).size)
	}
}
