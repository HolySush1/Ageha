package app.ageha.desktop

import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.ReaderBackground
import app.ageha.core.network.AgehaPaths
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
	val window: WindowGeometry = WindowGeometry(),
	val lastCategoryId: Int = -1,
	val minimiseToTray: Boolean = false,
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
