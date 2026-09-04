package app.ageha.desktop

import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.ReaderBackground
import app.ageha.core.network.AgehaPaths
import app.ageha.feature.settings.AppUpdatePolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The handful of things Ageha remembers between launches, other than the library.
 *
 * A JSON file rather than a table. These are small, they are read exactly once at startup, and
 * they must be readable when the database is not -- the window has to open even if the library is
 * corrupt or locked, otherwise the user has no way to reach the settings that would let them fix
 * it. Room's own preferences table lands with the settings screen in milestone 8, for values that
 * belong with the data rather than with the window.
 */
@Serializable
data class Preferences(
	val theme: AgehaThemeMode = AgehaThemeMode.SYSTEM,
	val readerBackground: ReaderBackground = ReaderBackground.BLACK,
	/**
	 * Two pages side by side in paged mode.
	 *
	 * Off by default. It only helps on a window wide enough for two pages at a readable size, and
	 * a reader who opens Ageha on a laptop and gets two postage stamps will conclude the app is
	 * broken rather than that a setting is wrong.
	 */
	val doublePage: Boolean = false,
	/** Whether the first page stands alone in double-page mode. See PageLayout for why. */
	val coverOffset: Boolean = true,
	/**
	 * How wide the webtoon strip is drawn, as a multiple of the source's own pixel width.
	 *
	 * 1.0 means one image pixel per screen pixel -- what the source actually published, and the
	 * only width that is never an interpolation of somebody else's line art. It is stored rather
	 * than being per-chapter because the first thing a reader on a large monitor does is widen the
	 * strip, and having to do that again at every chapter break would make the control feel broken.
	 *
	 * Clamped where it is used, not here: the strip is also capped to the window, so the same
	 * stored number gives a sensible width on a laptop and on a 27-inch display.
	 */
	val webtoonZoom: Float = 1f,
	val window: WindowGeometry = WindowGeometry(),
	val lastCategoryId: Int = -1,
	val minimiseToTray: Boolean = false,
	/**
	 * Run a sync when the window opens, if an account is configured.
	 *
	 * On by default, and harmless when it is not: with no account the engine returns
	 * `NotConfigured` without touching the network. Startup is the one moment a sync is genuinely
	 * wanted -- it is when another device's changes are most likely to be waiting -- and it is
	 * also the moment the user is least interested in being asked.
	 */
	val syncOnStart: Boolean = true,
	/**
	 * Whether Ageha looks for a newer Ageha, and whether it says so.
	 *
	 * Defaults to checking quietly. It does *not* control installation -- the installer owns that
	 * on all three platforms and exposes no runtime switch. See [AppUpdatePolicy].
	 */
	val appUpdatePolicy: AppUpdatePolicy = AppUpdatePolicy.AUTOMATIC,
)

/**
 * Where the window was last time.
 *
 * A desktop app that opens in the middle of the screen at a default size every launch is a desktop
 * app that has not been finished. Position is stored alongside size because on a multi-monitor
 * setup "same size, wrong monitor" is barely better than nothing.
 */
@Serializable
data class WindowGeometry(
	val width: Int = 1280,
	val height: Int = 860,
	val x: Int? = null,
	val y: Int? = null,
	val isMaximized: Boolean = false,
)

/**
 * Reads and writes [Preferences].
 *
 * Writes go through a temporary file and an atomic rename. A half-written preferences file is
 * indistinguishable from a corrupt one, and the cost of getting it wrong is an app that will not
 * start -- so the write either lands whole or does not land.
 */
class PreferencesStore(private val file: File = File(AgehaPaths.dataDir, "preferences.json")) {

	private val json = Json {
		prettyPrint = true
		// A preferences file written by a newer Ageha must not stop an older one from starting.
		// Unknown keys are dropped on read and lost on the next write, which is the right trade
		// for settings -- unlike for a backup, where losing an unknown section loses user data.
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	fun load(): Preferences {
		if (!file.exists()) return Preferences()
		return runCatching { json.decodeFromString<Preferences>(file.readText()) }
			// A corrupt file falls back to defaults rather than refusing to start. The user loses
			// a window size; the alternative is losing the application.
			.getOrElse { Preferences() }
	}

	fun save(preferences: Preferences) {
		runCatching {
			file.parentFile?.mkdirs()
			val temporary = File(file.parentFile, "${file.name}.tmp")
			temporary.writeText(json.encodeToString(preferences))
			if (!temporary.renameTo(file)) {
				// Windows will not rename onto an existing file. Deleting first opens a window
				// where neither file is complete, which is why the temporary is written first and
				// why a failure here leaves the *old* file in place.
				file.delete()
				temporary.renameTo(file)
			}
		}
	}
}
