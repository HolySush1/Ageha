package app.ageha.core.data

import java.io.File

/** One downloaded title, as the Downloads screen needs to see it. */
data class DownloadedTitle(
	/** The directory name under the source, which is [ChapterDownloader]'s sanitised title. */
	val title: String,
	val sourceName: String,
	val chapterCount: Int,
	val bytes: Long,
	/** Newest chapter file's timestamp, epoch millis. Sorts recent work to the top. */
	val lastModified: Long,
	/** The directory itself, so a delete does not have to recompute the path. */
	val directory: File,
) {
	/** Stable across a rescan, and unique: two sources may both carry the same title. */
	val key: String get() = "$sourceName/$title"
}

/**
 * What is on this device, and how much room is left.
 *
 * [pages] is what the reader downloaded on purpose; [thumbnails] is cover art the image loader
 * cached on its own. They are reported apart because they behave differently -- one is the user's
 * library and the other is disposable -- and because a storage bar that folds an evictable cache
 * into "your downloads" invites people to delete chapters to reclaim space a cache would have
 * given back for free.
 */
data class StorageReport(
	val titles: List<DownloadedTitle>,
	val pages: Long,
	val thumbnails: Long,
	/** Free space on the volume the downloads live on. */
	val free: Long,
	/** Total size of that volume. */
	val capacity: Long,
) {
	val used: Long get() = pages + thumbnails
	val chapterCount: Int get() = titles.sumOf { it.chapterCount }

	companion object {
		val EMPTY = StorageReport(emptyList(), 0, 0, 0, 0)
	}
}

/**
 * Reads the download directory back as a list of titles.
 *
 * ## Why this exists at all
 *
 * `DownloadQueue` knows what is *moving* -- jobs enqueued in this session, their progress, their
 * failures -- and forgets all of it when the window closes. Nothing knew what was already *there*.
 * That was fine while the Downloads screen was a queue view, and is not fine now that it opens on
 * "on this device": a screen that shows an empty list until you download something during that
 * session is a screen that lies about your library every time you launch.
 *
 * ## Why the filesystem is the source of truth, rather than a table
 *
 * [ChapterDownloader] already commits to a layout -- `root/<source>/<title>/<chapter>.cbz`, with
 * partial work confined to `.part` files that are renamed only on success. That makes the
 * directory tree a record that cannot drift from reality, which a table can: a user who deletes a
 * chapter in their file manager, restores a backup, or copies their library to another machine
 * leaves a database row describing a file that is not there. Walking the tree costs a few
 * milliseconds against a library of thousands and is never wrong.
 *
 * Only `.cbz` files count. A `.part` is work in progress -- it is not a chapter you have, and
 * counting one would make the storage figure climb while a download ran and then drop when it
 * succeeded, which reads as a bug rather than as progress.
 */
class DownloadInventory(
	private val root: File,
	/** Where the image loader caches cover art. Reported separately; see [StorageReport]. */
	private val thumbnailCache: File,
) {

	/**
	 * Walks the tree.
	 *
	 * Blocking, and left that way rather than made `suspend`: this is plain synchronous IO with no
	 * cancellation points worth the ceremony, and every caller already has a background dispatcher
	 * to run it on.
	 */
	fun scan(): StorageReport {
		val titles = mutableListOf<DownloadedTitle>()
		// `listFiles` returns null for a directory that does not exist or cannot be read. A fresh
		// installation is the common case there, not an error.
		for (sourceDir in root.listFiles()?.filter { it.isDirectory }.orEmpty()) {
			for (titleDir in sourceDir.listFiles()?.filter { it.isDirectory }.orEmpty()) {
				val chapters = titleDir
					.listFiles { file -> file.isFile && file.extension.equals("cbz", true) }
					.orEmpty()
				if (chapters.isEmpty()) continue
				titles += DownloadedTitle(
					title = titleDir.name,
					sourceName = sourceDir.name,
					chapterCount = chapters.size,
					bytes = chapters.sumOf { it.length() },
					lastModified = chapters.maxOf { it.lastModified() },
					directory = titleDir,
				)
			}
		}
		titles.sortByDescending { it.lastModified }

		// `totalSpace` is 0 for a path that does not exist, which would make the storage bar
		// divide by zero on a first launch. Created rather than special-cased, so the figures are
		// real from the first frame.
		if (!root.exists()) root.mkdirs()

		return StorageReport(
			titles = titles,
			pages = titles.sumOf { it.bytes },
			thumbnails = directorySize(thumbnailCache),
			free = root.usableSpace,
			capacity = root.totalSpace,
		)
	}

	/**
	 * Deletes every chapter of one title.
	 *
	 * Returns the bytes actually reclaimed, so the caller reports what it freed rather than what
	 * it expected to. Destructive: the UI confirms before calling this.
	 *
	 * The title's directory goes too, but only if the delete emptied it. A file the walk did not
	 * count -- a `.part` from an interrupted download, or something the user put there -- keeps
	 * the directory alive instead of being swept up with the chapters.
	 */
	fun delete(title: DownloadedTitle): Long {
		var freed = 0L
		val chapters = title.directory
			.listFiles { file -> file.isFile && file.extension.equals("cbz", true) }
			.orEmpty()
		for (file in chapters) {
			val size = file.length()
			if (file.delete()) freed += size
		}
		if (title.directory.listFiles()?.isEmpty() == true) title.directory.delete()
		return freed
	}

	private fun directorySize(dir: File): Long =
		if (!dir.isDirectory) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
