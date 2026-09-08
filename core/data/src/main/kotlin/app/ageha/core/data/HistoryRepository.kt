package app.ageha.core.data

import app.ageha.core.database.dao.HistoryDao
import app.ageha.core.database.dao.MangaDao
import app.ageha.core.database.dao.MangaWithHistory
import app.ageha.core.database.entity.HistoryEntity
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One thing the reader has read, as the Continue Reading list shows it.
 *
 * [chapterName] and [chapterNumber] come from the stored chapter row and are null when there
 * isn't one -- history imported from an Android backup carries positions but no chapters. The
 * entry is still shown; "something you read, and we know when" is more useful than nothing.
 */
data class ContinueEntry(
	val manga: AgehaManga,
	/**
	 * The source's display title, or its raw name when the current parsers build has never heard
	 * of it. Never blank: an entry with no readable source is one the user cannot identify.
	 */
	val sourceTitle: String,
	/**
	 * False when the loaded parsers build has no such source.
	 *
	 * This is a normal state, not an error. A parsers downgrade, a pinned older build, or upstream
	 * retiring a site all produce it, and the entry has to survive all three -- the reading history
	 * is the user's, not the source's.
	 */
	val isSourceAvailable: Boolean,
	val lastReadAt: Long,
	val progressPercent: Float?,
	val chapterNumber: Float?,
	val chapterName: String?,
) {

	val mangaId: Long get() = manga.id

	/**
	 * This entry is a file on disk rather than something from a source.
	 *
	 * The distinction matters to the *list*, not just to the plumbing: an archive has exactly one
	 * chapter and no source to ask about it, so there is no chapter list to send anybody to. The
	 * Continue screen sends an ordinary entry to its chapter list and a local one straight back
	 * into the reader, which is the only place a CBZ can go.
	 */
	val isLocalFile: Boolean get() = manga.sourceName == LocalArchive.LOCAL_SOURCE

	/**
	 * Whether this entry has reached the end of everything the source had published.
	 *
	 * Free, and exact. `percent` is `(chapterIndex + pageWithinChapter) / chapterCount`, so it
	 * reaches exactly 1 only on the final page of the final chapter -- which is the definition.
	 * The list uses this to mark entries; [HistoryRepository.resume] re-derives it from the
	 * chapter rows when one is actually opened, because by then the chapter list may have grown.
	 */
	val isCaughtUp: Boolean get() = progressPercent != null && progressPercent >= 1f

	/**
	 * The last chapter read, as one line.
	 *
	 * Sources are inconsistent about this: some number chapters and give no name, some name them
	 * and give no number, a few do both and a few do neither. Each case gets the most it can say
	 * rather than a placeholder like "Chapter ?" that pretends to information.
	 */
	val chapterLabel: String?
		get() {
			val number = chapterNumber?.let { value ->
				val whole = value.toInt()
				// 12.5 is a real chapter number and has to survive; 12.0 must not print as "12.0".
				if (value == whole.toFloat()) "Chapter $whole" else "Chapter $value"
			}
			val name = chapterName?.takeIf { it.isNotBlank() && it != number }
			return when {
				number != null && name != null -> "$number — $name"
				else -> number ?: name
			}
		}
}

/**
 * Where one chapter stands relative to the reader's saved position.
 *
 * Derived, never stored. The Android schema Ageha stays compatible with has no per-chapter read
 * table -- it keeps *one* position per manga -- so read state is a comparison against that
 * position rather than a flag, and marking chapters read moves the position rather than setting
 * forty flags. That is also what upstream does, and it is why marking chapter 50 read and then
 * resuming lands on chapter 51 instead of somewhere the two disagree about.
 *
 * The cost is stated rather than hidden: read state is a prefix. There is no way to express
 * "read 1-10 and 30-40 but not 11-29", and a schema with room to say it would be a schema a
 * backup could not cross.
 */
enum class ChapterReadState {
	/** Behind the position. */
	READ,

	/** The position itself, stopped part-way through. */
	READING,

	/** Ahead of the position. */
	UNREAD,
}

/**
 * The saved position, resolved against the branch currently on screen.
 *
 * [index] is -1 when the position points at a chapter this branch does not contain, which is
 * normal rather than broken: a manga read on one scanlation branch and then viewed on another
 * has no position *here*, and every chapter shown is honestly unread.
 */
