package app.ageha.core.data

import app.ageha.core.database.AgehaDatabase
import app.ageha.core.database.AgehaDatabaseFactory
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaContentType
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.model.SourceFailure
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.source.MangaSourceRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Continue Reading.
 *
 * The rule under test throughout is the one a reader notices immediately when it is wrong:
 * **finishing a chapter means the next chapter, not the last page of the one just finished.**
 * Getting it backwards reopens a page the user already read and looks like lost progress.
 *
 * Everything here runs against a real database and a registry holding exactly one source, because
 * the second most important behaviour is what happens for the source that is *not* in it.
 */
class HistoryRepositoryTest {

	private lateinit var db: AgehaDatabase
	private lateinit var reader: ReaderRepository
	private lateinit var history: HistoryRepository

	/** One source exists. Anything else is "not in this parsers build", which is the point. */
	private object OneSourceRegistry : MangaSourceRegistry {
		private val known = SourceDescriptor(
			name = "MANGADEX",
			title = "MangaDex",
			locale = null,
			contentType = AgehaContentType.MANGA,
			isBroken = false,
		)

		override fun availableSources() = listOf(known)
		override fun descriptorFor(name: String) = known.takeIf { it.name == name }
		override fun clientFor(name: String): MangaSourceClient = throw SourceFailure.UnknownSource(name)
		override val parsersVersion = "test"
	}

	@BeforeEach
	fun open() {
		db = AgehaDatabaseFactory.openInMemory()
		reader = ReaderRepository(
			catalog = CatalogRepository(OneSourceRegistry),
			manga = db.mangaDao(),
			history = db.historyDao(),
			prefs = db.mangaPrefsDao(),
		)
		history = HistoryRepository(
			history = db.historyDao(),
			manga = db.mangaDao(),
			sources = SourceRepository(db.sourcesDao(), OneSourceRegistry),
		)
	}

	@AfterEach
	fun close() = db.close()

	// ------------------------------------------------------------------ the resume rule

	@Test
	@DisplayName("stopping mid-chapter resumes that exact page")
	fun midChapterResumesExactly() = runTest {
		val manga = manga(chapters = 3)
		reader.savePosition(manga, manga.chapters!![1], page = 7, scroll = 0f, percent = 0.4f, pageCount = 20)

		val point = history.resume(manga.id)

		assertTrue(point is ResumePoint.Open)
		point as ResumePoint.Open
		assertEquals(manga.chapters!![1].id, point.chapter.id)
		assertEquals(7, point.page)
		assertFalse(point.isCaughtUp)
	}

	@Test
	@DisplayName("stopping on the last page of a chapter opens the next one at page one")
	fun finishedChapterAdvances() = runTest {
		val manga = manga(chapters = 3)
		// Page 19 of 20 is the final page.
		reader.savePosition(manga, manga.chapters!![1], page = 19, scroll = 0f, percent = 0.66f, pageCount = 20)

		val point = history.resume(manga.id) as ResumePoint.Open

		assertEquals(manga.chapters!![2].id, point.chapter.id, "should advance to the next chapter")
		assertEquals(0, point.page, "a new chapter starts at page one")
		assertFalse(point.isCaughtUp)
	}

	@Test
	@DisplayName("finishing the final chapter reopens where it stopped, and says so")
	fun finalChapterIsCaughtUp() = runTest {
		val manga = manga(chapters = 3)
		reader.savePosition(manga, manga.chapters!![2], page = 19, scroll = 0f, percent = 1f, pageCount = 20)

		val point = history.resume(manga.id) as ResumePoint.Open

		assertEquals(manga.chapters!![2].id, point.chapter.id, "there is nothing after the last chapter")
		assertEquals(19, point.page, "reopen where they stopped rather than restarting the chapter")
		assertTrue(point.isCaughtUp)
	}

	/**
	 * A row with no page count cannot know whether the chapter was finished, and guessing wrong
	 * skips a chapter the user had not read. Imported Android history is all of these.
	 */
	@Test
	@DisplayName("an unknown page count resumes the saved page rather than guessing")
	fun unknownPageCountDoesNotAdvance() = runTest {
		val manga = manga(chapters = 3)
		reader.savePosition(manga, manga.chapters!![0], page = 19, scroll = 0f, percent = 0.3f, pageCount = 0)

		val point = history.resume(manga.id) as ResumePoint.Open

		assertEquals(manga.chapters!![0].id, point.chapter.id)
		assertEquals(19, point.page)
	}

