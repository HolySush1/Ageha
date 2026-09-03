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

/** Per-manga reader settings. Named `preferences` to match the Android app's table. */
internal const val TABLE_PREFERENCES = "preferences"

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
/**
 * Version 29 adds the Android app's `preferences` table, which carries per-manga reader mode.
 *
 * Version 30 adds `history.page_count`, which is **Ageha's own** rather than the Android app's --
 * the first place the two schemas diverge. It is additive with a default, so a backup written by
 * the Android app still restores, and a row restored from one simply reports "page count unknown".
 * Continue Reading needs it to tell "stopped on the last page of a chapter" from "stopped in the
 * middle of one"; see the column's own comment.
 *
 * Ageha started at 28 to match the Android schema and moves forward from there by migration
 * rather than by editing 28 in place -- an already-shipped version that changes shape is a
 * version that cannot be migrated *from*. Each of the nine entities still missing lands the same
 * way, with the feature that needs it.
 */
const val AGEHA_DATABASE_VERSION = 30