data class ReadingMarker(
	val chapterId: Long,
	val index: Int,
	val page: Int,
	/**
	 * The reader reached the end of that chapter.
	 *
	 * Unknowable for a row with no page count -- imported Android history is all of these -- and
	 * false is the safe answer there: it renders the chapter as in progress rather than claiming
	 * a completion nothing recorded.
	 */
	val isFinished: Boolean,
	val percent: Float?,
) {

	/** Where [index] sits, for a chapter at [chapterIndex] in the same list. */
	fun stateOf(chapterIndex: Int): ChapterReadState = when {
		index < 0 || chapterIndex > index -> ChapterReadState.UNREAD
		chapterIndex < index -> ChapterReadState.READ
		isFinished -> ChapterReadState.READ
		else -> ChapterReadState.READING
	}
}

/**
 * What happens when a Continue Reading entry is opened.
 *
 * Three outcomes rather than one, because the interesting cases are the ones where Ageha cannot
 * simply open the reader, and each needs a different screen. Returning null for all of them and
 * letting the UI guess is how an entry ends up doing nothing when clicked.
 */
sealed interface ResumePoint {

	/** Everything is known locally: open the reader here. */
	data class Open(
		val manga: AgehaManga,
		val chapter: AgehaChapter,
		val page: Int,
		/**
		 * The reader finished the last chapter this source published, as far as Ageha knows.
		 *
		 * It still opens -- at the final page, where they stopped -- but the UI says so, because
		 * silently reopening the page someone already finished looks like the app lost their
		 * progress.
		 */
		val isCaughtUp: Boolean,
	) : ResumePoint

	/**
	 * The manga is known but its chapters are not, so the source has to supply them.
	 *
	 * Happens to history restored from an Android backup, which carries reading positions with no
	 * chapter rows behind them. The details screen fetches the list and the reader resumes from
	 * the same history row once a chapter is opened.
	 */
	data class NeedsChapters(val manga: AgehaManga) : ResumePoint

	/**
	 * The source is not in this parsers build, so there is nothing to open.
	 *
	 * The offer is a search of the sources that *are* loaded, by title. That is the only useful
	 * action left, and it is considerably better than an error.
	 */
	data class SourceUnavailable(val manga: AgehaManga, val title: String) : ResumePoint
}

/**
 * Reading history, and everything Continue Reading is built on.
 *
 * This is what replaced the external tracking services (CLAUDE.md 9, docs/ARCHITECTURE.md 7b).
 * The question tracking was going to answer -- *where was I, and what is next* -- is answerable
 * from rows Ageha already writes, so it is answered here: no account, no OAuth, no network, and
 * it works on a plane.
 *
 * Everything on this class is local by construction. There is no code path here that can reach a
 * source, which is what makes the title filter instant and what makes an entry from a missing
 * source render like any other.
 */
