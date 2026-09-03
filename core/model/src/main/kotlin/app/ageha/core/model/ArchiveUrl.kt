package app.ageha.core.model

import java.io.File

/**
 * Addressing one page inside a local comic archive.
 *
 * Lives in `:core:model` because two modules that do not otherwise know about each other both
 * need it: `:core:data` builds these urls when it lists an archive, and `:core:image` parses them
 * when Coil asks for the bytes. Duplicating a parser across that boundary is how the two ends of
 * a format quietly stop agreeing.
 *
 * The `!/` separator is the convention JAR urls use for exactly this problem. It is recognisable,
 * and it cannot collide with a path -- `!` is not a path separator on Windows, macOS or Linux.
 */
object ArchiveUrl {

	const val SCHEME = "cbz"

	private const val SEPARATOR = "!/"

	fun of(archive: File, entryName: String): String =
		"$SCHEME://${archive.absolutePath.replace(File.separatorChar, '/')}$SEPARATOR$entryName"

	fun isArchiveUrl(url: String): Boolean = url.startsWith("$SCHEME://")

	/** Splits a url back into its archive file and entry name. Null if it is not one. */
	fun parse(url: String): Pair<File, String>? {
		if (!isArchiveUrl(url)) return null
		val body = url.removePrefix("$SCHEME://")
		// lastIndexOf, not indexOf: an entry name may itself contain "!/" and the *archive path*
		// may not, so the final separator is the real one.
		val separator = body.lastIndexOf(SEPARATOR)
		if (separator < 0) return null
		return File(body.substring(0, separator)) to body.substring(separator + SEPARATOR.length)
	}
}