	/**
	 * Chapter order only means anything inside a branch. Advancing across branches would follow
	 * one scanlation group's chapter 5 with a different group's chapter 1.
	 */
	@Test
	@DisplayName("the next chapter is the next one on the same branch")
	fun advanceStaysOnBranch() = runTest {
		val chapters = listOf(
			chapter(1, "en", 1f),
			chapter(2, "fr", 1f),
			chapter(3, "en", 2f),
			chapter(4, "fr", 2f),
		)
		val manga = manga(chapters = 0).copy(chapters = chapters)
		reader.savePosition(manga, chapters[0], page = 9, scroll = 0f, percent = 0.5f, pageCount = 10)

		val point = history.resume(manga.id) as ResumePoint.Open

		assertEquals(3L, point.chapter.id, "should follow the English branch, not the French one")
	}

	// ------------------------------------------------------------------ degraded states

	@Test
	@DisplayName("an entry whose source is gone stays in the list, marked unavailable")
	fun missingSourceSurvives() = runTest {
		val manga = manga(chapters = 2).copy(sourceName = "SOME_RETIRED_SITE")
		reader.savePosition(manga, manga.chapters!![0], page = 1, scroll = 0f, percent = 0.1f, pageCount = 10)

		val entries = history.observeAll().first()

		assertEquals(1, entries.size, "the entry must not disappear with its source")
		assertFalse(entries.single().isSourceAvailable)
		// The raw name, since there is no descriptor to supply a nicer one. Never blank.
		assertEquals("SOME_RETIRED_SITE", entries.single().sourceTitle)
		assertTrue(history.resume(manga.id) is ResumePoint.SourceUnavailable)
	}

	/**
	 * History restored from an Android backup carries positions and no chapters. The entry must
	 * still open something -- the details screen, so a source can supply the list.
	 */
	@Test
	@DisplayName("a position with no stored chapters asks the source for them")
	fun noChaptersNeedsDetails() = runTest {
		val manga = manga(chapters = 0)
		reader.savePosition(manga, chapter(99, null, 1f), page = 3, scroll = 0f, percent = 0.2f, pageCount = 10)

		assertTrue(history.resume(manga.id) is ResumePoint.NeedsChapters)
	}

	@Test
	fun `nothing read means nothing to resume`() = runTest {
		assertNull(history.resume(mangaId = 404L))
	}

	// ------------------------------------------------------------------ the list itself

	@Test
	@DisplayName("the list names the last chapter read, from stored rows")
	fun listNamesTheChapter() = runTest {
		val manga = manga(chapters = 3)
		reader.savePosition(manga, manga.chapters!![1], page = 2, scroll = 0f, percent = 0.4f, pageCount = 20)

		val entry = history.observeAll().first().single()

		assertEquals(2f, entry.chapterNumber)
		assertEquals("Part 2", entry.chapterName)
		assertEquals("Chapter 2 — Part 2", entry.chapterLabel)
		assertTrue(entry.isSourceAvailable)
	}

	@Test
	@DisplayName("most recent first")
	fun orderedByLastRead() = runTest {
		val older = manga(id = 1L, title = "Older", chapters = 1)
		val newer = manga(id = 2L, title = "Newer", chapters = 1)
		reader.savePosition(older, older.chapters!![0], 0, 0f, 0f, 10, now = 1_000L)
		reader.savePosition(newer, newer.chapters!![0], 0, 0f, 0f, 10, now = 2_000L)

		assertEquals(listOf("Newer", "Older"), history.observeAll().first().map { it.manga.title })
	}

	@Test
	fun `the quick search filters by title and by alternative title`() = runTest {
		val entries = listOf(
			entryFor(manga(id = 1L, title = "Berserk", chapters = 1)),
			entryFor(manga(id = 2L, title = "Vinland Saga", chapters = 1).copy(altTitles = setOf("ヴィンランド・サガ"))),
		)

		assertEquals(1, history.filter(entries, "berserk").size, "matching is case-insensitive")
		assertEquals(1, history.filter(entries, "ヴィンランド").size, "alternative titles match too")
		assertEquals(2, history.filter(entries, "  ").size, "a blank query filters nothing")
		assertTrue(history.filter(entries, "nothing here").isEmpty())
	}

	// ------------------------------------------------------------------ the list is live

