package app.ageha.desktop

import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * What Ageha is holding on disk that it could regenerate.
 *
 * Reported in three parts rather than as one total, because only two of them are safe to throw
 * away and the third is the one people would most want explained. See [CacheMaintenance].
 */
data class CacheReport(
	/** Decoded page and cover images, written by Coil. */
	val images: Long,
	/** HTTP responses, written by OkHttp. Includes page images a source served as cacheable. */
	val http: Long,
	/**
	 * The Chromium profile belonging to the browser component.
	 *
	 * Reported, and never emptied by [CacheMaintenance.clear] -- see the note there.
	 */
	val browser: Long,
) {
	/** What the button will actually reclaim. Deliberately excludes [browser]. */
	val clearable: Long get() = images + http

	companion object {
		val EMPTY = CacheReport(0, 0, 0)
	}
}

/**
 * Measuring and emptying Ageha's caches.
 *
 * ## Why this needed to exist
 *
 * Reading a chapter writes every page of it to disk, twice over: Coil keeps a 512MB image cache
 * and OkHttp a 256MB response cache, both enabled by default and neither surfaced anywhere. Up to
 * three quarters of a gigabyte of manga therefore accumulated under the user's profile with no way
 * to see it and no way to remove it short of deleting directories by hand. For a manga reader that
 * is as much a privacy question as a disk one: "I closed the chapter" ought to mean something.
 *
 * ## Why it goes through the libraries rather than deleting directories
 *
 * Both caches are live while the application is running, with open file handles and an in-memory
 * index. Deleting the files underneath them leaves each library convinced the entries still exist,
 * which surfaces later as blank covers rather than as an error anyone could act on.
 * `DiskCache.clear()` and `Cache.evictAll()` empty them from the inside, index included.
 */
class CacheMaintenance(
	private val imageLoader: ImageLoader,
	private val httpClient: OkHttpClient,
	/** The browser component's Chromium profile. Measured, never emptied. */
	private val browserCacheDir: File,
) {

	/**
	 * Current sizes. On [Dispatchers.IO] because two of the three are filesystem work.
	 *
	 * Every read is defended on its own. A cache that has never been written has no directory,
	 * `Cache.size()` does real IO and can throw, and a settings panel that fails to open because a
	 * directory is missing would be a worse bug than the one this class exists to fix.
	 */
	suspend fun report(): CacheReport = withContext(Dispatchers.IO) {
		CacheReport(
			images = runCatching { imageLoader.diskCache?.size ?: 0L }.getOrDefault(0L),
			http = runCatching { httpClient.cache?.size() ?: 0L }.getOrDefault(0L),
			browser = runCatching { browserCacheDir.sizeOnDisk() }.getOrDefault(0L),
		)
	}

	/**
	 * Empty the image and response caches, and report what that reclaimed.
	 *
	 * The Chromium profile is left alone deliberately, and not as an oversight to tidy up later.
	 * It holds the browser component's cookies, so clearing it would sign the user out of every
	 * source they had logged into and discard the challenge clearances that make those sources
	 * work at all -- an expensive and invisible consequence for a button that promises to free up
	 * space. Anyone who wants it gone can remove the browser component itself.
	 *
	 * @return bytes freed, measured rather than assumed: sizes are read either side of the clear.
	 */
	suspend fun clear(): Long = withContext(Dispatchers.IO) {
		val before = report()
		runCatching { imageLoader.diskCache?.clear() }
		runCatching { httpClient.cache?.evictAll() }
		// Measured afterwards because neither library promises it emptied everything -- an entry
		// being read at that moment survives eviction -- and reporting a freed figure larger than
		// what actually went is how a storage number stops being believed.
		(before.clearable - report().clearable).coerceAtLeast(0L)
	}
}

/**
 * Total size of a directory tree, or zero when it is not there.
 *
 * `walkBottomUp` rather than recursion over `listFiles`, so a deep tree cannot overflow the stack.
 * Directories are skipped rather than summed: `length()` on one reports the entry's own size
 * rather than its contents', which on Windows quietly adds a few kilobytes per directory.
 */
private fun File.sizeOnDisk(): Long =
	if (!exists()) 0L else walkBottomUp().filter { it.isFile }.sumOf { it.length() }
