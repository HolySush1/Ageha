package app.ageha.feature.reader

import app.ageha.core.data.CatalogRepository
import app.ageha.core.database.AgehaDatabase
import app.ageha.core.database.AgehaDatabaseFactory
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.SourceFailure
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.source.MangaSourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The last page turn must survive the application quitting.
 *
 * This is the narrowest, most easily-broken behaviour in the reader, and it has failed in two
 * opposite ways. Both are one keyword apart from correct, both compile, and neither shows up
 * anywhere except as a reading position that quietly reverted a chapter:
 *
 *  - `scope.launch(NonCancellable) { … }` detaches the write from the scope, because
 *    `NonCancellable` is a `Job` and `launch` takes it as the parent. Shutdown's `join()` then has
 *    nothing to wait for and closes the database mid-write.
 *  - A plain `scope.launch { … }` is cancelled before its body runs if the quit wins the race to
 *    the dispatcher, so the write never happens at all.
 *
 * The test is therefore the harshest possible ordering: save, then cancel immediately, with no
 * chance for the coroutine to be dispatched in between.
 */
class ReaderShutdownTest {

	private lateinit var db: AgehaDatabase

	private object EmptyRegistry : MangaSourceRegistry {
		override fun availableSources() = emptyList<app.ageha.core.model.SourceDescriptor>()
		override fun descriptorFor(name: String) = null
		override fun clientFor(name: String): MangaSourceClient = throw SourceFailure.UnknownSource(name)
		override val parsersVersion = "test"
	}

	@BeforeEach
	fun open() {
		db = AgehaDatabaseFactory.openInMemory()
	}

	@AfterEach
	fun close() = db.close()

	@Test
	@DisplayName("the final position is written even if the scope is cancelled in the same breath")
	fun finalWriteSurvivesCancellation() = runBlocking {
		val reader = app.ageha.core.data.ReaderRepository(
			catalog = CatalogRepository(EmptyRegistry),
			manga = db.mangaDao(),
			history = db.historyDao(),
			prefs = db.mangaPrefsDao(),
		)

		// One thread, and it is held busy until after the cancellation.
		//
		// This is what makes the test deterministic rather than a coin toss. On a multi-threaded
		// dispatcher a plain `launch` usually wins the race to run before `cancel` arrives, so the
		// bug it is guarding against passes most of the time and fails in front of a user. With the
		// only worker blocked, a *dispatched* coroutine provably cannot have started, which is
		// precisely the condition the shutdown path has to survive.
		val executor = Executors.newSingleThreadExecutor()
		val released = CountDownLatch(1)
		executor.execute { released.await() }
		val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
		try {
			val viewModel = ReaderViewModel(reader, scope)
			viewModel.open(manga, chapter)

			viewModel.savePositionNow()
			// Exactly what closing the window does, with nothing in between.
			scope.cancel()
			released.countDown()
			scope.coroutineContext.job.join()

			val saved = db.historyDao().find(manga.id)
			assertNotNull(saved, "the last position must survive the quit that caused it to be saved")
			assertEquals(chapter.id, saved!!.chapterId)
		} finally {
			executor.shutdownNow()
		}
	}

	private val chapter = AgehaChapter(
		id = 11L,
		title = "Chapter 1",
		number = 1f,
		volume = null,
		url = "/c/1",
		scanlator = null,
		uploadDate = null,
		branch = null,
		sourceName = "TEST",
	)

	private val manga = AgehaManga(
		id = 1L,
		title = "A Manga",
		altTitles = emptySet(),
		url = "/m/1",
		publicUrl = "https://example.test/m/1",
		rating = null,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = listOf(chapter),
		sourceName = "TEST",
	)
}
