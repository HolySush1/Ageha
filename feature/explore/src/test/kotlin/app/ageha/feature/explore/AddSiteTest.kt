package app.ageha.feature.explore

import app.ageha.core.data.SourceRepository
import app.ageha.core.database.dao.SourcesDao
import app.ageha.core.database.entity.MangaSourceEntity
import app.ageha.core.model.AgehaContentType
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.source.MangaSourceRegistry
import app.ageha.core.source.ResolvedLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Add site dialog's state machine.
 *
 * The resolver is faked: whether the parsers library recognises comix.to is its business, and is
 * checked against the real library from the CLI. What is tested here is everything Ageha decides
 * around it -- in particular the distinctions the dialog exists to draw, which a looser state
 * model would blur into "nothing found".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddSiteTest {

	private val comix = SourceDescriptor(
		name = "COMIX",
		title = "Comix",
		locale = "en",
		contentType = AgehaContentType.MANGA,
		isBroken = false,
	)

	/**
	 * A site that publishes in several languages, as MangaBall does in 42.
	 *
	 * Titled the way upstream titles that family, so the tests exercise the real shape: the
	 * language is in the title, and the names sort in the order the enum declares them.
	 */
	private fun ball(code: String, language: String) = SourceDescriptor(
		name = "MANGABALL_" + code.uppercase(),
		title = "Manga Ball ($language)",
		locale = code,
		contentType = AgehaContentType.MANGA,
		isBroken = false,
	)

	private val ballAr = ball("ar", "Arabic")
	private val ballEn = ball("en", "English")
	private val ballFr = ball("fr", "French")

	/** What the fake registry lists, set per test. */
	private var catalogue: List<SourceDescriptor> = listOf(comix)

	/** What the fake resolver answers, set per test. */
	private var answer: suspend (String) -> ResolvedLink? = { null }

	/** What the fake resolver answers when asked as one particular source. */
	private var answerAs: suspend (String, String) -> ResolvedLink? = { _, _ -> null }

	/** Every link actually handed to the resolver. */
	private val asked = mutableListOf<String>()

	private val dao = InMemorySourcesDao()

	private inner class FakeRegistry : MangaSourceRegistry {
		override fun availableSources() = catalogue
		override fun descriptorFor(name: String) = availableSources().firstOrNull { it.name == name }
		override fun clientFor(name: String): MangaSourceClient = error("not needed")
		override val parsersVersion = "test-build"

		override suspend fun resolveLink(url: String): ResolvedLink? {
			asked += url
			return answer(url)
		}

		override suspend fun resolveLinkAs(url: String, sourceName: String): ResolvedLink? =
			answerAs(url, sourceName)
	}

	/** Eager for the reason ExploreViewModelTest.viewModel spells out. */
	private fun TestScope.viewModel(systemLanguage: String = "en"): ExploreViewModel {
		val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
		return ExploreViewModel(
			sources = SourceRepository(dao, FakeRegistry()),
			scope = eager,
			// Stated rather than inherited: the preferred language is the thing under test in half
			// of these, and a machine set to French would otherwise quietly change the answer.
			systemLanguage = systemLanguage,
		)
	}

	private fun ExploreViewModel.status() = (addSite.value as AddSiteState.Open).status

	private fun ExploreViewModel.lookUp(text: String) {
		openAddSite()
		setAddSiteInput(text)
		findSite()
	}

	@Test
	fun `a site's front page finds its source`() = runTest(StandardTestDispatcher()) {
		answer = { ResolvedLink("COMIX", manga = null) }
		val model = viewModel()

		model.lookUp("https://comix.to/")
		advanceUntilIdle()

		assertEquals(AddSiteStatus.Found("comix.to", comix, manga = null), model.status())
	}

	@Test
	fun `a bare host is looked up over https`() = runTest(StandardTestDispatcher()) {
		answer = { ResolvedLink("COMIX", manga = null) }
		val model = viewModel()

		model.lookUp("comix.to")
		advanceUntilIdle()

		assertEquals(listOf("https://comix.to"), asked)
	}

	@Test
	fun `a manga link carries the manga`() = runTest(StandardTestDispatcher()) {
		val manga = manga("Becoming a God by Myself")
		answer = { ResolvedLink("COMIX", manga) }
		val model = viewModel()

		model.lookUp("https://comix.to/title/exm0x")
		advanceUntilIdle()

		assertEquals(manga, (model.status() as AddSiteStatus.Found).manga)
	}

	@Test
	fun `text that is not a link never reaches the resolver`() = runTest(StandardTestDispatcher()) {
		val model = viewModel()

		model.lookUp("not a link")
		advanceUntilIdle()

		assertEquals(AddSiteStatus.NotALink, model.status())
		assertTrue(asked.isEmpty(), "a typo must not cost a network round trip")
	}

	/**
	 * The distinction the dialog exists for. "Not a link" is a typo to fix; "not found" is a fact
	 * about the library that retyping cannot change. It carries the build so the copy can say
	 * which one was checked -- a newer parsers build may well have the site.
	 */
	@Test
	fun `a site no source reads is not found rather than not a link`() = runTest(StandardTestDispatcher()) {
		answer = { null }
		val model = viewModel()

		model.lookUp("https://example.com")
		advanceUntilIdle()

		assertEquals(AddSiteStatus.NotFound("example.com", "test-build"), model.status())
	}

	@Test
	fun `a resolver that throws is a failed lookup, not a missing site`() = runTest(StandardTestDispatcher()) {
		answer = { error("the site sent back garbage") }
		val model = viewModel()

		model.lookUp("https://comix.to/")
		advanceUntilIdle()

		assertInstanceOf(AddSiteStatus.Failed::class.java, model.status())
	}

	@Test
	fun `a lookup that never answers times out instead of spinning forever`() = runTest(StandardTestDispatcher()) {
		answer = { awaitCancellation() }
		val model = viewModel()

		model.lookUp("https://comix.to/")
		advanceTimeBy(ADD_SITE_TIMEOUT_MS + 1)

		val status = model.status()
		assertInstanceOf(AddSiteStatus.Failed::class.java, status)
		assertTrue((status as AddSiteStatus.Failed).reason.contains("longer"), status.reason)
	}

	/**
	 * An answer for the previous text, left under a field that now reads something else, would
	 * put an Open button on screen that opens the wrong site.
	 */
	@Test
	fun `changing the text discards the previous answer`() = runTest(StandardTestDispatcher()) {
		answer = { ResolvedLink("COMIX", manga = null) }
		val model = viewModel()
		model.lookUp("https://comix.to/")
		advanceUntilIdle()

		model.setAddSiteInput("https://another.site/")

		assertEquals(AddSiteStatus.Idle, model.status())
	}

	@Test
	fun `closing the dialog abandons a lookup in flight`() = runTest(StandardTestDispatcher()) {
		answer = { awaitCancellation() }
		val model = viewModel()
		model.lookUp("https://comix.to/")

		model.closeAddSite()
		advanceUntilIdle()

		assertEquals(AddSiteState.Closed, model.addSite.value)
	}

	/**
	 * Pasting a link is asking for that site. A source opened from one and then missing from the
	 * Enabled list the next time the user looked would read as the link not having worked -- and
	 * the typical pasted site is one the user has never touched, so there is no row to flip.
	 */
	@Test
	fun `opening what was found switches the source on and closes the dialog`() = runTest(StandardTestDispatcher()) {
		answer = { ResolvedLink("COMIX", manga = null) }
		val model = viewModel()
		model.lookUp("https://comix.to/")
		advanceUntilIdle()

		val taken = model.acceptFound()
		advanceUntilIdle()

		assertEquals("COMIX", taken?.source?.name)
		assertEquals(AddSiteState.Closed, model.addSite.value)
		assertEquals(true, dao.find("COMIX")?.isEnabled, "a never-touched source must get a row, enabled")
	}

	/**
	 * The defect this family of tests exists for.
	 *
	 * Upstream's resolver returns the *first* source in the build's declaration order that serves
	 * the host. mangaball.net is served by 42, one per language, so pasting it answered "Manga Ball
	 * (Arabic)" and offered no way to reach the other 41.
	 */
	@Test
	fun `every source serving a site is offered, not only the one upstream names`() =
		runTest(StandardTestDispatcher()) {
			catalogue = listOf(ballAr, ballEn, ballFr)
			answer = {
				ResolvedLink(ballAr.name, manga = null, alternatives = listOf(ballEn.name, ballFr.name))
			}
			val model = viewModel(systemLanguage = "de")

			model.lookUp("https://mangaball.net/")
			advanceUntilIdle()

			val found = model.status() as AddSiteStatus.Found
			assertEquals(listOf(ballAr, ballEn, ballFr), found.candidates)
		}

	@Test
	fun `the machine's own language is chosen, not the first in the alphabet`() =
		runTest(StandardTestDispatcher()) {
			catalogue = listOf(ballAr, ballEn, ballFr)
			answer = {
				ResolvedLink(ballAr.name, manga = null, alternatives = listOf(ballEn.name, ballFr.name))
			}
			val model = viewModel(systemLanguage = "fr")

			model.lookUp("https://mangaball.net/")
			advanceUntilIdle()

			assertEquals(ballFr, (model.status() as AddSiteStatus.Found).source)
		}

	/** A machine set to a language the site does not publish in still should not land on Arabic. */
	@Test
	fun `English is chosen when the machine's language is not among them`() =
		runTest(StandardTestDispatcher()) {
			catalogue = listOf(ballAr, ballEn, ballFr)
			answer = {
				ResolvedLink(ballAr.name, manga = null, alternatives = listOf(ballEn.name, ballFr.name))
			}
			val model = viewModel(systemLanguage = "de")

			model.lookUp("https://mangaball.net/")
			advanceUntilIdle()

			assertEquals(ballEn, (model.status() as AddSiteStatus.Found).source)
		}

	/**
	 * Otherwise "Open manga" would open the title in the language the user just passed over: the
	 * manga came from whichever source upstream happened to name.
	 */
	@Test
	fun `choosing another language asks that source for the title`() =
		runTest(StandardTestDispatcher()) {
			catalogue = listOf(ballAr, ballEn, ballFr)
			val arabic = manga("The Arabic listing")
			val french = manga("Le titre francais")
			answer = {
				ResolvedLink(ballAr.name, arabic, alternatives = listOf(ballEn.name, ballFr.name))
			}
			answerAs = { _, name -> if (name == ballFr.name) ResolvedLink(name, french) else null }
			// Set to Arabic so the default stays on upstream's pick and the only re-resolve is the
			// one this test performs.
			val model = viewModel(systemLanguage = "ar")

			model.lookUp("https://mangaball.net/manga/x")
			advanceUntilIdle()
			model.chooseSource(ballFr.name)
			advanceUntilIdle()

			val found = model.status() as AddSiteStatus.Found
			assertEquals(ballFr, found.source)
			assertEquals(french, found.manga)
			assertFalse(found.reresolving, "the spinner must not be left running")
		}

	/**
	 * Landing on a front page is a small annoyance. Opening a title in the language just declined,
	 * silently, is not -- so a re-resolve that comes back empty drops the manga rather than keeping
	 * the one that belonged to another source.
	 */
	@Test
	fun `a title the chosen source cannot find leaves the site, not the wrong title`() =
		runTest(StandardTestDispatcher()) {
			catalogue = listOf(ballAr, ballEn, ballFr)
			answer = {
				ResolvedLink(ballAr.name, manga("The Arabic listing"), alternatives = listOf(ballFr.name))
			}
			answerAs = { _, _ -> null }
			val model = viewModel(systemLanguage = "ar")

			model.lookUp("https://mangaball.net/manga/x")
			advanceUntilIdle()
			model.chooseSource(ballFr.name)
			advanceUntilIdle()

			val found = model.status() as AddSiteStatus.Found
			assertEquals(ballFr, found.source)
			assertNull(found.manga)
		}

	@Test
	fun `enable all switches on every source serving the site`() = runTest(StandardTestDispatcher()) {
		catalogue = listOf(ballAr, ballEn, ballFr)
		answer = {
			ResolvedLink(ballAr.name, manga = null, alternatives = listOf(ballEn.name, ballFr.name))
		}
		val model = viewModel(systemLanguage = "en")

		model.lookUp("https://mangaball.net/")
		advanceUntilIdle()
		val opened = model.enableAll()
		advanceUntilIdle()

		// The one opened is still the one chosen, not an arbitrary member of the family.
		assertEquals(ballEn, opened?.source)
		assertEquals(true, dao.find(ballAr.name)?.isEnabled)
		assertEquals(true, dao.find(ballEn.name)?.isEnabled)
		assertEquals(true, dao.find(ballFr.name)?.isEnabled)
		assertEquals(AddSiteState.Closed, model.addSite.value)
	}

	/**
	 * A bridge jar older than the app knows nothing of alternatives and reports none. It must read
	 * as the single answer it used to be, not as an empty list with nothing to choose.
	 */
	@Test
	fun `a bridge too old to report alternatives offers the one source it named`() =
		runTest(StandardTestDispatcher()) {
			answer = { ResolvedLink("COMIX", manga = null) }
			val model = viewModel()

			model.lookUp("https://comix.to/")
			advanceUntilIdle()

			assertEquals(listOf(comix), (model.status() as AddSiteStatus.Found).candidates)
		}

	private fun manga(title: String) = AgehaManga(
		id = 1L,
		title = title,
		altTitles = emptySet(),
		url = "/title/exm0x",
		publicUrl = "https://comix.to/title/exm0x",
		rating = null,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = null,
		sourceName = "COMIX",
	)

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
