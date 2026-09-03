package app.ageha.core.parsers

/**
 * What an update check concluded.
 *
 * Every outcome is something the UI can say out loud. That matters most for [Rejected]: a build
 * that Ageha cannot run is a normal event, not a crash, and the user needs to hear that their
 * sources still work and that the fix is an app update rather than anything they did.
 */
sealed interface UpdateOutcome {

	/** Already running the newest build. */
	data class UpToDate(val version: String) : UpdateOutcome

	/** The user fixed the version deliberately, so nothing was fetched. */
	data class Pinned(val version: String) : UpdateOutcome

	/** Fetched, gated and staged. Not live until `activate` is called. */
	data class Ready(val version: String, val sourceCount: Int, val sha256: String) : UpdateOutcome

	/**
	 * The build loaded but Ageha cannot run it. The active build is untouched and this version is
	 * remembered so it is never fetched again.
	 */
	data class Rejected(
		val version: String,
		val reason: String,
		val userMessage: String,
	) : UpdateOutcome

	/** Refused on an earlier run. Reported rather than silently skipped, so the state is visible. */
	data class PreviouslyRejected(val version: String, val reason: String) : UpdateOutcome

	/**
	 * The check itself did not complete -- network down, GitHub unreachable, JitPack still
	 * building. Distinct from [Rejected]: nothing is known about the build, so it will be tried
	 * again rather than remembered as bad.
	 */
	data class CheckFailed(val reason: String) : UpdateOutcome
}
