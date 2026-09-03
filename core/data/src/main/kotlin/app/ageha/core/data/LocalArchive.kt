package app.ageha.core.data

import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaPage
import app.ageha.core.model.ArchiveUrl
import java.io.File
import java.util.zip.ZipFile

/**
 * Reading a comic archive from disk.
 *
 * **CBZ works; CBR does not, and that is a deliberate gap.** A `.cbz` is a zip and the JDK opens
 * it with no dependency at all. A `.cbr` is a RAR, a proprietary format with no JDK support, so
 * it needs a third-party library -- and the ones that exist are either GPL-incompatible or
 * unmaintained. Silently failing on a `.cbr` would be worse than saying so: [isSupported] and
 * [unsupportedReason] exist so the UI can tell the user their file is a RAR and suggest
 * repackaging it, rather than showing "could not open archive".
 *
 * Entries are read straight out of the zip rather than extracted to a temporary directory. A
 * chapter is often 30-50MB and extracting would double the disk cost of opening one, leave
 * temporary files behind on a crash, and buy nothing -- a zip's central directory makes any entry
 * directly addressable.
 */
object LocalArchive {

	private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

	private val SUPPORTED_EXTENSIONS = setOf("cbz", "zip")

	private val KNOWN_UNSUPPORTED = mapOf(
		"cbr" to "CBR archives are RAR files, which Ageha cannot open. Repackaging as CBZ works.",
		"rar" to "RAR archives are not supported. Repackaging as ZIP or CBZ works.",
		"cb7" to "CB7 archives are 7-Zip files, which Ageha cannot open.",
		"cbt" to "CBT archives are tar files, which Ageha cannot open yet.",
	)

	fun isSupported(file: File): Boolean = file.extension.lowercase() in SUPPORTED_EXTENSIONS

	/** Why a file cannot be read, phrased for a user. Null when it can. */
	fun unsupportedReason(file: File): String? = when {
		isSupported(file) -> null
		else -> KNOWN_UNSUPPORTED[file.extension.lowercase()]
			?: "Ageha does not recognise .${file.extension} as a comic archive."
	}

	/**
	 * The pages in an archive, in reading order.
	 *
	 * Ordering is the part that matters. Archive entries come back in whatever order the zip's
	 * directory happens to hold them, and even sorted lexicographically `page10` precedes `page2`.
	 * [naturalOrder] compares digit runs numerically, which is the ordering a human meant when
	 * they named the files.
	 */
	fun pages(file: File, sourceName: String = LOCAL_SOURCE): List<AgehaPage> {
		require(isSupported(file)) { unsupportedReason(file) ?: "unsupported archive" }
		return ZipFile(file).use { zip ->
			zip.entries().asSequence()
				.filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
				.map { it.name }
				.sortedWith(naturalOrder)
				.mapIndexed { index, entryName ->
					AgehaPage(
						// The index is the id: entry names are not numeric and the reader needs a
						// stable ordering key it can compare.
						id = index.toLong(),
						url = pageUrl(file, entryName),
						preview = null,
						sourceName = sourceName,
					)
				}
				.toList()
		}
	}

	/** @see ArchiveUrl */
	fun pageUrl(archive: File, entryName: String): String = ArchiveUrl.of(archive, entryName)

	/** @see ArchiveUrl */
	fun parse(url: String): Pair<File, String>? = ArchiveUrl.parse(url)

	/** Reads one entry's bytes. */
	fun readEntry(archive: File, entryName: String): ByteArray? = ZipFile(archive).use { zip ->
		val entry = zip.getEntry(entryName) ?: return null
		zip.getInputStream(entry).use { it.readBytes() }
	}

	/** A chapter standing for one archive file. */
	fun chapterFor(file: File, sourceName: String = LOCAL_SOURCE): AgehaChapter = AgehaChapter(
		id = file.absolutePath.hashCode().toLong() and 0xFFFFFFFFL,
		title = file.nameWithoutExtension,
		number = null,
		volume = null,
		url = file.absolutePath,
		scanlator = null,
		uploadDate = file.lastModified().takeIf { it > 0 },
		branch = null,
		sourceName = sourceName,
	)

	/**
	 * Compares strings with digit runs treated as numbers.
	 *
	 * `page2` before `page10`, which lexicographic ordering gets backwards -- and gets backwards
	 * silently, producing a chapter that reads in a plausible but wrong order.
	 */
	val naturalOrder: Comparator<String> = Comparator { a, b ->
		var i = 0
		var j = 0
		while (i < a.length && j < b.length) {
			val ca = a[i]
			val cb = b[j]
			if (ca.isDigit() && cb.isDigit()) {
				var endA = i
				while (endA < a.length && a[endA].isDigit()) endA++
				var endB = j
				while (endB < b.length && b[endB].isDigit()) endB++
				// Compared by digit count first, then lexicographically, after dropping leading
				// zeros. That is an exact numeric comparison for non-negative integers and needs
				// no parsing, so a filename with a forty-digit run cannot overflow it -- and
				// `007` and `7` compare equal, so a chapter mixing padded and unpadded numbering
				// still sorts correctly.
				val numA = a.substring(i, endA).trimStart('0').ifEmpty { "0" }
				val numB = b.substring(j, endB).trimStart('0').ifEmpty { "0" }
				val byLength = numA.length.compareTo(numB.length)
				if (byLength != 0) return@Comparator byLength
				val byValue = numA.compareTo(numB)
				if (byValue != 0) return@Comparator byValue
				i = endA
				j = endB
			} else {
				val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
				if (byChar != 0) return@Comparator byChar
				i++
				j++
			}
		}
		(a.length - i).compareTo(b.length - j)
	}

	/** The source name local files are attributed to. Never collides with a parser source name. */
	const val LOCAL_SOURCE = "LOCAL"
}
