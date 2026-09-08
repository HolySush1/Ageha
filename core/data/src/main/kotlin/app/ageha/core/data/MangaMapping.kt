package app.ageha.core.data

import app.ageha.core.database.dao.MangaWithHistory
import app.ageha.core.database.entity.ChapterEntity
import app.ageha.core.database.entity.MangaEntity
import app.ageha.core.database.entity.TagEntity
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaContentRating
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaMangaState
import app.ageha.core.model.AgehaTag

/**
 * Between Ageha's domain model and the rows it is stored in.
 *
 * The schema is the Android app's, column for column, because that is what keeps backup import
 * working (docs/ARCHITECTURE.md 1.3). It therefore carries a few shapes the domain model does not
 * share, and each one is a place data can be lost quietly:
 *
 *  - `rating` is a non-null `Float` in the table and nullable in the model. The Android app writes
 *    **-1** for "this source publishes no rating"; reading that as `0f` would claim every unrated
 *    manga is rated zero, which is a different and much worse statement.
 *  - `cover_url` is non-null in the table and nullable in the model, so the empty string stands in.
 *  - `alt_title` and `author` are single columns holding what the model treats as sets. The
 *    Android app stores only the first value; Ageha stores all of them newline-separated, which
 *    the Android app reads back as one long string rather than as nothing.
 */
object MangaMapping {

	/** The Android app's sentinel for "no rating published". */
	const val NO_RATING = -1f

	private const val MULTI_VALUE_SEPARATOR = "\n"

	fun toEntity(manga: AgehaManga): MangaEntity = MangaEntity(
		id = manga.id,
		title = manga.title,
		altTitles = manga.altTitles.takeIf { it.isNotEmpty() }?.joinToString(MULTI_VALUE_SEPARATOR),
		url = manga.url,
		publicUrl = manga.publicUrl,
		rating = manga.rating ?: NO_RATING,
		// `nsfw` predates the finer-grained content_rating column and the Android app still writes
		// both. Deriving it rather than storing a second opinion keeps them from disagreeing.
		isNsfw = manga.contentRating == AgehaContentRating.ADULT,
		contentRating = manga.contentRating?.name,
		coverUrl = manga.coverUrl.orEmpty(),
		largeCoverUrl = manga.largeCoverUrl,
		state = manga.state?.name,
		authors = manga.authors.takeIf { it.isNotEmpty() }?.joinToString(MULTI_VALUE_SEPARATOR),
		source = manga.sourceName,
	)

	fun toModel(entity: MangaEntity, tags: Set<AgehaTag> = emptySet(), chapters: List<AgehaChapter>? = null) =
		AgehaManga(
			id = entity.id,
			title = entity.title,
			altTitles = entity.altTitles.splitMultiValue(),
			url = entity.url,
			publicUrl = entity.publicUrl,
			rating = entity.rating.takeIf { it >= 0f },
			contentRating = entity.contentRating?.let { name ->
				AgehaContentRating.entries.firstOrNull { it.name == name }
			},
			coverUrl = entity.coverUrl.takeIf { it.isNotEmpty() },
			largeCoverUrl = entity.largeCoverUrl,
			tags = tags,
			state = entity.state?.let { name -> AgehaMangaState.entries.firstOrNull { it.name == name } },
			authors = entity.authors.splitMultiValue(),
			// Descriptions are not stored. They are large, they are the part of a manga most
			// likely to change upstream, and nothing in the library view shows them -- the details
			// screen fetches fresh. Persisting them would trade real disk for stale text.
			description = null,
			chapters = chapters,
			sourceName = entity.source,
		)

	fun toEntity(tag: AgehaTag): TagEntity = TagEntity(
		// Tags have no numeric id upstream, so one is derived from the pair that identifies them.
		// It must be stable across runs: `String.hashCode` is specified by the JDK and will not
		// drift, which `Object.hashCode` would.
		id = tagId(tag),
		title = tag.title,
		key = tag.key,
		source = tag.sourceName,
		isPinned = false,
	)

	fun tagId(tag: AgehaTag): Long = "${tag.sourceName}:${tag.key}".hashCode().toLong() and 0xFFFFFFFFL

	fun toModel(entity: TagEntity): AgehaTag =
		AgehaTag(title = entity.title, key = entity.key, sourceName = entity.source)

	fun toEntity(chapter: AgehaChapter, mangaId: Long, index: Int): ChapterEntity = ChapterEntity(
		chapterId = chapter.id,
		mangaId = mangaId,
		title = chapter.title.orEmpty(),
		// The table has no room for "unnumbered". 0 is the Android app's stand-in, and it is
		// distinguishable from a real chapter number because sources number from 1.
		number = chapter.number ?: 0f,
		volume = chapter.volume ?: 0,
		url = chapter.url,
		scanlator = chapter.scanlator,
		uploadDate = chapter.uploadDate ?: 0L,
		branch = chapter.branch,
		source = chapter.sourceName,
		index = index,
	)

	fun toModel(entity: ChapterEntity): AgehaChapter = AgehaChapter(
		id = entity.chapterId,
		title = entity.title.takeIf { it.isNotEmpty() },
		number = entity.number.takeIf { it > 0f },
		volume = entity.volume.takeIf { it > 0 },
		url = entity.url,
		scanlator = entity.scanlator,
		uploadDate = entity.uploadDate.takeIf { it > 0L },
		branch = entity.branch,
		sourceName = entity.source,
	)

	private fun String?.splitMultiValue(): Set<String> =
		this?.split(MULTI_VALUE_SEPARATOR)?.filter { it.isNotBlank() }?.toSet().orEmpty()
}

/**
 * A library entry: a manga, plus what the reader has done with it.
 *
 * [newChapters] is derived rather than stored. The history row records how many chapters existed
 * when the manga was last opened, so anything past that count is new. That is what the unread
 * badge counts, and it needs no per-chapter read table to do it -- which matters because the
 * Android schema does not have one either, so a badge that required one would not survive a
 * backup import.
 */
data class LibraryEntry(
	val manga: AgehaManga,
	val lastReadAt: Long?,
	val progressPercent: Float?,
	val newChapters: Int,
	/**
	 * The number of the chapter last read, when the stored chapter row carries one.
	 *
	 * Null in two ordinary cases: history imported from an Android backup, which brings positions
	 * without chapter rows, and a source that numbers nothing. The card drops the number rather
	 * than printing "Chapter null".
	 */
	val lastChapterNumber: Float? = null,
) {
	val hasBeenRead: Boolean get() = lastReadAt != null

	companion object {
		fun of(row: MangaWithHistory, currentChapterCount: Int?): LibraryEntry = LibraryEntry(
			manga = MangaMapping.toModel(row.manga),
			lastReadAt = row.updatedAt.takeIf { it > 0 },
			progressPercent = row.percent.takeIf { it >= 0f },
			lastChapterNumber = row.chapterNumber?.takeIf { it > 0f },
			// A source that *loses* chapters -- a rescrape, a removed branch -- would otherwise
			// produce a negative badge. Clamped, because "-3 new chapters" is never right.
			newChapters = ((currentChapterCount ?: row.chaptersAtLastRead) - row.chaptersAtLastRead)
				.coerceAtLeast(0),
		)

		fun unread(manga: AgehaManga): LibraryEntry =
			LibraryEntry(manga = manga, lastReadAt = null, progressPercent = null, newChapters = 0)
	}
}
