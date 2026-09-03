package app.ageha.core.backup

/**
 * The sections of a Kotatsu-Redo backup archive.
 *
 * A backup is a zip whose entry names are exactly these strings, each holding a JSON document.
 * The names come from the Android app's `BackupSection` enum and are matched case-insensitively,
 * as the Android app does when reading.
 *
 * [supportedByAgeha] marks the sections Ageha can currently restore. The rest are recognised and
 * reported rather than ignored: a user migrating from Android should be told that their bookmarks
 * were left behind, not discover it themselves three weeks later. Each becomes supported when the
 * feature that owns it lands.
 */
enum class BackupSection(val entryName: String, val supportedByAgeha: Boolean) {

	INDEX("index", supportedByAgeha = true),
	HISTORY("history", supportedByAgeha = true),
	CATEGORIES("categories", supportedByAgeha = true),
	FAVOURITES("favourites", supportedByAgeha = true),
	SOURCES("sources", supportedByAgeha = true),

	// Recognised, not yet restorable. Each waits on the feature that owns its table.
	SETTINGS("settings", supportedByAgeha = false),
	SETTINGS_READER_GRID("reader_grid", supportedByAgeha = false),
	BOOKMARKS("bookmarks", supportedByAgeha = false),
	SCROBBLING("scrobbling", supportedByAgeha = false),
	STATISTICS("statistics", supportedByAgeha = false),
	SAVED_FILTERS("saved_filters", supportedByAgeha = false),
	;

	companion object {
		fun of(entryName: String): BackupSection? =
			entries.firstOrNull { it.entryName.equals(entryName, ignoreCase = true) }
	}
}
