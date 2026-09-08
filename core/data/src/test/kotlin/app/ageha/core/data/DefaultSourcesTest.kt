package app.ageha.core.data

import app.ageha.core.database.dao.SourcesDao
import app.ageha.core.database.entity.MangaSourceEntity
import app.ageha.core.model.AgehaContentType
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.source.MangaSourceRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a fresh installation starts with.
 *
 * Two things are pinned here and they pull in opposite directions. The default set has to be wide
 * enough to be worth having -- an empty Explore screen on first run is the problem it exists to
 * solve -- and it must never reach past what was asked for. So the cases that matter are the
 * exclusions: adult, broken, and other languages.
 *
 * The two ways in have deliberately different manners, and the tests keep them apart. The
 * **automatic** one runs at startup, unasked, and so may only act where there is no decision to
 * overrule -- an empty table, nothing else. The **explicit** one is a button somebody pressed, and
 * turns on every default that is off, including ones switched off earlier. What they share is that
 * neither ever turns anything off.
 */
class DefaultSourcesTest {

	private fun descriptor(
		name: String,
		locale: String? = "en",
		type: AgehaContentType = AgehaContentType.MANGA,
		broken: Boolean = false,
	) = SourceDescriptor(
		name = name,
		title = name.lowercase().replaceFirstChar { it.uppercase() },
		locale = locale,
		contentType = type,
		isBroken = broken,
	)

	private val catalogue = listOf(
		descriptor("ENGLISH_ONE"),
		descriptor("ENGLISH_TWO"),
		// No language tag: a multi-language source, which is what MangaDex and Comick are.
		descriptor("MULTI", locale = null),
		descriptor("JAPANESE", locale = "ja"),
		descriptor("ADULT", type = AgehaContentType.HENTAI),
		// Adult *and* multi-language, so the language clause cannot be what excludes it.
		descriptor("ADULT_MULTI", locale = null, type = AgehaContentType.HENTAI),
		descriptor("BROKEN", broken = true),
	)

	private fun names(list: List<SourceDescriptor>) = list.map { it.name }.sorted()

	@Test
	fun `the default set is English and multi-language, without adult or broken sources`() {
		assertEquals(
			listOf("ENGLISH_ONE", "ENGLISH_TWO", "MULTI"),
			names(DefaultSources.from(catalogue)),
		)
	}

	/**
	 * The one exclusion worth stating on its own. Everything else here is a preference; this is
	 * the promise that nothing adult is switched on by a default.
	 */
	@Test
	fun `no adult source is ever a default`() {
		val defaults = DefaultSources.from(catalogue)
		assertTrue(defaults.none { it.isAdult }, "an adult source reached the default set")
	}

	@Test
	fun `a first run enables the defaults`() = runTest {
		val dao = InMemorySourcesDao()
		val repository = SourceRepository(dao, FakeRegistry())

		val seeded = repository.seedDefaultsOnFirstRun()

		assertEquals(listOf("ENGLISH_ONE", "ENGLISH_TWO", "MULTI"), seeded.sorted())
		assertEquals(
			listOf("ENGLISH_ONE", "ENGLISH_TWO", "MULTI"),
			dao.all().filter { it.isEnabled }.map { it.source }.sorted(),
		)
	}

	/**
	 * The guard that keeps a default a default. Somebody who has curated their own list must not
	 * find it rearranged because a parsers update introduced sources matching the default rule.
	 */
	@Test
	fun `a run that is not the first seeds nothing`() = runTest {
		val dao = InMemorySourcesDao()
		val repository = SourceRepository(dao, FakeRegistry())
		// One decision on record is enough to make this not a first run.
		repository.setEnabled("JAPANESE", enabled = true)

		val seeded = repository.seedDefaultsOnFirstRun()

		assertEquals(emptyList<String>(), seeded)
		assertEquals(listOf("JAPANESE"), dao.all().map { it.source })
	}