	/**
	 * The reading list follows the reader without being told.
	 *
	 * This is the behaviour the Continue screen and the library banner both rest on, and it is not
	 * free: it holds only because [HistoryRepository.observeAll] is a Room `Flow` query and because
	 * the reader's position writes land in the very tables that query reads -- `history` for the
	 * position, `chapters` for the name it prints. A `suspend` read behind a manual refresh would
	 * compile identically and leave the screen showing yesterday's chapter.
	 *
	 * One subscription, opened before anything has been read and never reopened, so a query that
	 * only happened to be run again cannot pass this. Duplicates are collapsed rather than
	 * asserted on: Room is allowed to invalidate more often than the data actually changes, and
	 * pinning the emission count would make this a test of Room's scheduler.
	 */
	@Test
	@DisplayName("reading a chapter updates the list already on screen")
	fun theListIsLive() = runTest {
		val manga = manga(chapters = 3)
		val seen = CopyOnWriteArrayList<String?>()
		val watching = launch(Dispatchers.Default) {
			history.observeAll().collect { entries -> seen += entries.singleOrNull()?.chapterLabel }
		}
		await("the list to start out empty") { seen.isNotEmpty() }

		reader.savePosition(manga, manga.chapters!![0], page = 4, scroll = 0f, percent = 0.1f, pageCount = 20)
		await("chapter 1 to appear") { seen.last() == "Chapter 1 — Part 1" }

		reader.savePosition(manga, manga.chapters!![1], page = 4, scroll = 0f, percent = 0.4f, pageCount = 20)
		await("chapter 2 to replace it") { seen.last() == "Chapter 2 — Part 2" }

		watching.cancel()
		assertEquals(
			listOf(null, "Chapter 1 — Part 1", "Chapter 2 — Part 2"),
			seen.distinct(),
			"the list should start empty and then follow the reader, chapter by chapter",
		)
	}

	@Test
	@DisplayName("reading reorders the list under whoever is looking at it")
	fun readingReordersTheList() = runTest {
		val first = manga(id = 1L, title = "First", chapters = 2)
		val second = manga(id = 2L, title = "Second", chapters = 2)
		reader.savePosition(first, first.chapters!![0], 0, 0f, 0.1f, 20, now = 1_000L)
		reader.savePosition(second, second.chapters!![0], 0, 0f, 0.1f, 20, now = 2_000L)

		val seen = CopyOnWriteArrayList<List<String>>()
		val watching = launch(Dispatchers.Default) {
			history.observeAll().collect { entries -> seen += entries.map { it.manga.title } }
		}
		await("the list to arrive") { seen.isNotEmpty() }
		assertEquals(listOf("Second", "First"), seen.first(), "most recently read first")

		// Pick "First" back up. It is now the most recent thing read.
		reader.savePosition(first, first.chapters!![1], 0, 0f, 0.6f, 20, now = 3_000L)

		await("the order to follow") { seen.last() == listOf("First", "Second") }
		watching.cancel()
	}

	// ------------------------------------------------------------------ marking read and unread

	@Test
	@DisplayName("marking a chapter read marks everything before it too")
	fun markReadIsAPrefix() = runTest {
		val manga = manga(chapters = 5)

		history.markReadThrough(manga, manga.chapters!![2])

		val marker = history.observeMarker(manga.id, manga.chapters!!).first()!!
		assertEquals(2, marker.index)
		assertTrue(marker.isFinished)
		assertEquals(ChapterReadState.READ, marker.stateOf(0))
		assertEquals(ChapterReadState.READ, marker.stateOf(2), "the chapter clicked is read too")
		assertEquals(ChapterReadState.UNREAD, marker.stateOf(3))
	}

	/**
	 * The rule that makes marking read useful rather than merely decorative: the position it
	 * writes has to be one `resume` reads as *finished*, or Continue Reading would reopen the
	 * chapter that was just marked done.
	 */
	@Test
	@DisplayName("marking a chapter read makes the next one the thing to continue with")
	fun markReadAdvancesResume() = runTest {
		val manga = manga(chapters = 5)

		history.markReadThrough(manga, manga.chapters!![2])

		val point = history.resume(manga.id) as ResumePoint.Open
		assertEquals(manga.chapters!![3].id, point.chapter.id)
		assertEquals(0, point.page)
	}

