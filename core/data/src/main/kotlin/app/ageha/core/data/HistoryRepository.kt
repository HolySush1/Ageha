package app.ageha.core.data

import app.ageha.core.database.dao.HistoryDao
import app.ageha.core.database.dao.MangaDao
import app.ageha.core.database.dao.MangaWithHistory
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
		/** See [observeAll]. Large enough to be "everything" for any real reader. */
		const val HISTORY_LIMIT = 2_000

		/** The library shelf. A row, not a screen -- more than this is just off the edge. */
		const val SHELF_LIMIT = 12
	}
}
