package app.ageha.core.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What counts as a link when someone pastes into the Add site dialog.
 *
 * The rules are small and each one is here because getting it wrong shows up as the dialog telling
 * someone their perfectly good link is not a link -- or, worse, handing something that is not a
 * web address to a resolver that will try to fetch it.
 */
class SiteLinksTest {

	@Test
	@DisplayName("a full link passes through untouched")
	fun fullLink() {
		assertEquals("https://comix.to/", SiteLinks.normalise("https://comix.to/"))
		assertEquals("http://example.com/manga/x", SiteLinks.normalise("http://example.com/manga/x"))
	}

	@Test
	@DisplayName("a bare host, as copied from an address bar, gets https")
	fun bareHost() {
		assertEquals("https://comix.to", SiteLinks.normalise("comix.to"))
		assertEquals("https://comix.to/title/abc", SiteLinks.normalise("comix.to/title/abc"))
	}

	@Test
	@DisplayName("surrounding whitespace from a paste is forgiven")
	fun trimsPaste() {
		assertEquals("https://comix.to", SiteLinks.normalise("  comix.to\n"))
	}

	@Test
	@DisplayName("a port is not mistaken for a scheme")
	fun portIsNotScheme() {
		// `comix.to:443` has the shape `word:` that a scheme check looks for. Refusing it would
		// be the dialog rejecting a working address on a technicality.
		assertEquals("https://comix.to:443/x", SiteLinks.normalise("comix.to:443/x"))
	}

	@Test
	@DisplayName("prose, blanks and hosts without a dot are not links")
	fun notLinks() {
		assertNull(SiteLinks.normalise("not a link"))
		assertNull(SiteLinks.normalise(""))
		assertNull(SiteLinks.normalise("   "))
		assertNull(SiteLinks.normalise("localhost"))
	}

	@Test
	@DisplayName("schemes other than http and https are refused, not rewritten")
	fun otherSchemes() {
		// Bolting https:// onto these would produce nonsense URLs and send them to a resolver.
		assertNull(SiteLinks.normalise("javascript:alert(1)"))
		assertNull(SiteLinks.normalise("file:///C:/Users/me/manga.cbz"))
		assertNull(SiteLinks.normalise("ftp://example.com"))
	}

	@Test
	@DisplayName("the host shown back to the user drops www")
	fun hostForCopy() {
		assertEquals("comix.to", SiteLinks.hostOf("https://www.comix.to/title/abc"))
		assertEquals("comix.to", SiteLinks.hostOf("https://comix.to"))
	}
}
