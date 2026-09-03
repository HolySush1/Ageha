package app.ageha.core.data

import app.ageha.core.database.dao.MangaWithHistory
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaContentRating
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaMangaState
import app.ageha.core.model.AgehaTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The domain/row mapping, with attention to the places the two shapes disagree.
 *
 * The schema is the Android app's, so it has non-null columns where the model has nullable fields
 * and single columns where the model has sets. Every one of those is somewhere a value can be
 * quietly changed rather than lost, which is worse.
 */
class MangaMappingTest {

	private fun manga(
		rating: Float? = 0.8f,
		altTitles: Set<String> = setOf("Berserk", "ベルセルク"),
		authors: Set<String> = setOf("Kentaro Miura"),
		coverUrl: String? = "https://example.test/cover.jpg",
	) = AgehaManga(
		id = 42,
		title = "Berserk",
		altTitles = altTitles,
		url = "/manga/berserk",
		publicUrl = "https://example.test/manga/berserk",
		rating = rating,
		contentRating = AgehaContentRating.ADULT,
		coverUrl = coverUrl,
		largeCoverUrl = null,
		tags = setOf(AgehaTag("Seinen", "seinen", "test")),
		state = AgehaMangaState.FINISHED,
		authors = authors,
		description = "<p>long</p>",
		chapters = null,
		sourceName = "test",
	)

	@Test
	fun `a manga survives the round trip`() {
		val original = manga()
		val restored = MangaMapping.toModel(MangaMapping.toEntity(original), tags = original.tags)
		assertEquals(original.id, restored.id)
		assertEquals(original.title, restored.title)
		assertEquals(original.url, restored.url)
		assertEquals(original.publicUrl, restored.publicUrl)
		assertEquals(original.rating, restored.rating)
		assertEquals(original.contentRating, restored.contentRating)
		assertEquals(original.state, restored.state)
		assertEquals(original.altTitles, restored.altTitles)
		assertEquals(original.authors, restored.authors)
		assertEquals(original.tags, restored.tags)
		assertEquals(original.sourceName, restored.sourceName)
	}

	/**
	 * The `-1` rating sentinel, which is the trap in this table.
	 *
	 * The column is a non-null `Float` and the Android app writes -1 for "this source publishes no
	 * rating". Mapping that to `0f` would assert every unrated manga is rated zero -- a claim, not
	 * an absence, and one that would sort them to the bottom of a rating-ordered list.
	 */
	@Test
	fun `an absent rating stays absent`() {
		val entity = MangaMapping.toEntity(manga(rating = null))
		assertEquals(MangaMapping.NO_RATING, entity.rating, "the sentinel must be written, not 0")
		assertNull(MangaMapping.toModel(entity).rating, "-1 must read back as no rating")
	}

	@Test
	fun `a genuine zero rating is not confused with an absent one`() {
		val entity = MangaMapping.toEntity(manga(rating = 0f))
		assertEquals(0f, MangaMapping.toModel(entity).rating)
	}

	/** `cover_url` is non-null in the table; the empty string is the stand-in, not `"null"`. */
	@Test
	fun `a missing cover round trips as null`() {
		val entity = MangaMapping.toEntity(manga(coverUrl = null))
		assertEquals("", entity.coverUrl)
		assertNull(MangaMapping.toModel(entity).coverUrl)
	}

	@Test
	fun `empty sets do not become a blank string`() {
		val entity = MangaMapping.toEntity(manga(altTitles = emptySet(), authors = emptySet()))
		assertNull(entity.altTitles)
		assertNull(entity.authors)
		val restored = MangaMapping.toModel(entity)
		assertTrue(restored.altTitles.isEmpty())
		assertTrue(restored.authors.isEmpty())
	}

	/**
	 * Descriptions are deliberately not stored. Asserted so it stays a decision rather than
	 * becoming a bug someone "fixes" without noticing the details screen already refetches.
	 */
	@Test
	fun `descriptions are not persisted`() {
		assertNull(MangaMapping.toModel(MangaMapping.toEntity(manga())).description)
	}

	/**
	 * Tag ids are derived from source and key, and must be stable across runs -- they are foreign
	 * keys. `String.hashCode` is specified by the JDK; an identity hash would change every launch
	 * and orphan every tag link.
	 */
	@Test
	fun `tag ids are stable and source-scoped`() {
		val a = AgehaTag("Seinen", "seinen", "sourceA")
		val b = AgehaTag("Seinen", "seinen", "sourceB")
		assertEquals(MangaMapping.tagId(a), MangaMapping.tagId(AgehaTag("Different label", "seinen", "sourceA")))
		assertTrue(MangaMapping.tagId(a) != MangaMapping.tagId(b), "the same key in two sources is two tags")
		assertTrue(MangaMapping.tagId(a) >= 0, "ids are used as keys and must not be negative")
	}

	@Test
	fun `an unnumbered chapter round trips as unnumbered`() {
		val chapter = AgehaChapter(
			id = 7, title = null, number = null, volume = null,
			url = "/c/7", scanlator = null, uploadDate = null, branch = null, sourceName = "test",
		)
		val restored = MangaMapping.toModel(MangaMapping.toEntity(chapter, mangaId = 42, index = 0))
		assertNull(restored.number)
		assertNull(restored.volume)
		assertNull(restored.title)
		assertNull(restored.uploadDate)
	}

	// ------------------------------------------------------------------ unread counting

	private fun historyRow(chaptersAtLastRead: Int, updatedAt: Long = 1_000L) = MangaWithHistory(
		manga = MangaMapping.toEntity(manga()),
		updatedAt = updatedAt,
		chapterId = 1,
		page = 3,
		pageCount = 0,
		scroll = 0.5f,
		percent = 0.25f,
		chaptersAtLastRead = chaptersAtLastRead,
		chapterName = null,
		chapterNumber = null,
		chapterBranch = null,
		chapterIndex = null,
	)

	@Test
	fun `new chapters are what appeared since the manga was last opened`() {
		val entry = LibraryEntry.of(historyRow(chaptersAtLastRead = 10), currentChapterCount = 13)
		assertEquals(3, entry.newChapters)
		assertTrue(entry.hasBeenRead)
	}

	/**
	 * Sources lose chapters sometimes -- a rescrape, a branch removed, a licence takedown. The
	 * arithmetic then goes negative, and "-3 new chapters" is never a thing to show anyone.
	 */
	@Test
	fun `a source losing chapters does not produce a negative badge`() {
		val entry = LibraryEntry.of(historyRow(chaptersAtLastRead = 20), currentChapterCount = 15)
		assertEquals(0, entry.newChapters)
	}

	@Test
	fun `an unopened manga shows no badge and no progress`() {
		val entry = LibraryEntry.unread(manga())
		assertEquals(0, entry.newChapters)
		assertNull(entry.progressPercent)
		assertTrue(!entry.hasBeenRead)
	}

	@Test
	fun `without a fresh chapter count nothing is claimed to be new`() {
		val entry = LibraryEntry.of(historyRow(chaptersAtLastRead = 10), currentChapterCount = null)
		assertEquals(0, entry.newChapters, "an unknown count must not be guessed at")
	}
}
