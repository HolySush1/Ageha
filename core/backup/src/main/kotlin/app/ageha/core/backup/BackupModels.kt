package app.ageha.core.backup

import app.ageha.core.database.entity.FavouriteCategoryEntity
import app.ageha.core.database.entity.FavouriteEntity
import app.ageha.core.database.entity.HistoryEntity
import app.ageha.core.database.entity.MangaEntity
import app.ageha.core.database.entity.MangaSourceEntity
import app.ageha.core.database.entity.TagEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The JSON shapes inside a Kotatsu-Redo backup archive.
 *
 * These mirror the Android app's `backups/data/model` classes field for field, including the
 * defaults. The defaults are not decoration: a backup written by an older app version simply omits
 * newer fields, and a missing default turns "an old backup" into "a failed import".
 *
 * Two traps worth naming, because both compile fine and both corrupt data:
 *
 *  - `FavouriteBackup.categoryId` is a **Long** in the archive while the database column is an
 *    **Int**. The archive's type is the authority when reading.
 *  - `rating` uses -1 for unknown rather than being nullable, and `percent` uses -1 likewise. A
 *    naive "0 means unknown" reading would mark every unrated manga as rated zero.
 */
@Serializable
data class BackupIndex(
	@SerialName("app_id") val appId: String = "",
	@SerialName("app_version") val appVersion: Int = 0,
	@SerialName("created_at") val createdAt: Long = 0L,
)

@Serializable
data class TagBackup(
	@SerialName("id") val id: Long,
	@SerialName("title") val title: String,
	@SerialName("key") val key: String,
	@SerialName("source") val source: String,
	@SerialName("pinned") val isPinned: Boolean = false,
) {
	fun toEntity() = TagEntity(
		id = id,
		title = title,
		key = key,
		source = source,
		isPinned = isPinned,
	)
}

@Serializable
data class MangaBackup(
	@SerialName("id") val id: Long,
	@SerialName("title") val title: String,
	@SerialName("alt_title") val altTitles: String? = null,
	@SerialName("url") val url: String,
	@SerialName("public_url") val publicUrl: String,
	@SerialName("rating") val rating: Float = RATING_UNKNOWN,
	@SerialName("nsfw") val isNsfw: Boolean = false,
	@SerialName("content_rating") val contentRating: String? = null,
	@SerialName("cover_url") val coverUrl: String,
	@SerialName("large_cover_url") val largeCoverUrl: String? = null,
	@SerialName("state") val state: String? = null,
	@SerialName("author") val authors: String? = null,
	@SerialName("source") val source: String,
	@SerialName("tags") val tags: Set<TagBackup> = emptySet(),
) {
	fun toEntity() = MangaEntity(
		id = id,
		title = title,
		altTitles = altTitles,
		url = url,
		publicUrl = publicUrl,
		rating = rating,
		isNsfw = isNsfw,
		contentRating = contentRating,
		coverUrl = coverUrl,
		largeCoverUrl = largeCoverUrl,
		state = state,
		authors = authors,
		source = source,
	)
}

@Serializable
data class HistoryBackup(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("updated_at") val updatedAt: Long,
	@SerialName("chapter_id") val chapterId: Long,
	@SerialName("page") val page: Int,
	@SerialName("scroll") val scroll: Float,
	@SerialName("percent") val percent: Float = PROGRESS_NONE,
	@SerialName("chapters") val chaptersCount: Int = 0,
	@SerialName("manga") val manga: MangaBackup,
) {
	fun toEntity() = HistoryEntity(
		mangaId = mangaId,
		createdAt = createdAt,
		updatedAt = updatedAt,
		chapterId = chapterId,
		page = page,
		scroll = scroll,
		percent = percent,
		// An imported row is present, not deleted. Carrying a tombstone in from a backup would
		// hide the entry the user was trying to restore.
		deletedAt = 0L,
		chaptersCount = chaptersCount,
	)
}

@Serializable
data class CategoryBackup(
	@SerialName("category_id") val categoryId: Int,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("title") val title: String,
	@SerialName("order") val order: String = "NEWEST",
	@SerialName("track") val track: Boolean = true,
	@SerialName("show_in_lib") val isVisibleInLibrary: Boolean = true,
) {
	fun toEntity() = FavouriteCategoryEntity(
		categoryId = categoryId,
		createdAt = createdAt,
		sortKey = sortKey,
		title = title,
		order = order,
		track = track,
		isVisibleInLibrary = isVisibleInLibrary,
		deletedAt = 0L,
	)
}

@Serializable
data class FavouriteBackup(
	@SerialName("manga_id") val mangaId: Long,
	/** Long in the archive, Int in the column. The archive is the authority when reading. */
	@SerialName("category_id") val categoryId: Long,
	@SerialName("sort_key") val sortKey: Int = 0,
	@SerialName("pinned") val isPinned: Boolean = false,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("manga") val manga: MangaBackup,
) {
	fun toEntity() = FavouriteEntity(
		mangaId = mangaId,
		categoryId = categoryId.toInt(),
		sortKey = sortKey,
		createdAt = createdAt,
		deletedAt = 0L,
		isPinned = isPinned,
	)
}

@Serializable
data class SourceBackup(
	@SerialName("source") val source: String,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("used_at") val lastUsedAt: Long,
	@SerialName("added_in") val addedIn: Int,
	@SerialName("pinned") val isPinned: Boolean = false,
	@SerialName("enabled") val isEnabled: Boolean = true,
) {
	fun toEntity() = MangaSourceEntity(
		source = source,
		isEnabled = isEnabled,
		sortKey = sortKey,
		addedIn = addedIn,
		lastUsedAt = lastUsedAt,
		isPinned = isPinned,
		// Cloudflare state is per-installation and per-user-agent; importing another device's
		// would be meaningless here and possibly wrong.
		cfState = 0,
	)
}

/** The archive's sentinel for "this source publishes no rating". Not null, and not zero. */
const val RATING_UNKNOWN = -1f

/** The archive's sentinel for "reading progress unknown". */
const val PROGRESS_NONE = -1f
