package app.ageha.core.jvmcontext

import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Image requests name the source as their Referer, as the Android app sends them.
 *
 * ComicK is the case that found this. Its pages are served from `cdn1.comicknew.pictures`, whose
 * Cloudflare rules hand out an image only to a Referer of `https://comick.live/`. No Referer and the
 * CDN's own origin both get the same 403 block page -- and the second is what Ageha was sending,
 * because the network layer filled the gap from the request's host.
 */
class ImageHeadersTest {

	private val parserHeaders = Headers.headersOf("User-Agent", "Mozilla/5.0 test")

	@Test
	@DisplayName("a parser that sets no Referer gets its source's domain, not the image host")
	fun refererIsTheSource() {
		assertEquals("https://comick.live/", imageHeaders(parserHeaders, "comick.live").header("Referer"))
	}

	@Test
	@DisplayName("a Referer the parser set itself is kept, and not doubled")
	fun parserRefererWins() {
		val own = Headers.headersOf("User-Agent", "Mozilla/5.0 test", "Referer", "https://cdn.example/reader/")
		assertEquals("https://cdn.example/reader/", imageHeaders(own, "example.org").header("Referer"))
	}

	@Test
	@DisplayName("the parser's own headers still go out")
	fun parserHeadersKept() {
		assertEquals("Mozilla/5.0 test", imageHeaders(parserHeaders, "comick.live").header("User-Agent"))
	}

	@Test
	@DisplayName("an internationalised domain is sent as ASCII, which is all a header can carry")
	fun idnDomainIsAscii() {
		assertEquals("https://xn--bcher-kva.de/", imageHeaders(parserHeaders, "bücher.de").header("Referer"))
	}

	@Test
	@DisplayName("a blank domain adds no Referer rather than a malformed one")
	fun blankDomainAddsNothing() {
		assertNull(imageHeaders(parserHeaders, "").header("Referer"))
	}

	/** Header names are case-insensitive; a name sent twice fails the test rather than picking one. */
	private fun Map<String, String>.header(name: String): String? {
		val matches = filterKeys { it.equals(name, ignoreCase = true) }.values
		check(matches.size <= 1) { "$name sent ${matches.size} times" }
		return matches.firstOrNull()
	}
}
