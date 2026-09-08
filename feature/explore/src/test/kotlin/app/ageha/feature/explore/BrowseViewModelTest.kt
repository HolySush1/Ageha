package app.ageha.feature.explore

import app.ageha.core.data.CatalogRepository
import app.ageha.core.data.SourceRepository
import app.ageha.core.database.dao.SourcesDao
import app.ageha.core.database.entity.MangaSourceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaContentType
import app.ageha.core.model.AgehaFilter
import app.ageha.core.model.AgehaFilterCapabilities
import app.ageha.core.model.AgehaFilterOptions
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaPage
import app.ageha.core.model.AgehaSortOrder
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.model.SourceFailure
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.source.MangaSourceRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Paging a source, and what happens when one misbehaves.
 *
 * Sources are the least trustworthy thing Ageha talks to, so the interesting cases here are all
 * failure cases: repeated entries between pages, a listing that never ends, a block partway
 * through. Each one is something real sources actually do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {

	private val descriptor = SourceDescriptor(
		name = "TEST",
		title = "Test Source",
		locale = "en",
		contentType = AgehaContentType.MANGA,
		isBroken = false,
	)

	private fun manga(id: Long) = AgehaManga(
		id = id,
		title = "Manga $id",
		altTitles = emptySet(),
		url = "/m/$id",
		publicUrl = "https://test/m/$id",
		rating = null,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = null,
		sourceName = "TEST",
	)

	/** A source that returns whatever the test tells it to, page by page. */
	private inner class FakeClient(
		private val pages: List<Result<List<AgehaManga>>>,
	) : MangaSourceClient {
		var calls = 0
			private set

		override val descriptor = this@BrowseViewModelTest.descriptor
		override val domain = "test.invalid"
		override val availableSortOrders = setOf(AgehaSortOrder.UPDATED, AgehaSortOrder.POPULARITY)
		override val filterCapabilities = AgehaFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
			isSearchWithFiltersSupported = false,
			isYearSupported = false,
			isAuthorSearchSupported = false,
		)

		override suspend fun list(offset: Int, order: AgehaSortOrder, filter: AgehaFilter): List<AgehaManga> {
			val page = pages.getOrElse(calls) { Result.success(emptyList()) }
			calls++
			return page.getOrThrow()
		}

		override suspend fun details(manga: AgehaManga) = manga
		override suspend fun pages(chapter: AgehaChapter): List<AgehaPage> = emptyList()
		override suspend fun pageUrl(page: AgehaPage) = page.url
		override suspend fun filterOptions() =
			AgehaFilterOptions(emptySet(), emptySet(), emptySet(), emptySet())
		override suspend fun relatedManga(seed: AgehaManga): List<AgehaManga> = emptyList()
		override fun imageRequestHeaders() = mapOf("Referer" to "https://test.invalid/")
	}

	private inner class FakeRegistry(private val client: MangaSourceClient) : MangaSourceRegistry {
		override fun availableSources() = listOf(descriptor)
		override fun descriptorFor(name: String) = descriptor.takeIf { it.name == name }
		override fun clientFor(name: String) = client
		override val parsersVersion = "test"
	}

	private fun viewModel(
		pages: List<Result<List<AgehaManga>>>,
		scope: TestScope,
	): Pair<BrowseViewModel, FakeClient> {
		val client = FakeClient(pages)
		val registry = FakeRegistry(client)
		return BrowseViewModel(
			catalog = CatalogRepository(registry),
			sources = SourceRepository(InMemorySourcesDao(), registry),
			scope = scope,
		) to client
	}

	@Test
	fun `the first page loads and reports more available`() = runTest(StandardTestDispatcher()) {
		val (model, _) = viewModel(listOf(Result.success(listOf(manga(1), manga(2)))), this)
		model.open("TEST")
		advanceUntilIdle()

		val state = model.state.value
		assertEquals(2, state.manga.size)
		assertTrue(state.hasMore, "a non-empty page means there may be more")
		assertFalse(state.isLoadingFirstPage)
		assertEquals("Test Source", state.sourceTitle)
		assertEquals(mapOf("Referer" to "https://test.invalid/"), state.imageHeaders)
	}

	/**
	 * Sources repeat entries between offsets when the underlying listing shifts mid-scroll, which
	 * it does constantly on a "recently updated" ordering. A duplicate key in a lazy grid is a
	 * hard crash, not a cosmetic problem, so the dedup is load-bearing.
	 */
	@Test
	fun `entries repeated across pages appear once`() = runTest(StandardTestDispatcher()) {
		val (model, _) = viewModel(
			listOf(
				Result.success(listOf(manga(1), manga(2))),
				Result.success(listOf(manga(2), manga(3))),
			),
			this,
		)
		model.open("TEST")
		advanceUntilIdle()
		model.loadMore()
		advanceUntilIdle()

		val ids = model.state.value.manga.map { it.id }
		assertEquals(listOf(1L, 2L, 3L), ids)
	}

	@Test
	fun `an empty page ends the listing`() = runTest(StandardTestDispatcher()) {
		val (model, client) = viewModel(
			listOf(Result.success(listOf(manga(1))), Result.success(emptyList())),
			this,
		)
		model.open("TEST")
		advanceUntilIdle()
		model.loadMore()
		advanceUntilIdle()

		assertFalse(model.state.value.hasMore)
		val callsAtEnd = client.calls
		// Nothing further is requested once the end is known. A listing that keeps asking is how
		// an app gets its whole user base rate-limited.
		model.loadMore()
		advanceUntilIdle()
		assertEquals(callsAtEnd, client.calls)
	}

	/**
	 * A failure never clears what is already on screen. Someone three screens into a listing
	 * should not lose it because page four was blocked.
	 */
	@Test
	fun `a failure keeps the results already loaded`() = runTest(StandardTestDispatcher()) {
		val (model, _) = viewModel(
			listOf(
				Result.success(listOf(manga(1), manga(2))),
				Result.failure(SourceFailure.Blocked("TEST", statusCode = 403, url = null)),
			),
			this,
		)
		model.open("TEST")
		advanceUntilIdle()
		model.loadMore()
		advanceUntilIdle()

		val state = model.state.value
		assertEquals(2, state.manga.size, "the loaded page must survive the failed one")
		assertNotNull(state.failure)
		assertFalse(state.hasMore, "a non-transient block must stop the pager")
	}

	@Test
	fun `a transient failure leaves the pager willing to retry`() = runTest(StandardTestDispatcher()) {
		val (model, _) = viewModel(
			listOf(
				Result.success(listOf(manga(1))),
				Result.failure(SourceFailure.Network("TEST", cause = java.io.IOException("boom"))),
			),
			this,
		)
		model.open("TEST")
		advanceUntilIdle()
		model.loadMore()
		advanceUntilIdle()

		assertTrue(model.state.value.hasMore, "a network blip is worth retrying; a 403 is not")
	}

	@Test
	fun `changing the sort restarts the listing rather than appending`() = runTest(StandardTestDispatcher()) {
		val (model, _) = viewModel(
			listOf(
				Result.success(listOf(manga(1), manga(2))),
				Result.success(listOf(manga(10), manga(11))),
			),
			this,
		)
		model.open("TEST")
		advanceUntilIdle()
		model.setSort(AgehaSortOrder.POPULARITY)
		advanceUntilIdle()

		assertEquals(listOf(10L, 11L), model.state.value.manga.map { it.id })
	}

	/**
	 * `availableSortOrders` is a set, so "the first one" is not meaningfully ordered. Browsing a
	 * manga source almost always means "what updated recently", so that is preferred when offered.
	 */
	@Test
	fun `the default sort prefers recently updated`() = runTest(StandardTestDispatcher()) {
		val (model, _) = viewModel(listOf(Result.success(listOf(manga(1)))), this)
		model.open("TEST")
		advanceUntilIdle()
		assertEquals(AgehaSortOrder.UPDATED, model.state.value.sort)
	}

	@Test
	fun `an unexpected exception becomes a source failure rather than a crash`() =
		runTest(StandardTestDispatcher()) {
			val (model, _) = viewModel(
				listOf(Result.failure(IllegalStateException("a parser threw something odd"))),
				this,
			)
			model.open("TEST")
			advanceUntilIdle()

			val failure = model.state.value.failure
			assertTrue(failure is SourceFailure.Unknown) {
				"the facade's guarantee must hold even when a parser breaks it: got $failure"
			}
		}

	/**
	 * The sources table, in memory.
	 *
	 * Small enough to be worth writing by hand, and it keeps these tests free of Room, a temp
	 * directory and a native SQLite load -- none of which has anything to do with what is being
	 * tested here. `SourceRepository` takes this DAO rather than the whole database precisely so
	 * that is possible.
	 */
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
