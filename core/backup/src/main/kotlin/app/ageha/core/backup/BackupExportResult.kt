package app.ageha.core.backup

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What an export actually wrote.
 *
 * Counts per section rather than a total, for the same reason [BackupImportResult] names what it
 * skipped: "Backup saved" over an archive holding no favourites is technically true and useless.
 * A user who exports and sees `favourites: 0` when they have two hundred knows immediately that
 * something is wrong, which is a discovery worth making now rather than at restore time.
 */
data class BackupExportResult(
	val file: File,
	/** Milliseconds, as stamped into the archive index. */
	val createdAt: Long,
	val written: Map<BackupSection, Int>,
	val sizeBytes: Long,
) {

	val totalWritten: Int get() = written.values.sum()

	/** A summary written for a person, not a log file. */
	fun describe(): String = buildString {
		appendLine("Wrote " + totalWritten + " item(s) to " + file.name + " (" + humanSize() + "):")
		written.entries.sortedBy { it.key.name }.forEach { (section, count) ->
			appendLine("  " + section.entryName + ": " + count)
		}
		appendLine()
		appendLine("Created " + TIMESTAMP.format(Instant.ofEpochMilli(createdAt)))
		appendLine("This file restores into Ageha and into the Kotatsu-Redo Android app.")
	}.trimEnd()

	/**
	 * Bytes, rounded to something readable.
	 *
	 * Binary units, because that is what every file manager on the three platforms Ageha ships to
	 * reports, and a number that disagrees with the one beside it in Explorer reads as a bug.
	 */
	fun humanSize(): String {
		if (sizeBytes < 1024) return sizeBytes.toString() + " B"
		var value = sizeBytes.toDouble()
		var unit = 0
		while (value >= 1024 && unit < UNITS.lastIndex) {
			value /= 1024
			unit++
		}
		return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit])
	}

	private companion object {
		val UNITS = listOf("B", "KiB", "MiB", "GiB")
		val TIMESTAMP: DateTimeFormatter =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
	}
}

/**
 * The archive could not be written.
 *
 * Distinct from an export that ran: everything this covers happens before or instead of a usable
 * file existing, so there is never a half-archive to explain away.
 */
class BackupExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The name the Android app would give a backup taken now.
 *
 * `kotatsu_20260904-1730.bk.zip` -- upstream's `BackupUtils.generateFileName`, reproduced so that
 * a user with archives from both apps sees one naming convention in one folder, sorted by date
 * because the timestamp is fixed-width and leading. The stem is Ageha's rather than Kotatsu's,
 * since that is the app that wrote it.
 */
fun defaultBackupFileName(now: Long = System.currentTimeMillis()): String =
	"ageha_" + FILE_TIMESTAMP.format(Instant.ofEpochMilli(now)) + ".bk.zip"

private val FILE_TIMESTAMP: DateTimeFormatter =
	DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())