class HistoryRepository(
	private val history: HistoryDao,
	private val manga: MangaDao,
	private val sources: SourceRepository,
) {

	/**
	 * Everything read, most recent first.
	 *
	 * Capped rather than unbounded. A history is not a log -- past a few thousand entries it stops
	 * being something anyone scrolls -- and an uncapped query would pull the whole table into
	 * memory on every write to it, since this is a live subscription.
	 */
	fun observeAll(limit: Int = HISTORY_LIMIT): Flow<List<ContinueEntry>> =
		history.observeRecentWithManga(limit).map { rows -> rows.map(::toEntry) }

	/** The shelf on the library screen: the most recent handful. */
	fun observeRecent(limit: Int = SHELF_LIMIT): Flow<List<ContinueEntry>> = observeAll(limit)

	/**
	 * The quick search: filter by title, in memory.
	 *
	 * Deliberately not a SQL `LIKE` behind a debounce. The list is already in memory and already
	 * bounded, so filtering it is free, and a database round trip per keystroke would make the one
	 * search in Ageha that *could* be instant feel like the ones that talk to a website.
	 *
	 * Alternative titles are matched as well as the main one, because somebody who started reading
	 * something under its Japanese title will look for it that way.
	 */
	fun filter(entries: List<ContinueEntry>, query: String): List<ContinueEntry> {
		val needle = query.trim().lowercase()
		if (needle.isEmpty()) return entries
		return entries.filter { entry ->
			entry.manga.title.lowercase().contains(needle) ||
				entry.manga.altTitles.any { it.lowercase().contains(needle) }
		}
	}

	/**
	 * Where opening this entry should land.
	 *
	 * The rule that matters: **finishing a chapter means the next one.** A reader who stopped on
	 * the final page of chapter 5 wants chapter 6 at page one, not chapter 5's last page again --
	 * that is the position they *left*, not the position they want. When there is no chapter 6,
	 * the entry reopens where it was and reports being caught up.
	 *
	 * Every part of this is answered from stored rows. Nothing here contacts a source, so it
	 * behaves identically offline and for a source that is no longer in the parsers build.
	 */
	suspend fun resume(mangaId: Long): ResumePoint? {
		val row = history.find(mangaId) ?: return null
		val entity = manga.find(mangaId) ?: return null
		val stored = manga.chaptersOf(mangaId).map { MangaMapping.toModel(it) }
		val model = MangaMapping.toModel(
			entity = entity,
			tags = manga.tagsOf(mangaId).map { MangaMapping.toModel(it) }.toSet(),
			chapters = stored.takeIf { it.isNotEmpty() },
		)

		if (!isAvailable(entity.source)) {
			return ResumePoint.SourceUnavailable(model, entity.title)
		}

		// The chapter list is missing, or the recorded chapter is not in it -- a rescrape can drop
		// a chapter, and a backup import brings no chapters at all. Either way the source is the
		// only thing that can say what the chapters are now.
		val current = stored.firstOrNull { it.id == row.chapterId }
			?: return ResumePoint.NeedsChapters(model)

		// Branch-local, because chapter *order* is only meaningful within a branch. The chapter
		// after number 5 on one scanlation branch is not the chapter after number 5 on another.
		val branch = stored.filter { it.branch == current.branch }
		val position = branch.indexOfFirst { it.id == current.id }

		// pageCount 0 means "not known" -- an imported row, or one written before this was
		// recorded. Unknown resumes the saved page, which is never wrong, only less clever.
		val finishedChapter = row.pageCount > 0 && row.page >= row.pageCount - 1
		if (!finishedChapter) {
			return ResumePoint.Open(model, current, row.page, isCaughtUp = false)
		}

		val next = branch.getOrNull(position + 1)
		return if (next != null) {
			ResumePoint.Open(model, next, page = 0, isCaughtUp = false)
		} else {
			ResumePoint.Open(model, current, row.page, isCaughtUp = true)
		}
	}

	/**
	 * The saved position for one manga, resolved against [chapters], as a subscription.
	 *
	 * Live rather than fetched once, because marking a chapter read writes the row this reads: a
	 * chapter list that had looked it up at open time would keep drawing yesterday's markers until
	 * it was navigated away from and back.
	 *
	 * [chapters] is the branch currently on screen, so switching branch re-resolves the index
	 * without another query -- the position has not changed, only what it is being compared with.
	 */
	fun observeMarker(mangaId: Long, chapters: List<AgehaChapter>): Flow<ReadingMarker?> =
		history.observe(mangaId).map { row -> row?.let { toMarker(it, chapters) } }

	private fun toMarker(row: HistoryEntity, chapters: List<AgehaChapter>) = ReadingMarker(
		chapterId = row.chapterId,
		index = chapters.indexOfFirst { it.id == row.chapterId },
		page = row.page,
		// The same test `resume` uses, and it has to stay the same test: these two disagreeing
		// would mean a chapter drawn as read that reopens at page one.
		isFinished = row.pageCount > 0 && row.page >= row.pageCount - 1,
		percent = row.percent.takeIf { it > 0f },
	)

	/**
	 * Mark [chapter] and everything before it in its branch as read.
	 *
	 * Moving the position rather than setting flags -- see [ChapterReadState] for why there are no
	 * flags to set. The row is written as *finished*, which is what makes Continue Reading offer
	 * the chapter after this one rather than reopening the one just marked.
	 */
	suspend fun markReadThrough(
		manga: AgehaManga,
		chapter: AgehaChapter,
		now: Long = System.currentTimeMillis(),
	) {
		val branch = manga.chaptersByBranch()[chapter.branch].orEmpty()
		val index = branch.indexOfFirst { it.id == chapter.id }
		if (index < 0) return
		write(manga, chapter, percent = (index + 1).toFloat() / branch.size, now = now)
	}

	/**
	 * Mark [chapter] and everything after it in its branch as unread.
	 *
	 * The mirror of [markReadThrough]: the position moves back to the chapter *before* this one,
	 * marked finished, so this chapter becomes the next thing to read. Un-reading the first
	 * chapter has no earlier chapter to point at and means "none of this has been read", which is
	 * a removal -- soft, exactly like [remove], so a sync cannot resurrect it.
	 */
	suspend fun markUnreadFrom(
		manga: AgehaManga,
		chapter: AgehaChapter,
		now: Long = System.currentTimeMillis(),
	) {
		val branch = manga.chaptersByBranch()[chapter.branch].orEmpty()
		val index = branch.indexOfFirst { it.id == chapter.id }
		if (index < 0) return
		if (index == 0) {
			remove(manga.id, now)
			return
		}
		write(manga, branch[index - 1], percent = index.toFloat() / branch.size, now = now)
	}

	/**
	 * Write a position that stands for "this chapter is finished".
	 *
	 * `page = 0` of `pageCount = 1` is the smallest true statement of that in a schema which
	 * stores a position rather than a completion flag: the last page of a chapter Ageha has never
	 * opened and therefore cannot measure. Opening it later replaces the 1 with the real count.
	 *
	 * Scroll is zeroed. It belongs to a webtoon strip inside a chapter, and marking a chapter read
	 * from a list is not a statement about where in it anybody was.
	 */
	private suspend fun write(manga: AgehaManga, chapter: AgehaChapter, percent: Float, now: Long) {
		HistoryWriter.write(
			mangaDao = this.manga,
			historyDao = history,
			manga = manga,
			chapter = chapter,
			page = 0,
			scroll = 0f,
			percent = percent,
			pageCount = FINISHED_PAGE_COUNT,
			now = now,
		)
	}

	/**
	 * Mark a whole manga read, from a grid where no chapter list is in hand.
	 *
	 * The library grid holds manga rows, not chapters -- the model it renders comes straight out
	 * of the `manga` table and its `chapters` field is null by design, because storing every
	 * chapter of every shelved title to draw a cover would be the wrong trade. So this reads the
	 * chapter rows the reader has already stored rather than asking the caller for a list it does
	 * not have.
	 *
	 * Returns false when there is nothing to point the position at: never opened, and no chapters
	 * ever stored. That is a real state for a freshly imported library, and the caller says so
	 * rather than leaving a right-click that silently did nothing.
	 */
	suspend fun markAllRead(mangaId: Long, now: Long = System.currentTimeMillis()): Boolean {
		manga.find(mangaId) ?: return false
		val stored = manga.chaptersOf(mangaId)
		val existing = history.find(mangaId)
		// The largest branch, for the reason `resume` picks branch-locally: chapter order is only
		// meaningful within one, and the biggest is the one the reader was almost certainly on.
		val last = stored.groupBy { it.branch }.maxByOrNull { it.value.size }?.value?.lastOrNull()
		val chapterId = last?.chapterId ?: existing?.chapterId ?: return false
		history.upsert(
			HistoryEntity(
				mangaId = mangaId,
				createdAt = existing?.createdAt ?: now,
				updatedAt = now,
				chapterId = chapterId,
				page = 0,
				pageCount = FINISHED_PAGE_COUNT,
				scroll = 0f,
				percent = 1f,
				deletedAt = 0,
				chaptersCount = stored.size.takeIf { it > 0 } ?: existing?.chaptersCount ?: 0,
			),
		)
		return true
	}

	/**
	 * Mark a whole manga unread.
	 *
	 * The same soft delete [remove] performs, under the name the menu uses. They are genuinely the
	 * same operation -- "none of this has been read" and "forget that I read this" leave the
	 * database in one state -- and having two implementations of it would be two chances for them
	 * to drift.
	 */
	suspend fun markAllUnread(mangaId: Long, now: Long = System.currentTimeMillis()) = remove(mangaId, now)

	/** Forget one manga. Soft, so a sync cannot resurrect it. */
	suspend fun remove(mangaId: Long, now: Long = System.currentTimeMillis()) {
		history.markDeleted(mangaId, now)
	}

	/** Forget everything. Returns how many entries were cleared, so settings can say. */
	suspend fun clearAll(now: Long = System.currentTimeMillis()): Int = history.markAllDeleted(now)

	private fun toEntry(row: MangaWithHistory): ContinueEntry {
		val available = isAvailable(row.manga.source)
		return ContinueEntry(
			manga = MangaMapping.toModel(row.manga),
			sourceTitle = sourceTitleOf(row.manga.source),
			isSourceAvailable = available,
			lastReadAt = row.updatedAt,
			progressPercent = row.percent.takeIf { it > 0f },
			chapterNumber = row.chapterNumber?.takeIf { it > 0f },
			chapterName = row.chapterName?.takeIf { it.isNotBlank() },
		)
	}

	/**
	 * Whether the loaded parsers build offers this source.
	 *
	 * A local archive is its own case: it is not in the registry and never will be, but a CBZ on
	 * disk is as available as anything gets. Reporting it unavailable would offer to search other
	 * sources for a file the user already has.
	 */
	private fun isAvailable(sourceName: String): Boolean =
		sourceName == LocalArchive.LOCAL_SOURCE || sources.descriptor(sourceName) != null

	private fun sourceTitleOf(sourceName: String): String = when {
		sourceName == LocalArchive.LOCAL_SOURCE -> "Local file"
		else -> sources.descriptor(sourceName)?.title ?: sourceName
	}

	private companion object {
		/** See [write]. A one-page chapter you are on the last page of. */
		const val FINISHED_PAGE_COUNT = 1

		/** See [observeAll]. Large enough to be "everything" for any real reader. */
		const val HISTORY_LIMIT = 2_000

		/** The library shelf. A row, not a screen -- more than this is just off the edge. */
		const val SHELF_LIMIT = 12
	}
}
