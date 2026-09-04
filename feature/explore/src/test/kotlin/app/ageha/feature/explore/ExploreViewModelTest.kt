package app.ageha.feature.explore

import app.ageha.core.data.SourceRepository
import app.ageha.core.database.dao.SourcesDao
import app.ageha.core.database.entity.MangaSourceEntity
import app.ageha.core.model.AgehaContentType
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.source.MangaSourceRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The source picker's three filters.
 *
 * The catalogue is around 1360 entries and the filters are the only thing that makes it usable, so
 * the cases that matter here are the ones where a filter could quietly withhold something: an
 * adult source appearing when nobody asked for one, a broken source vanishing from the view whose
 * entire purpose is to show broken sources, and the hidden-counts disagreeing with the list they
 * describe. That last one is the subtle one -- a count computed by a second, separate reading of
 * the filter rules is a count that drifts away from the list the moment either changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {

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

	/** A plain source, an adult one, a broken one, and one that is both. Plus a second language. */
	private val catalogue = listOf(
		descriptor("ALPHA"),
		descriptor("BETA", locale = "ja"),
		descriptor("GAMMA", type = AgehaContentType.HENTAI),
		descriptor("DELTA", broken = true),
		descriptor("EPSILON", type = AgehaContentType.HENTAI, broken = true),
	)

	private inner class FakeRegistry : MangaSourceRegistry {
		override fun availableSources() = catalogue
		override fun descriptorFor(name: String) = catalogue.firstOrNull { it.name == name }
		override fun clientFor(name: String): MangaSourceClient = error("not needed")
		override val parsersVersion = "test"
	}

	/**
	 * A view model that is actually running, which takes more setting up than it looks.
	 *
	 * `ExploreViewModel.state` is a `stateIn` with `WhileSubscribed`, so two things have to be true
	 * before any assertion here means anything, and getting either wrong produces a test that reads
	 * the initial empty state and fails for reasons unrelated to filtering:
	 *
	 *  - **Something has to be subscribed**, or the flow never recomputes at all.
	 *  - **Both the subscriber and the view model's own scope have to dispatch eagerly.** Under a
	 *    plain `StandardTestDispatcher` neither the collector nor the sharing coroutine runs, even
	 *    across `advanceUntilIdle` -- `backgroundScope` work is not advanced with the test body.
	 *    `UnconfinedTestDispatcher` starts them where they are launched, which is what makes the
	 *    state observable synchronously.
	 *
	 * The scope keeps `backgroundScope`'s job, so it is still cancelled when the test ends and the
	 * sharing coroutine cannot outlive it.
	 */
	private fun TestScope.viewModel(
		hideBroken: Boolean = true,
		showAdult: Boolean = false,
		locale: String? = null,
		onFiltersChanged: (Boolean, Boolean, String?) -> Unit = { _, _, _ -> },
	): ExploreViewModel {
		val registry = FakeRegistry()
		val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
		val model = ExploreViewModel(
			sources = SourceRepository(InMemorySourcesDao(), registry),
			scope = eager,
			hideBroken = hideBroken,
			showAdult = showAdult,
			locale = locale,
			onFiltersChanged = onFiltersChanged,
		)
		eager.launch { model.state.collect {} }
		return model
	}

	/** Names in the list, which is all these assertions ever care about. */
	private fun ExploreViewModel.names() = state.value.sources.map { it.name }.sorted()

	@Test
	fun `adult sources are hidden until asked for`() = runTest(StandardTestDispatcher()) {
		val model = viewModel()
		model.setFilter(SourceFilter.ALL)
		advanceUntilIdle()

		assertEquals(listOf("ALPHA", "BETA"), model.names())
		assertFalse(model.state.value.showAdult, "adult sources start hidden")
	}

	@Test
	fun `turning on 18+ reveals adult sources`() = runTest(StandardTestDispatcher()) {
		val model = viewModel()
		model.setFilter(SourceFilter.ALL)
		model.setShowAdult(true)
		advanceUntilIdle()

		assertEquals(listOf("ALPHA", "BETA", "GAMMA"), model.names())
	}

	/**
	 * EPSILON is adult *and* broken, and with both filters on it must not be counted twice. The
	 * two counts are shown side by side and are meant to add up to what was withheld.
	 */
	@Test
	fun `broken sources are hidden by default and counted separately from adult ones`() =
		runTest(StandardTestDispatcher()) {
			val model = viewModel()
			model.setFilter(SourceFilter.ALL)
			advanceUntilIdle()

			val state = model.state.value
			assertEquals(listOf("ALPHA", "BETA"), model.names())
			// GAMMA and EPSILON are adult; DELTA is the only source withheld for being broken.
			assertEquals(2, state.hiddenAdultCount)
			assertEquals(1, state.hiddenBrokenCount)
			assertTrue(state.hasHiddenSources)
		}

	@Test
	fun `the counts add up to what was actually withheld`() = runTest(StandardTestDispatcher()) {
		val model = viewModel()
		model.setFilter(SourceFilter.ALL)
		advanceUntilIdle()

		val state = model.state.value
		val withheld = catalogue.size - state.sources.size
		assertEquals(withheld, state.hiddenAdultCount + state.hiddenBrokenCount)
	}

	/**
	 * The one rule between the filters: asking to see broken sources beats the switch that hides
	 * them. Without this the "Known broken" view is empty by default, which looks like a bug in
	 * the view rather than like a filter doing its job.
	 */
	@Test
	fun `the broken view overrides hide-broken`() = runTest(StandardTestDispatcher()) {
		val model = viewModel(hideBroken = true)
		model.setFilter(SourceFilter.BROKEN)
		advanceUntilIdle()

		assertEquals(listOf("DELTA"), model.names())
		assertEquals(0, model.state.value.hiddenBrokenCount, "nothing is withheld for being broken here")
	}

	@Test
	fun `the broken view still respects the adult filter`() = runTest(StandardTestDispatcher()) {
		val model = viewModel()
		model.setFilter(SourceFilter.BROKEN)
		model.setShowAdult(true)
		advanceUntilIdle()

		assertEquals(listOf("DELTA", "EPSILON"), model.names())
	}

	@Test
	fun `filtering by language narrows the list`() = runTest(StandardTestDispatcher()) {
		val model = viewModel()
		model.setFilter(SourceFilter.ALL)
		model.setLocale("ja")
		advanceUntilIdle()

		assertEquals(listOf("BETA"), model.names())
	}

	/**
	 * The menu offers languages with counts, and the counts describe what is reachable rather than
	 * what exists. With adult sources hidden, a language menu that still counts them sends the
	 * user to a language whose sources they cannot see.
	 */
	@Test
	fun `the language menu counts only reachable sources`() = runTest(StandardTestDispatcher()) {
		val model = viewModel()
		model.setFilter(SourceFilter.ALL)
		advanceUntilIdle()

		val english = model.state.value.availableLocales.first { it.tag == "en" }
		// ALPHA only: GAMMA and EPSILON are adult, DELTA is broken.
		assertEquals(1, english.count)
		assertEquals("English", english.displayName, "tags are shown as language names, not as tags")
	}

	@Test
	fun `the language menu offers languages other than the one selected`() =
		runTest(StandardTestDispatcher()) {
			val model = viewModel()
			model.setFilter(SourceFilter.ALL)
			model.setLocale("ja")
			advanceUntilIdle()

			val tags = model.state.value.availableLocales.map { it.tag }
			assertTrue("en" in tags, "selecting Japanese must not remove English from the menu")
		}

	/** The filters are a setting. The shell persists them, so it has to be told when they change. */
	@Test
	fun `filter changes are reported for persistence`() = runTest(StandardTestDispatcher()) {
		val seen = mutableListOf<Triple<Boolean, Boolean, String?>>()
		val model = viewModel { hideBroken, showAdult, locale ->
			seen += Triple(hideBroken, showAdult, locale)
		}
		model.setShowAdult(true)
		model.setHideBroken(false)
		model.setLocale("ja")
		advanceUntilIdle()

		assertEquals(
			listOf(
				Triple(true, true, null),
				Triple(false, true, null),
				Triple(false, true, "ja"),
			),
			seen,
		)
	}

	@Test
	fun `seeded filters are applied before the user touches anything`() =
		runTest(StandardTestDispatcher()) {
			val model = viewModel(hideBroken = false, showAdult = true, locale = "en")
			model.setFilter(SourceFilter.ALL)
			advanceUntilIdle()

			assertEquals(listOf("ALPHA", "DELTA", "EPSILON", "GAMMA"), model.names())
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

		override suspend fun setEnabled(name: String, enabled: Boolean) {
			rows.value = rows.value.map { if (it.source == name) it.copy(isEnabled = enabled) else it }
		}

		override suspend fun markUsed(name: String, now: Long) {
			rows.value = rows.value.map { if (it.source == name) it.copy(lastUsedAt = now) else it }
		}
	}
}
