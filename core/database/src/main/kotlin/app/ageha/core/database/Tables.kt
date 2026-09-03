package app.ageha.core.database

/**
 * Table names, taken verbatim from the Android app's `core/db/Tables.kt`.
 *
 * These are not free choices. Ageha's schema deliberately matches Kotatsu-Redo's so that a backup
 * file exported from the Android app can be imported here, and so the sync protocol stays a
 * possibility rather than a rewrite. A renamed table is a broken import.
 */
internal const val TABLE_MANGA = "manga"
internal const val TABLE_TAGS = "tags"
internal const val TABLE_MANGA_TAGS = "manga_tags"
internal const val TABLE_CHAPTERS = "chapters"
internal const val TABLE_HISTORY = "history"
internal const val TABLE_FAVOURITES = "favourites"
internal const val TABLE_FAVOURITE_CATEGORIES = "favourite_categories"
internal const val TABLE_SOURCES = "sources"

/**
 * Ageha starts where the Android app currently is, rather than at 1.
 *
 * The Android app is on version 28 after 27 hand-written migrations. Replaying that history would
 * mean porting 27 migrations that can never run here -- no Ageha installation has ever had version
 * 1 -- purely to arrive at the same place. Ageha declares 28 as its baseline and its own
 * migrations start at 29.
 *
 * A user whose backup predates 28 upgrades it by opening the Android app, which is that app's job
 * and not ours.
 */
const val AGEHA_DATABASE_VERSION = 28
