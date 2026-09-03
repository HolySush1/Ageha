package app.ageha.core.backup

/**
 * What an import actually did.
 *
 * Deliberately detailed. A migration is the moment a user decides whether to trust Ageha with
 * years of reading history, and "Import complete" printed over a silently partial restore is the
 * worst available answer. Everything skipped is named, with a reason.
 */
data class BackupImportResult(
	val index: BackupIndex?,
	val restored: Map<BackupSection, Int>,
	/** Sections present in the archive that this version of Ageha cannot restore yet. */
	val skippedSections: List<BackupSection>,
	/** Sections present that Ageha does not recognise at all -- a newer Android app wrote them. */
	val unknownEntries: List<String>,
	/**
	 * Rows dropped, with a reason. A backup can reference a favourite category its own categories
	 * section never defined; the foreign key would reject that row and abort everything, so it is
	 * better to drop the one row and say so.
	 */
	val droppedRows: List<String>,
) {

	val totalRestored: Int get() = restored.values.sum()

	val isCompletelyClean: Boolean
		get() = skippedSections.isEmpty() && unknownEntries.isEmpty() && droppedRows.isEmpty()

	/** A summary written for a person, not a log file. */
	fun describe(): String = buildString {
		appendLine("Restored " + totalRestored + " item(s):")
		restored.entries.sortedBy { it.key.name }.forEach { (section, count) ->
			appendLine("  " + section.entryName + ": " + count)
		}
		if (skippedSections.isNotEmpty()) {
			appendLine()
			appendLine("Not restored -- Ageha does not support these yet:")
			skippedSections.forEach { appendLine("  " + it.entryName) }
		}
		if (unknownEntries.isNotEmpty()) {
			appendLine()
			appendLine("Not recognised -- written by a newer version of the Android app:")
			unknownEntries.forEach { appendLine("  " + it) }
		}
		if (droppedRows.isNotEmpty()) {
			appendLine()
			appendLine("Skipped " + droppedRows.size + " row(s):")
			droppedRows.take(10).forEach { appendLine("  " + it) }
			if (droppedRows.size > 10) {
				appendLine("  ... and " + (droppedRows.size - 10) + " more")
			}
		}
	}.trimEnd()
}

/** The archive could not be read at all. Distinct from an import that ran and skipped things. */
class BackupImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
