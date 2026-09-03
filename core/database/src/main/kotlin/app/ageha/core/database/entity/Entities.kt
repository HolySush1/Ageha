package app.ageha.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import app.ageha.core.database.TABLE_CHAPTERS
import app.ageha.core.database.TABLE_FAVOURITES
import app.ageha.core.database.TABLE_FAVOURITE_CATEGORIES
import app.ageha.core.database.TABLE_HISTORY
import app.ageha.core.database.TABLE_MANGA
import app.ageha.core.database.TABLE_MANGA_TAGS
import app.ageha.core.database.TABLE_PREFERENCES
import app.ageha.core.database.TABLE_SOURCES
import app.ageha.core.database.TABLE_TAGS

/**
 * Column-for-column ports of the Android app's entities at schema version 28.
 *
 * Every column name, type and nullability is copied deliberately, including the parts that would
 * be designed differently from scratch:
 *
 *  - `alt_title` and `author` are comma-joined strings rather than relations, because that is what
 *    the Android app writes and what a backup file contains.
 *  - `rating` uses -1 as its unknown sentinel rather than being nullable.
 *  - `nsfw` is kept alongside the newer `content_rating`, because rows written by older versions
 *    of the Android app still populate it.
 *  - `source` is a string. Upstream reached the same conclusion Ageha did, for its own reasons:
 *    parser source identity is a name, never an ordinal (docs/FINDINGS.md 5).
 *
 * Ageha's domain models do not look like this and should not. The mapping between them lives in
 * one place, the same way the parser mapping does.
 */
@Entity(tableName = TABLE_MANGA)
data class MangaEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "manga_id") val id: Long,
	@ColumnInfo(name = "title") val title: String,
	/** Comma-joined. See the class comment. */
	@ColumnInfo(name = "alt_title") val altTitles: String?,
	@ColumnInfo(name = "url") val url: String,
	@ColumnInfo(name = "public_url") val publicUrl: String,
	/** Normalised 0..1, or -1 when the source publishes no rating. */
	@ColumnInfo(name = "rating") val rating: Float,
	/** Superseded by [contentRating] but still written by older Android versions. */
	@ColumnInfo(name = "nsfw") val isNsfw: Boolean,
	@ColumnInfo(name = "content_rating") val contentRating: String?,
	@ColumnInfo(name = "cover_url") val coverUrl: String,
	@ColumnInfo(name = "large_cover_url") val largeCoverUrl: String?,
	@ColumnInfo(name = "state") val state: String?,
	/** Comma-joined. See the class comment. */
	@ColumnInfo(name = "author") val authors: String?,
	@ColumnInfo(name = "source") val source: String,
)

@Entity(tableName = TABLE_TAGS)
data class TagEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "tag_id") val id: Long,
	@ColumnInfo(name = "title") val title: String,
	@ColumnInfo(name = "key") val key: String,
	@ColumnInfo(name = "source") val source: String,
	@ColumnInfo(name = "pinned") val isPinned: Boolean,
)