	@Test
	@DisplayName("marking a chapter unread marks everything after it too")
	fun markUnreadIsASuffix() = runTest {
		val manga = manga(chapters = 5)
		history.markReadThrough(manga, manga.chapters!![4])

		history.markUnreadFrom(manga, manga.chapters!![2])

		val marker = history.observeMarker(manga.id, manga.chapters!!).first()!!
		assertEquals(1, marker.index, "the position falls back to the chapter before the one clicked")
		assertEquals(ChapterReadState.READ, marker.stateOf(1))
		assertEquals(ChapterReadState.UNREAD, marker.stateOf(2))
		assertEquals(manga.chapters!![2].id, (history.resume(manga.id) as ResumePoint.Open).chapter.id)
	}

	@Test
	@DisplayName("marking the first chapter unread means nothing has been read")
	fun markUnreadFromTheStartClearsTheEntry() = runTest {
		val manga = manga(chapters = 3)
		history.markReadThrough(manga, manga.chapters!![2])

		history.markUnreadFrom(manga, manga.chapters!![0])

		assertNull(history.observeMarker(manga.id, manga.chapters!!).first())
		assertTrue(history.observeAll().first().isEmpty())
		assertEquals(1, db.historyDao().countIncludingDeleted(), "soft, like every other removal")
	}

	/**
	 * Marking read is branch-local for the same reason resuming is: chapter order only means
	 * anything within one scanlation branch.
	 */
	@Test
	@DisplayName("marking read on one branch says nothing about the other")
	fun markReadStaysOnBranch() = runTest {
		val chapters = listOf(
			chapter(1, "en", 1f),
			chapter(2, "fr", 1f),
			chapter(3, "en", 2f),
			chapter(4, "fr", 2f),
		)
		val manga = manga(chapters = 0).copy(chapters = chapters)

		history.markReadThrough(manga, chapters[2])

		val english = history.observeMarker(manga.id, listOf(chapters[0], chapters[2])).first()!!
		assertEquals(1, english.index)
		assertEquals(ChapterReadState.READ, english.stateOf(0))

		val french = history.observeMarker(manga.id, listOf(chapters[1], chapters[3])).first()!!
		assertEquals(-1, french.index, "the position is not on this branch at all")
		assertEquals(ChapterReadState.UNREAD, french.stateOf(0), "so nothing here is read")
	}

	/**
	 * A position with no page count cannot claim the chapter was finished. Rendering it as read
	 * would be a completion nothing recorded; "reading" is what the row actually says.
	 */
	@Test
	@DisplayName("a chapter stopped part-way through reads as in progress, not as read")
	fun partWayThroughIsReading() = runTest {
		val manga = manga(chapters = 3)
		reader.savePosition(manga, manga.chapters!![1], page = 5, scroll = 0f, percent = 0.4f, pageCount = 20)

		val marker = history.observeMarker(manga.id, manga.chapters!!).first()!!
		assertEquals(ChapterReadState.READ, marker.stateOf(0))
		assertEquals(ChapterReadState.READING, marker.stateOf(1))
		assertEquals(ChapterReadState.UNREAD, marker.stateOf(2))
	}

	// ------------------------------------------------------------------ marking from the grid

	@Test
	@DisplayName("marking a whole title read points at the last chapter stored for it")
	fun markAllReadUsesStoredChapters() = runTest {
		val manga = manga(chapters = 4)
		// Reading anything is what puts the chapter rows in the database in the first place.
		reader.savePosition(manga, manga.chapters!![0], 0, 0f, 0.1f, 20)

		assertTrue(history.markAllRead(manga.id))

		val entry = history.observeAll().first().single()
		assertEquals(1f, entry.progressPercent)
		assertTrue(entry.isCaughtUp)
		assertEquals(manga.chapters!![3].id, (history.resume(manga.id) as ResumePoint.Open).chapter.id)
	}

	@Test
	@DisplayName("marking a whole title unread clears it from the list")
	fun markAllUnreadClears() = runTest {
		val manga = manga(chapters = 2)
		reader.savePosition(manga, manga.chapters!![0], 0, 0f, 0.1f, 20)

		history.markAllUnread(manga.id)

		assertTrue(history.observeAll().first().isEmpty())
	}

