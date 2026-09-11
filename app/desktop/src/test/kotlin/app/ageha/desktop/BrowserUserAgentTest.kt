package app.ageha.desktop

import app.ageha.core.network.UserAgents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile

/**
 * Ageha's HTTP user agent claims the Chrome version the bundled Chromium really is.
 *
 * A Cloudflare clearance the browser component earns is bound to the user agent it was earned
 * with, and OkHttp is what presents it afterwards -- so the two have to say the same thing. Making
 * Chromium claim to be an older Chrome instead was tried and fails: the check never clears for an
 * engine that lies about its version, and ComicK's search went from working in two seconds to
 * timing out after ninety.
 *
 * So when a JCEF bump moves Chromium to a new major version, this fails, and the constant moves
 * with it. The version is read from the metadata jcefmaven ships inside its API jar, which names
 * the Chromium it was built against -- no natives need to be present.
 */
class BrowserUserAgentTest {

	@Test
	fun `the HTTP user agent claims the Chromium that is bundled`() {
		// By name, because :app:desktop does not compile against JCEF -- only :core:browser does.
		val cef = Class.forName("org.cef.CefApp")
		val jar = File(cef.protectionDomain.codeSource.location.toURI())
		val meta = JarFile(jar).use { file ->
			file.getInputStream(file.getJarEntry("build_meta.json")).bufferedReader().readText()
		}
		val bundled = Regex("""chromium-(\d+)\.""").find(meta)?.groupValues?.get(1)
		assertNotNull(bundled, "no Chromium version in JCEF's build_meta.json: $meta")

		val claimed = Regex("""Chrome/(\d+)\.""").find(UserAgents.CHROME_DESKTOP)?.groupValues?.get(1)
		assertEquals(bundled, claimed, "UserAgents.CHROME_DESKTOP must claim Chrome $bundled")
	}
}
