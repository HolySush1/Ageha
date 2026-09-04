package app.ageha.feature.settings

/**
 * How Ageha behaves about its own updates.
 *
 * The brief asks for this as a three-way toggle, and the three options here mean genuinely
 * different things -- but **none of them controls installation**, and pretending otherwise would
 * be the easy mistake to make.
 *
 * Ageha is packaged by Hydraulic Conveyor, and on every platform the *installer* owns updating:
 * MSIX on Windows, the bundle's own updater on macOS, and apt on Linux. All three are configured
 * when the package is built and none exposes a runtime switch, so a settings toggle claiming to
 * turn automatic installation on and off would be a lie told by a checkbox.
 *
 * What Ageha genuinely controls is **whether it looks, and whether it tells you**. That is what
 * these three settings do, and the settings panel says so in as many words.
 */
enum class AppUpdatePolicy(val label: String, val detail: String) {
	AUTOMATIC(
		"Check quietly",
		"Ageha checks at startup and stays silent unless there is something you need to do. " +
			"Your installer applies updates on its own.",
	),
	NOTIFY(
		"Check and tell me",
		"Ageha checks at startup and shows a notice whenever a newer version is out.",
	),
	MANUAL(
		"Never check",
		"Ageha does not contact GitHub at all. Use the button below when you want to look.",
	),
}

/**
 * What the app-update row is showing.
 *
 * The outcome arrives as text rather than as a type because the type that produces it lives in
 * `:app:desktop` -- this module renders settings, it does not know how the application discovers
 * its own releases.
 */
data class AppUpdatesUiState(
	val policy: AppUpdatePolicy = AppUpdatePolicy.AUTOMATIC,
	val currentVersion: String = "",
	val isChecking: Boolean = false,
	/** Last result, already written for a person. Null before anything has been checked. */
	val lastResult: String? = null,
)
