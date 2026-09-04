package app.ageha.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

/**
 * A native open dialog.
 *
 * AWT's `FileDialog` rather than Swing's `JFileChooser`, because on Windows and macOS it is the
 * *actual* system dialog -- the one with the user's shortcuts, their recent places and their
 * network drives -- while `JFileChooser` is a Swing lookalike that immediately marks an app as
 * not-quite-native. On Linux both are Swing-drawn, so nothing is lost there either.
 *
 * Compose Desktop has no file picker of its own, and this is the whole of what Ageha needs.
 */
object FilePicker {

	/**
	 * Ask for a file to open.
	 *
	 * @param extensions lower-case, without the dot. The filter is advisory: `FileDialog` honours
	 *   it on some platforms and ignores it on others, so the caller must still validate what it
	 *   gets back rather than trusting the dialog to have filtered.
	 */
	fun openFile(title: String, extensions: Set<String>): File? {
		val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
		dialog.filenameFilter = FilenameFilter { _, name ->
			extensions.isEmpty() || name.substringAfterLast('.', "").lowercase() in extensions
		}
		// Windows ignores FilenameFilter and wants a glob instead. Setting both means the right
		// one applies on each platform and the other is harmless.
		if (extensions.isNotEmpty()) {
			dialog.file = extensions.joinToString(";") { "*.$it" }
		}
		dialog.isVisible = true
		val directory = dialog.directory ?: return null
		val name = dialog.file ?: return null
		return File(directory, name).takeIf { it.isFile }
	}

	/**
	 * Ask where to write a file.
	 *
	 * Returns the chosen path whether or not anything is there yet -- unlike [openFile], which
	 * only returns files that exist. The caller must be prepared to overwrite: `FileDialog.SAVE`
	 * asks the platform's own "replace it?" question on Windows and macOS, so a path coming back
	 * from here has already been confirmed, and asking again in-app would be a second dialog
	 * saying the same thing. Linux's Swing implementation does not ask, which is the same
	 * behaviour every other GTK-era Java app has there.
	 *
	 * @param defaultName pre-filled in the name field. Worth supplying: a save dialog opening on
	 *   an empty name is a dialog the user has to think about.
	 */
	fun saveFile(title: String, defaultName: String): File? {
		val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
		dialog.file = defaultName
		dialog.isVisible = true
		val directory = dialog.directory ?: return null
		val name = dialog.file ?: return null
		return File(directory, name)
	}
}
