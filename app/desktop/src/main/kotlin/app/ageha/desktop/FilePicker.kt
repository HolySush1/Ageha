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
}