	/**
	 * A freshly imported library has manga rows and no chapter rows. There is nothing to point a
	 * position at, and the caller is told so rather than left with a menu item that did nothing.
	 */
	@Test
	@DisplayName("a title with no chapters stored anywhere cannot be marked read")
	fun markAllReadNeedsSomethingToPointAt() = runTest {
		assertFalse(history.markAllRead(mangaId = 404L), "not even a manga row exists")
	}

	// ------------------------------------------------------------------ removal

	@Test
	@DisplayName("removing one entry is soft, so a sync cannot resurrect it")
	fun removeIsSoft() = runTest {
		val manga = manga(chapters = 1)
		reader.savePosition(manga, manga.chapters!![0], 0, 0f, 0f, 10)

		history.remove(manga.id)

		assertTrue(history.observeAll().first().isEmpty())
		assertEquals(1, db.historyDao().countIncludingDeleted(), "the tombstone stays")
	}

	@Test
	@DisplayName("clear-all reports how many it cleared")
	fun clearAllCounts() = runTest {
		for (id in 1L..3L) {
			val manga = manga(id = id, title = "Title $id", chapters = 1)
			reader.savePosition(manga, manga.chapters!![0], 0, 0f, 0f, 10)
		}

		assertEquals(3, history.clearAll())
		assertTrue(history.observeAll().first().isEmpty())
		assertEquals(0, history.clearAll(), "a second clear has nothing left to do")
	}

	// ------------------------------------------------------------------ labels

	@Test
	@DisplayName("a source that names its chapters after their number does not say it twice")
	fun redundantChapterNameIsDropped() {
		assertEquals("Chapter 2", label(number = 2f, name = "Chapter 2"))
	}

	@Test
	fun `a whole chapter number does not print a decimal point`() {
		assertEquals("Chapter 12", label(number = 12f, name = null))
		assertEquals("Chapter 12.5", label(number = 12.5f, name = null))
		assertEquals("A Name", label(number = null, name = "A Name"))
		assertEquals("Chapter 3 — A Name", label(number = 3f, name = "A Name"))
		assertNull(label(number = null, name = null), "a source that says nothing gets no label")
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Wait for a live query to catch up, in real time.
	 *
	 * Room delivers invalidations on its own dispatcher and a real database write is a real write,
	 * so the virtual clock `runTest` provides cannot be advanced past them -- there is nothing on
	 * its scheduler to advance. Polling on a real dispatcher is what is left, and it is honest
	 * about what it is: the alternative, sprinkling `runCurrent()` and hoping, produced a test that
	 * passed or failed depending on which thread got there first.
	 */
	private suspend fun await(what: String, predicate: () -> Boolean) {
		val settled = withContext(Dispatchers.Default) {
			withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
				while (!predicate()) delay(AWAIT_POLL_MS)
				true
			}
		}
		if (settled == null) fail<Unit>("timed out waiting for $what")
	}


	private fun label(number: Float?, name: String?): String? = ContinueEntry(
		manga = manga(chapters = 0),
		sourceTitle = "MangaDex",
		isSourceAvailable = true,
		lastReadAt = 0L,
		progressPercent = null,
		chapterNumber = number,
		chapterName = name,
	).chapterLabel

	private fun entryFor(manga: AgehaManga) = ContinueEntry(
		manga = manga,
		sourceTitle = "MangaDex",
		isSourceAvailable = true,
		lastReadAt = 0L,
		progressPercent = null,
		chapterNumber = null,
		chapterName = null,
	)

	private fun chapter(id: Long, branch: String?, number: Float) = AgehaChapter(
		id = id,
		title = "Part ${number.toInt()}",
		number = number,
		volume = null,
		url = "/c/$id",
		scanlator = null,
		uploadDate = null,
		branch = branch,
		sourceName = "MANGADEX",
	)

	private fun manga(id: Long = 1L, title: String = "A Manga", chapters: Int) = AgehaManga(
		id = id,
		title = title,
		altTitles = emptySet(),
		url = "/m/$id",
		publicUrl = "https://example.test/m/$id",
		rating = null,
		contentRating = null,
		coverUrl = null,
		largeCoverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		description = null,
		chapters = (1..chapters).map { chapter(id * 100 + it, null, it.toFloat()) }.takeIf { it.isNotEmpty() },
		sourceName = "MANGADEX",
	)

	private companion object {
		/** Generous: it is a ceiling on a hang, not a measurement of how fast Room is. */
		const val AWAIT_TIMEOUT_MS = 5_000L
		const val AWAIT_POLL_MS = 5L
	}
}
