package app.ageha.core.data

import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.SourceFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** How a download ended. */
sealed interface DownloadResult {

	data class Complete(val file: File, val pageCount: Int) : DownloadResult

	/** Already on disk. Not an error, and not work worth repeating. */
	data class AlreadyDownloaded(val file: File) : DownloadResult

	data class Failed(val failure: SourceFailure, val completedPages: Int) : DownloadResult

	/**
	 * Some pages could not be fetched but the chapter was written anyway.
	 *
	 * A chapter missing two pages of forty is still worth reading offline, and telling the user
	 * which pages are missing beats refusing them the other thirty-eight.
	 */
	data class Partial(val file: File, val pageCount: Int, val missingPages: List<Int>) : DownloadResult
}

/** Progress for one chapter, as it happens. */
data class DownloadProgress(val completed: Int, val total: Int) {
	val fraction: Float get() = if (total <= 0) 0f else completed.toFloat() / total
}

/**
 * Writes one chapter to a CBZ.
 *
 * CBZ rather than a directory of images, and rather than a format of Ageha's own. It is the
 * interchange format every comic reader understands, which means a download is portable: a user
 * who stops using Ageha still has their chapters, readable in anything. That property is worth
 * more than any efficiency a bespoke format could buy.
 *
 * Three properties this guarantees, each of which is a way naive download code goes wrong:
 *
 *  - **A partial file never looks complete.** Everything is written to a `.part` file and renamed
 *    only when the chapter is finished. An interrupted download leaves no `.cbz` behind, so
 *    "already downloaded" cannot be a lie.
 *  - **Page order survives.** Entries are named with a zero-padded index, so *any* reader sorts
 *    them correctly -- not just Ageha, whose own natural ordering would forgive worse names.
 *  - **One page failing does not lose the chapter.** Missing pages are reported, not fatal.
 */