	/**
	 * The Explore button, and the CLI's `defaults --apply`.
	 *
	 * A default that is switched off is switched back on. This is an *action*, not a policy --
	 * somebody asked for the default set by name -- and a button that quietly skipped the sources
	 * you had once turned off would be a button whose result nobody could predict. The automatic
	 * path never does this; see the first-run test above.
	 */
	@Test
	fun `applying the defaults turns on every default that is off`() = runTest {
		val dao = InMemorySourcesDao()
		val repository = SourceRepository(dao, FakeRegistry())
		repository.setEnabled("ENGLISH_ONE", enabled = false)

		val added = repository.enableDefaults()

		assertEquals(listOf("ENGLISH_ONE", "ENGLISH_TWO", "MULTI"), added.sorted())
		assertEquals(
			listOf("ENGLISH_ONE", "ENGLISH_TWO", "MULTI"),
			dao.all().filter { it.isEnabled }.map { it.source }.sorted(),
		)
	}

	/**
	 * The other half of that, and the one that keeps it safe: it only ever adds.
	 *
	 * A source outside the default set keeps whatever it has -- a language you added, an 18+
	 * source you chose. If this ever turned something off, the button would be destroying work
	 * rather than saving it, and it is offered without a confirmation on the grounds that it
	 * cannot.
	 */
	@Test
	fun `applying the defaults never turns anything off`() = runTest {
		val dao = InMemorySourcesDao()
		val repository = SourceRepository(dao, FakeRegistry())
		repository.setEnabled("JAPANESE", enabled = true)
		repository.setEnabled("ADULT", enabled = true)

		repository.enableDefaults()

		assertEquals(
			listOf("ADULT", "ENGLISH_ONE", "ENGLISH_TWO", "JAPANESE", "MULTI"),
			dao.all().filter { it.isEnabled }.map { it.source }.sorted(),
		)
	}

	/** What the button counts, and why it stops being drawn. */
	@Test
	fun `the count of defaults that are off empties as they are enabled`() = runTest {
		val dao = InMemorySourcesDao()
		val repository = SourceRepository(dao, FakeRegistry())

		assertEquals(3, repository.observeDefaultsOff().first().size)
		repository.enableDefaults()
		assertEquals(0, repository.observeDefaultsOff().first().size)
	}

	@Test
	fun `applying the defaults twice adds nothing the second time`() = runTest {
		val dao = InMemorySourcesDao()
		val repository = SourceRepository(dao, FakeRegistry())

		repository.enableDefaults()
		val again = repository.enableDefaults()

		assertEquals(emptyList<String>(), again)
		assertEquals(3, dao.all().size)
	}

	private inner class FakeRegistry : MangaSourceRegistry {
		override fun availableSources() = catalogue
		override fun descriptorFor(name: String) = catalogue.firstOrNull { it.name == name }
		override fun clientFor(name: String): MangaSourceClient = error("not needed")
		override val parsersVersion = "test"
	}

	private class InMemorySourcesDao : SourcesDao {
		private val rows = MutableStateFlow<List<MangaSourceEntity>>(emptyList())

		override suspend fun all(): List<MangaSourceEntity> = rows.value
		override fun observeAll(): Flow<List<MangaSourceEntity>> = rows
		override fun observeEnabled(): Flow<List<MangaSourceEntity>> = rows
		override suspend fun find(name: String) = rows.value.firstOrNull { it.source == name }
		override suspend fun nextSortKey() = rows.value.size

		override suspend fun upsert(source: MangaSourceEntity) {
			rows.value = rows.value.filterNot { it.source == source.source } + source
		}

		override suspend fun upsertAll(sources: List<MangaSourceEntity>) {
			val names = sources.mapTo(mutableSetOf()) { it.source }
			rows.value = rows.value.filterNot { it.source in names } + sources
		}

		override suspend fun setEnabled(name: String, enabled: Boolean) {
			rows.value = rows.value.map { if (it.source == name) it.copy(isEnabled = enabled) else it }
		}

		override suspend fun markUsed(name: String, now: Long) {
			rows.value = rows.value.map { if (it.source == name) it.copy(lastUsedAt = now) else it }
		}
	}
}
