package app.ageha.core.jvmcontext

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.File

/**
 * Per-source settings, and that they survive a restart.
 *
 * Persistence is the whole point of this store. It was consulted on every request from the day it
 * was written and nothing ever wrote to it, so all ~1,360 sources ran on their parsers' defaults
 * forever -- and 258 of them declare a `ConfigKey.Domain` whose only purpose is to be changed when
 * the site moves. A mirror the user picks and loses on the next launch is not a remedy.
 */
class SourceConfigStoreTest {

	/** `MangaSource` is a plain Kotlin interface with one property, so a fake is one line. */
	private class TestSource(override val name: String) : MangaSource

	private val source: MangaSource = TestSource("TESTSOURCE")
	private val domain = ConfigKey.Domain("first.test", "second.test")
	private val suspicious = ConfigKey.ShowSuspiciousContent(false)

	@Test
	@DisplayName("with nothing stored, a source gets its parser's own default")
	fun defaultsWhenUnset(@TempDir dir: File) {
		val store = SourceConfigStore(File(dir, "settings.properties"))
		assertEquals("first.test", store.configFor(source)[domain])
		assertFalse(store.configFor(source)[suspicious])
	}

	@Test
	@DisplayName("a chosen mirror is still chosen after a restart")
	fun overrideSurvivesReopen(@TempDir dir: File) {
		val file = File(dir, "settings.properties")
		SourceConfigStore(file).put(source.name, domain.key, "second.test")

		val reopened = SourceConfigStore(file)
		assertEquals("second.test", reopened.configFor(source)[domain])
		assertTrue(reopened.configuredSources().contains(source.name))
	}

	@Test
	@DisplayName("clearing an override goes back to the parser's default, and stays cleared")
	fun clearingRestoresTheDefault(@TempDir dir: File) {
		val file = File(dir, "settings.properties")
		val store = SourceConfigStore(file)
		store.put(source.name, domain.key, "second.test")
		store.put(source.name, domain.key, null)

		assertEquals("first.test", store.configFor(source)[domain])
		assertEquals("first.test", SourceConfigStore(file).configFor(source)[domain])
	}

	@Test
	@DisplayName("a boolean round-trips as a boolean, not as the string 'true'")
	fun booleansAreCoerced(@TempDir dir: File) {
		val file = File(dir, "settings.properties")
		SourceConfigStore(file).put(source.name, suspicious.key, "true")
		assertTrue(SourceConfigStore(file).configFor(source)[suspicious])
	}

	@Test
	@DisplayName("a value that will not coerce degrades to the default rather than throwing")
	fun uncoercibleValueDegrades(@TempDir dir: File) {
		val store = SourceConfigStore(File(dir, "settings.properties"))
		store.put(source.name, suspicious.key, "yes-please")
		assertFalse(store.configFor(source)[suspicious])
	}

	/**
	 * A settings file must never be the reason the application will not start. Losing an override
	 * costs the user one mirror choice; refusing to launch costs them the application.
	 */
	@Test
	@DisplayName("a settings file that cannot be read is survived, not fatal")
	fun unreadableFileIsSurvived(@TempDir dir: File) {
		// A directory where a file should be. Stands in for every way the store can fail to read
		// what is there -- a truncated write, a permission change, a file someone hand-edited into
		// nonsense -- all of which take the same path out of `load`.
		val file = File(dir, "settings.properties")
		file.mkdirs()

		val store = SourceConfigStore(file)
		assertEquals("first.test", store.configFor(source)[domain])
	}

	@Test
	@DisplayName("with no file at all, the store still works and writes nothing")
	fun inMemoryStoreNeedsNoFile(@TempDir dir: File) {
		val store = SourceConfigStore(null)
		store.put(source.name, domain.key, "second.test")
		assertEquals("second.test", store.configFor(source)[domain])
		assertEquals(emptyList<File>(), dir.listFiles()?.toList().orEmpty())
	}

	@Test
	@DisplayName("one source's settings do not leak into another's")
	fun sourcesAreIndependent(@TempDir dir: File) {
		val other: MangaSource = TestSource("OTHERSOURCE")
		val file = File(dir, "settings.properties")
		val store = SourceConfigStore(file)
		store.put(source.name, domain.key, "second.test")

		assertEquals("first.test", store.configFor(other)[domain])
		assertEquals("second.test", SourceConfigStore(file).configFor(source)[domain])
	}
}
