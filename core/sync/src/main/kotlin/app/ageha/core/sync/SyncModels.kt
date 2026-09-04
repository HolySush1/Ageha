package app.ageha.core.sync

import app.ageha.core.database.entity.FavouriteCategoryEntity
import app.ageha.core.database.entity.FavouriteEntity
import app.ageha.core.database.entity.HistoryEntity
import app.ageha.core.database.entity.MangaEntity
import app.ageha.core.database.entity.TagEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The kotatsu-syncserver wire format.
 *
 * Ported field for field from the Android app's `sync/data/model` package, which is the only
 * specification that exists -- the server is the other half of that client and nothing else
 * documents it. Where these disagree with the app, the app is right.
 *
 * Two differences from the backup DTOs in `:core:backup` are load-bearing, and blurring either one
 * silently corrupts data:
 *
 *  - **`deleted_at` is present here and absent there.** A backup describes what a library *is*, so
 *    it carries no tombstones. A sync payload describes everything that has *happened*, and the
 *    tombstone is the only thing distinguishing "deleted here" from "never existed here". Drop it
 *    and every other device pushes the row straight back.
 *  - **A manga's id is `manga_id` here and `id` in a backup.** Same value, same column, different
 *    key on the wire. There is no reason for it beyond the two formats having been written at
 *    different times, and no way to discover it except by reading both.
 *
 * As in a backup, `history.page_count` has no field: it is Ageha's own schema-30 column and the
 * protocol predates it. A row arriving from the server therefore reports its page count as
 * unknown, which degrades to resuming the exact saved page. See `HistoryEntity.pageCount`.
 */
@Serializable
data class SyncDto(
	@SerialName("history") val history: List<HistorySyncDto>? = null,
	@SerialName("categories") val categories: List<FavouriteCategorySyncDto>? = null,
	@SerialName("favourites") val favourites: List<FavouriteSyncDto>? = null,
	@SerialName("timestamp") val timestamp: Long,
)

@Serializable
data class MangaTagSyncDto(
	@SerialName("tag_id") val id: Long,
	@SerialName("title") val title: String,
	@SerialName("key") val key: String,
	@SerialName("source") val source: String,
	@SerialName("pinned") val pinned: Boolean = false,
) {
	fun toEntity() = TagEntity(id = id, title = title, key = key, source = source, isPinned = pinned)
}

@Serializable
data class MangaSyncDto(
	/** `manga_id` on this wire, `id` in a backup. See the file comment. */
	@SerialName("manga_id") val id: Long,
	@SerialName("title") val title: String,
	@SerialName("alt_title") val altTitle: String? = null,
	@SerialName("url") val url: String,
	@SerialName("public_url") val publicUrl: String,
	@SerialName("rating") val rating: Float,
	@SerialName("content_rating") val contentRating: String? = null,
	@SerialName("cover_url") val coverUrl: String,
	@SerialName("large_cover_url") val largeCoverUrl: String? = null,
	@SerialName("tags") val tags: Set<MangaTagSyncDto> = emptySet(),
	@SerialName("state") val state: String? = null,
	@SerialName("author") val author: String? = null,
	@SerialName("source") val source: String,
	@SerialName("nsfw") val nsfw: Boolean = false,
) {
	fun toEntity() = MangaEntity(
		id = id,
		title = title,
		altTitles = altTitle,
		url = url,
		publicUrl = publicUrl,
		rating = rating,
		isNsfw = nsfw,
		contentRating = contentRating,
		coverUrl = coverUrl,
		largeCoverUrl = largeCoverUrl,
		state = state,
		authors = author,
		source = source,
	)
}

@Serializable
data class HistorySyncDto(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("updated_at") val updatedAt: Long,
	@SerialName("chapter_id") val chapterId: Long,
	@SerialName("page") val page: Int,
	@SerialName("scroll") val scroll: Float,
	@SerialName("percent") val percent: Float,
	@SerialName("deleted_at") val deletedAt: Long,
	@SerialName("chapters") val chaptersCount: Int,
	@SerialName("manga") val manga: MangaSyncDto,
) {
	fun toEntity() = HistoryEntity(
		mangaId = mangaId,
		createdAt = createdAt,
		updatedAt = updatedAt,
		chapterId = chapterId,
		page = page,
		// No field on the wire. Unknown, which resumes the exact saved page rather than guessing
		// that the reader had finished the chapter.
		pageCount = 0,
		scroll = scroll,
		percent = percent,
		// Carried, unlike a backup import, which forces this to zero. Here it is the payload.
		deletedAt = deletedAt,
		chaptersCount = chaptersCount,
	)
}

@Serializable
data class FavouriteCategorySyncDto(
	@SerialName("category_id") val categoryId: Int,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("title") val title: String,
	@SerialName("order") val order: String,
	@SerialName("track") val track: Boolean,
	@SerialName("show_in_lib") val isVisibleInLibrary: Boolean,
	@SerialName("deleted_at") val deletedAt: Long,
) {
	fun toEntity() = FavouriteCategoryEntity(
		categoryId = categoryId,
		createdAt = createdAt,
		sortKey = sortKey,
		title = title,
		order = order,
		track = track,
		isVisibleInLibrary = isVisibleInLibrary,
		deletedAt = deletedAt,
	)
}

@Serializable
data class FavouriteSyncDto(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("manga") val manga: MangaSyncDto,
	/** Int on this wire, unlike a backup, where the same field is a Long. */
	@SerialName("category_id") val categoryId: Int,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("pinned") val pinned: Boolean,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("deleted_at") val deletedAt: Long,
) {
	fun toEntity() = FavouriteEntity(
		mangaId = mangaId,
		categoryId = categoryId,
		sortKey = sortKey,
		createdAt = createdAt,
		deletedAt = deletedAt,
		isPinned = pinned,
	)
}

/* The other direction: local rows to wire records. */

fun TagEntity.toSyncDto() = MangaTagSyncDto(
	id = id,
	title = title,
	key = key,
	source = source,
	pinned = isPinned,
)

fun MangaEntity.toSyncDto(tags: Set<MangaTagSyncDto>) = MangaSyncDto(
	id = id,
	title = title,
	altTitle = altTitles,
	url = url,
	publicUrl = publicUrl,
	rating = rating,
	contentRating = contentRating,
	coverUrl = coverUrl,
	largeCoverUrl = largeCoverUrl,
	tags = tags,
	state = state,
	author = authors,
	source = source,
	nsfw = isNsfw,
)

fun HistoryEntity.toSyncDto(manga: MangaSyncDto) = HistorySyncDto(
	mangaId = mangaId,
	createdAt = createdAt,
	updatedAt = updatedAt,
	chapterId = chapterId,
	page = page,
	scroll = scroll,
	percent = percent,
	deletedAt = deletedAt,
	chaptersCount = chaptersCount,
	manga = manga,
)

fun FavouriteCategoryEntity.toSyncDto() = FavouriteCategorySyncDto(
	categoryId = categoryId,
	createdAt = createdAt,
	sortKey = sortKey,
	title = title,
	order = order,
	track = track,
	isVisibleInLibrary = isVisibleInLibrary,
	deletedAt = deletedAt,
)

fun FavouriteEntity.toSyncDto(manga: MangaSyncDto) = FavouriteSyncDto(
	mangaId = mangaId,
	manga = manga,
	categoryId = categoryId,
	sortKey = sortKey,
	pinned = isPinned,
	createdAt = createdAt,
	deletedAt = deletedAt,
)
