package app.ageha.core.image

import app.ageha.core.model.ArchiveUrl
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import java.util.zip.ZipFile

/**
 * Serves page images out of a local CBZ to Coil.
 *
 * Registered alongside the network fetcher, so the reader draws a local archive through exactly
 * the same path it draws a remote source -- one `AsyncImage`, one memory cache, one set of
 * placeholder and error states. The alternative, a second image pipeline for local files, would
 * mean every reader feature had to be written twice.
 *
 * Entries are read on `Dispatchers.IO` and handed over as bytes rather than as a live stream.
 * A `ZipFile` holds an open file handle and Coil may hold a decoded source well past the fetch;
 * keeping the archive open that long would lock the file on Windows, which is exactly the trap
 * that bit the parsers updater.
 */
class ArchiveFetcher(private val url: String) : Fetcher {

	override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
		val (archive, entryName) = ArchiveUrl.parse(url) ?: return@withContext null
		if (!archive.isFile) return@withContext null
		val bytes = ZipFile(archive).use { zip ->
			val entry = zip.getEntry(entryName) ?: return@use null
			zip.getInputStream(entry).use { it.readBytes() }
		} ?: return@withContext null

		SourceFetchResult(
			source = ImageSource(Buffer().apply { write(bytes) }, FileSystem.SYSTEM),
			mimeType = null,
			// DISK, not NETWORK: it came off the local disk, and Coil uses this to decide whether
			// the result is worth writing into the *disk* cache. Caching a local file into a
			// second copy of itself would be pure waste.
			dataSource = DataSource.DISK,
		)
	}

	class Factory : Fetcher.Factory<Any> {
		/**
		 * Accepts a `coil3.Uri` as well as a `String`.
		 *
		 * Coil runs its mappers before consulting fetchers, and the built-in string mapper has
		 * already turned the model into a `Uri` by the time this is asked. Matching only on
		 * `String` therefore never matched anything, and Coil reported it as "unable to create a
		 * fetcher that supports cbz://..." -- a message that reads like the url is malformed
		 * rather than like the factory declined it.
		 */
		override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher? {
			val url = when (data) {
				is String -> data
				is Uri -> data.toString()
				else -> return null
			}
			return if (ArchiveUrl.isArchiveUrl(url)) ArchiveFetcher(url) else null
		}
	}
}
