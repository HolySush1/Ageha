package app.ageha.core.model

/**
 * Ageha's own manga model.
 *
 * Deliberately *not* a mirror of the parsers library's `Manga`. That type is mid-migration --
 * it still carries a deprecated constructor plus deprecated `author`, `altTitle` and `isNsfw`
 * accessors that upstream will delete (docs/FINDINGS.md 5). Mirroring it field-for-field would
 * spread that migration across every module. Instead the mapping lives in exactly one file,
 * [app.ageha.core.parsers.internal.ParserModelMapper], and only that file breaks when upstream moves.
 */
data class AgehaManga(
	val id: Long,
	val title: String,
	val altTitles: Set<String>,
	/** Source-relative url. Meaningful only to the parser that produced it. */
	val url: String,
	/** Absolute url, ready to open in a browser. */
	val publicUrl: String,
	/** Normalised 0..1, or null when the source publishes no rating. */
	val rating: Float?,
	val contentRating: AgehaContentRating?,
	val coverUrl: String?,
	val largeCoverUrl: String?,
	val tags: Set<AgehaTag>,
	val state: AgehaMangaState?,
	val authors: Set<String>,
	/** May be HTML. */
	val description: String?,
	/** Null until [app.ageha.core.parsers.MangaSourceClient.details] has been called. */
	val chapters: List<AgehaChapter>?,
	/** The [SourceDescriptor.name] this manga came from. A string, never an enum -- see [SourceDescriptor]. */
	val sourceName: String,
) {

	/** Chapters grouped by branch (scanlation group / language), in source order. */
	fun chaptersByBranch(): Map<String?, List<AgehaChapter>> =
		chapters.orEmpty().groupBy { it.branch }
}

data class AgehaChapter(
	val id: Long,
	/** Source-provided title. Null when the source only numbers its chapters. */
	val title: String?,
	/** Chapter number starting at 1, or null if the source does not number chapters. */
	val number: Float?,
	/** Volume number starting at 1, or null if unknown. */
	val volume: Int?,
	val url: String,
	val scanlator: String?,
	/** Epoch millis, or null when the source publishes no date. */
	val uploadDate: Long?,
	/** A group of chapters that overlap -- typically a language or a scanlation group. */
	val branch: String?,
	val sourceName: String,
)

data class AgehaPage(
	val id: Long,
	/**
	 * Source-relative url. May point at an image *or* at an HTML page that still needs
	 * resolving -- always go through [app.ageha.core.parsers.MangaSourceClient.pageUrl].
	 */
	val url: String,
	val preview: String?,
	val sourceName: String,
)

data class AgehaTag(
	val title: String,
	/** Unique within a source. */
	val key: String,
	val sourceName: String,
)

enum class AgehaMangaState {
	ONGOING,
	FINISHED,
	ABANDONED,
	PAUSED,
	UPCOMING,
	RESTRICTED,
	;

	companion object {
		/** Unknown upstream states degrade to null rather than throwing. */
		fun ofOrNull(name: String?): AgehaMangaState? =
			name?.let { n -> entries.firstOrNull { it.name == n } }
	}
}

enum class AgehaContentRating {
	SAFE,
	SUGGESTIVE,
	ADULT,
	;

	companion object {
		fun ofOrNull(name: String?): AgehaContentRating? =
			name?.let { n -> entries.firstOrNull { it.name == n } }
	}
}

/**
 * Mirrors the parsers library's `ContentType`. Kept complete rather than collapsed, because
 * collapsing MANHWA and MANHUA into MANGA would quietly lose the distinction the library, and the
 * user, actually care about. Values the current parsers build knows and we do not degrade to
 * [OTHER] via [of].
 */
enum class AgehaContentType {
	MANGA,
	MANHWA,
	MANHUA,
	HENTAI,
	COMICS,
	NOVEL,
	ONE_SHOT,
	DOUJINSHI,
	IMAGE_SET,
	ARTIST_CG,
	GAME_CG,
	OTHER,
	;

	companion object {
		fun of(name: String?): AgehaContentType =
			name?.let { n -> entries.firstOrNull { it.name == n } } ?: OTHER
	}
}

enum class AgehaSortOrder {
	UPDATED,
	UPDATED_ASC,
	POPULARITY,
	POPULARITY_ASC,
	RATING,
	RATING_ASC,
	NEWEST,
	NEWEST_ASC,
	ALPHABETICAL,
	ALPHABETICAL_DESC,
	RELEVANCE,
	ADDED,
	ADDED_ASC,
	;

	companion object {
		fun ofOrNull(name: String?): AgehaSortOrder? =
			name?.let { n -> entries.firstOrNull { it.name == n } }
	}
}
