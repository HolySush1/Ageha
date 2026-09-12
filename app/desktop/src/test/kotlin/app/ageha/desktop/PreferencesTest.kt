package app.ageha.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The preferences that have to hold a particular value rather than merely round-trip.
 *
 * Only one so far, and it is here because its default is the whole point of it: a bot check is a
 * site asking whether a person is there, and Ageha answering that out of sight is the thing this
 * setting exists to stop. A refactor that flipped the default, or a stored file that read as "off"
 * because the key was absent, would put the old behaviour back silently -- and silently is exactly
 * how it would be discovered, which is to say not at all.
 */
class PreferencesTest {

	@TempDir
	lateinit var dir: File

	private fun store() = PreferencesStore(File(dir, "preferences.json"))

	@Test
	fun `a fresh installation shows bot checks straight away`() {
		assertTrue(Preferences().showChecksImmediately)
		assertTrue(store().load().showChecksImmediately, "nothing saved yet must still mean shown")
	}

	/**
	 * The upgrade case, and the one a default alone does not prove: every existing installation has
	 * a preferences file written before this key existed. It must read as "show", not as "off"
	 * because the key is missing.
	 */
	@Test
	fun `a file written before this setting existed still shows checks`() {
		val file = File(dir, "preferences.json")
		file.writeText("""{ "theme": "EMBER", "hideBrokenSources": true }""")

		assertTrue(PreferencesStore(file).load().showChecksImmediately)
	}

	@Test
	fun `turning it off survives a restart`() {
		val store = store()
		store.save(Preferences(showChecksImmediately = false))

		assertFalse(store.load().showChecksImmediately)
	}
}