@Entity(
	tableName = TABLE_MANGA_TAGS,
	primaryKeys = ["manga_id", "tag_id"],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = TagEntity::class,
			parentColumns = ["tag_id"],
			childColumns = ["tag_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class MangaTagsEntity(
	@ColumnInfo(name = "manga_id", index = true) val mangaId: Long,
	@ColumnInfo(name = "tag_id", index = true) val tagId: Long,
)

@Entity(
	tableName = TABLE_CHAPTERS,
	// Column order matters: it is the Android app's, and it decides which column the implicit
	// primary-key index is usable for. Reversing it would still compile and still work, while
	// quietly producing a different schema from the one backups were written against.
	primaryKeys = ["manga_id", "chapter_id"],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class ChapterEntity(
	@ColumnInfo(name = "chapter_id") val chapterId: Long,
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "name") val title: String,
	@ColumnInfo(name = "number") val number: Float,
	@ColumnInfo(name = "volume") val volume: Int,
	@ColumnInfo(name = "url") val url: String,
	@ColumnInfo(name = "scanlator") val scanlator: String?,
	@ColumnInfo(name = "upload_date") val uploadDate: Long,
	@ColumnInfo(name = "branch") val branch: String?,
	@ColumnInfo(name = "source") val source: String,
	@ColumnInfo(name = "index") val index: Int,
)

@Entity(
	tableName = TABLE_HISTORY,
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class HistoryEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
	@ColumnInfo(name = "chapter_id") val chapterId: Long,
	@ColumnInfo(name = "page") val page: Int,
	/**
	 * How many pages that chapter had, or 0 when it is not known.
	 *
	 * **Ageha's own column; the Android app has no equivalent.** Without it "was the reader on the
	 * final page of this chapter" cannot be answered, and that question is what decides whether
	 * Continue Reading resumes the saved page or opens the next chapter at page one. Deriving it
	 * from [percent] was the alternative and it is float arithmetic against a chapter count that
	 * may have changed since -- wrong rarely, and wrong by opening the wrong chapter.
	 *
	 * Additive and defaulted, so a backup written by the Android app restores with 0 here, which
	 * reads as "unknown" and degrades to resuming exactly where the reader stopped.
	 */
	@ColumnInfo(name = "page_count", defaultValue = "0") val pageCount: Int,
	@ColumnInfo(name = "scroll") val scroll: Float,
	@ColumnInfo(name = "percent") val percent: Float,
	/** Soft delete. Zero means not deleted; sync needs the tombstone. */
	@ColumnInfo(name = "deleted_at") val deletedAt: Long,
	@ColumnInfo(name = "chapters") val chaptersCount: Int,
)

@Entity(tableName = TABLE_FAVOURITE_CATEGORIES)
data class FavouriteCategoryEntity(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "category_id") val categoryId: Int,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "sort_key") val sortKey: Int,
	@ColumnInfo(name = "title") val title: String,
	@ColumnInfo(name = "order") val order: String,
	@ColumnInfo(name = "track") val track: Boolean,
	@ColumnInfo(name = "show_in_lib") val isVisibleInLibrary: Boolean,
	@ColumnInfo(name = "deleted_at") val deletedAt: Long,
)

@Entity(
	tableName = TABLE_FAVOURITES,
	primaryKeys = ["manga_id", "category_id"],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = FavouriteCategoryEntity::class,
			parentColumns = ["category_id"],
			childColumns = ["category_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class FavouriteEntity(
	@ColumnInfo(name = "manga_id", index = true) val mangaId: Long,
	@ColumnInfo(name = "category_id", index = true) val categoryId: Int,
	@ColumnInfo(name = "sort_key") val sortKey: Int,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "deleted_at") val deletedAt: Long,
	@ColumnInfo(name = "pinned") val isPinned: Boolean,
)

@Entity(tableName = TABLE_SOURCES)
data class MangaSourceEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "source") val source: String,
	@ColumnInfo(name = "enabled") val isEnabled: Boolean,
	@ColumnInfo(name = "sort_key", index = true) val sortKey: Int,
	@ColumnInfo(name = "added_in") val addedIn: Int,
	@ColumnInfo(name = "used_at") val lastUsedAt: Long,
	@ColumnInfo(name = "pinned") val isPinned: Boolean,
	@ColumnInfo(name = "cf_state") val cfState: Int,
)

/**
 * Per-manga reader settings.
 *
 * A column-for-column port of the Android app's `MangaPrefsEntity`, including the colour-filter
 * fields Ageha does not use yet. Porting the unused columns costs nothing and keeps the schemas
 * identical, which is the whole reason backup import works; adding them later would mean another
 * migration for no gain.
 *
 * `mode` holds the Android app's `ReaderMode` **id**, not an ordinal: STANDARD is 1, WEBTOON is 2,
 * REVERSED is 3, VERTICAL is 4. The ids are deliberately not in declaration order upstream, so
 * writing an ordinal here would silently give a user a different reader mode after a backup
 * round trip.
 */
@Entity(
	tableName = TABLE_PREFERENCES,
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class MangaPrefsEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "mode") val mode: Int,
	@ColumnInfo(name = "cf_brightness") val cfBrightness: Float,
	@ColumnInfo(name = "cf_contrast") val cfContrast: Float,
	@ColumnInfo(name = "cf_invert") val cfInvert: Boolean,
	@ColumnInfo(name = "cf_grayscale") val cfGrayscale: Boolean,
	@ColumnInfo(name = "cf_book") val cfBookEffect: Boolean,
	@ColumnInfo(name = "title_override") val titleOverride: String?,
	@ColumnInfo(name = "cover_override") val coverUrlOverride: String?,
	@ColumnInfo(name = "content_rating_override") val contentRatingOverride: String?,
)
