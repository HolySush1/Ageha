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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
}