class ChapterDownloader(
	private val catalog: CatalogRepository,
	private val root: File,
	private val fetchImage: suspend (url: String, headers: Map<String, String>) -> InputStream?,
) {

	/** Where a chapter's file lives. Stable, so "is it downloaded" is a file check. */
	fun fileFor(manga: AgehaManga, chapter: AgehaChapter): File =
		File(root, "${manga.sourceName.sanitised()}/${manga.title.sanitised()}/${chapterName(chapter)}.cbz")

	fun isDownloaded(manga: AgehaManga, chapter: AgehaChapter): Boolean = fileFor(manga, chapter).isFile

	/** Every chapter downloaded for one manga. */
	fun downloadedChapters(manga: AgehaManga): List<File> =
		File(root, "${manga.sourceName.sanitised()}/${manga.title.sanitised()}")
			.listFiles { file -> file.extension.equals("cbz", ignoreCase = true) }
			?.sortedWith(compareBy(LocalArchive.naturalOrder) { it.name })
			.orEmpty()

	suspend fun download(
		manga: AgehaManga,
		chapter: AgehaChapter,
		onProgress: (DownloadProgress) -> Unit = {},
	): DownloadResult = withContext(Dispatchers.IO) {
		val target = fileFor(manga, chapter)
		if (target.isFile) return@withContext DownloadResult.AlreadyDownloaded(target)
		target.parentFile?.mkdirs()

		val pages = when (val result = catalog.pages(chapter)) {
			is CatalogResult.Failure -> return@withContext DownloadResult.Failed(result.failure, 0)
			is CatalogResult.Success -> result.value
		}
		if (pages.isEmpty()) {
			return@withContext DownloadResult.Failed(
				SourceFailure.ContentUnavailable(chapter.sourceName, "the chapter has no pages"),
				0,
			)
		}

		val headers = catalog.imageHeaders(chapter.sourceName)
		val partial = File(target.parentFile, target.name + PART_SUFFIX)
		val missing = mutableListOf<Int>()
		var written = 0

		try {
			ZipOutputStream(partial.outputStream().buffered()).use { zip ->
				pages.forEachIndexed { index, page ->
					onProgress(DownloadProgress(index, pages.size))
					val url = when (val resolved = catalog.pageUrl(page)) {
						is CatalogResult.Failure -> null
						is CatalogResult.Success -> resolved.value
					}
					val stream = url?.let { runCatching { fetchImage(it, headers) }.getOrNull() }
					if (stream == null) {
						missing += index + 1
						return@forEachIndexed
					}
					// Zero-padded to the width of the largest index, so lexicographic order --
					// which is all a foreign reader may do -- is also reading order.
					val name = "%0${pages.size.toString().length.coerceAtLeast(3)}d".format(index + 1)
					zip.putNextEntry(ZipEntry("$name.${url.imageExtension()}"))
					stream.use { it.copyTo(zip) }
					zip.closeEntry()
					written++
				}
			}
		} catch (cancellation: CancellationException) {
			// A cancelled download leaves nothing behind. The `.part` file is the only artefact
			// and it is removed, so the chapter is simply not downloaded rather than half so.
			partial.delete()
			throw cancellation
		} catch (failure: Throwable) {
			partial.delete()
			return@withContext DownloadResult.Failed(
				failure as? SourceFailure ?: SourceFailure.Unknown(chapter.sourceName, failure),
				written,
			)
		}

		if (written == 0) {
			partial.delete()
			return@withContext DownloadResult.Failed(
				SourceFailure.ContentUnavailable(chapter.sourceName, "no pages could be fetched"),
				0,
			)
		}

		// The rename is the commit. Until it happens there is no .cbz, so nothing can mistake an
		// interrupted download for a finished one.
		if (!partial.renameTo(target)) {
			// Windows refuses a rename onto an existing file. Nothing should exist here -- the
			// early return covers that -- but a concurrent download of the same chapter could
			// have finished first, in which case its file is as good as this one.
			partial.delete()
			return@withContext DownloadResult.AlreadyDownloaded(target)
		}
		onProgress(DownloadProgress(pages.size, pages.size))

		if (missing.isEmpty()) {
			DownloadResult.Complete(target, written)
		} else {
			DownloadResult.Partial(target, written, missing)
		}
	}

	fun delete(manga: AgehaManga, chapter: AgehaChapter): Boolean = fileFor(manga, chapter).delete()

	/** Total bytes on disk, for the settings screen. */
	fun sizeOnDisk(): Long =
		root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

	private companion object {
		const val PART_SUFFIX = ".part"

		/**
		 * Characters no filesystem across Windows, macOS and Linux will all accept.
		 *
		 * Manga titles routinely contain `:` and `?`, both of which are legal on Linux and
		 * rejected by Windows -- so a library that downloads fine on one machine fails on
		 * another. Replacing rather than stripping keeps titles distinguishable.
		 */
		val ILLEGAL_IN_FILENAMES = Regex("""[\\/:*?"<>|\x00-\x1F]""")

		/** Windows also refuses these names outright, whatever the extension. */
		val RESERVED_NAMES = setOf(
			"CON", "PRN", "AUX", "NUL",
			"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
			"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
		)

		const val MAX_NAME_LENGTH = 120
	}

	private fun String.sanitised(): String {
		val cleaned = ILLEGAL_IN_FILENAMES.replace(this, "_")
			.trim()
			// Windows silently strips trailing dots and spaces from directory names, which turns
			// "Vol. 3." into "Vol. 3" and makes the path Ageha computes differ from the one that
			// exists. Removing them here keeps the two in step.
			.trimEnd('.', ' ')
			.take(MAX_NAME_LENGTH)
			.ifEmpty { "untitled" }
		return if (cleaned.uppercase() in RESERVED_NAMES) "_$cleaned" else cleaned
	}

	private fun chapterName(chapter: AgehaChapter): String {
		val number = chapter.number?.let {
			if (it == it.toInt().toFloat()) "%04d".format(it.toInt()) else "%08.2f".format(it)
		}
		val title = chapter.title?.sanitised()
		return listOfNotNull(number, title).joinToString(" - ").ifEmpty { "chapter-${chapter.id}" }
	}

	private fun String.imageExtension(): String {
		val candidate = substringBefore('?').substringAfterLast('.', "").lowercase()
		// A url with no usable extension is common -- CDNs serve images from opaque paths. jpg is
		// the safe default: every comic reader probes the bytes anyway, and a wrong extension on a
		// correct file is far less damaging than no extension at all, which some readers skip.
		return if (candidate.length in 3..4 && candidate.all { it.isLetterOrDigit() }) candidate else "jpg"
	}
}
