package app.ageha.core.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Cover and page image loading.
 *
 * **Explicitly OkHttp, not Ktor.** Coil 3 defaults to Ktor on every platform except Android, and
 * that default is wrong here twice over: CLAUDE.md forbids Ktor because the parsers library is
 * built on OkHttp, and a second HTTP client would mean a second cookie jar, a second disk cache
 * and a second User-Agent. Sources that gate images behind a cookie set during the page request
 * would then fail in a way that looks like the source being broken. Coil's OkHttp artifact also
 * self-registers through `ServiceLoader`, so the fetcher is wired explicitly below to be certain
 * it is *Ageha's* client doing the fetching rather than one Coil made for itself.
 */
object AgehaImages {

	/**
	 * Covers are small and numerous; a library of a few thousand is a few hundred MB at most.
	 * Generous on purpose -- re-fetching a cover the user has already seen is the most visible
	 * kind of waste in a library grid, and it is waste that costs the *source* bandwidth too.
	 */
	private const val DISK_CACHE_BYTES = 512L * 1024 * 1024

	/** A quarter of the heap. Bounded, because a webtoon reader will want the rest of it. */
	private const val MEMORY_CACHE_FRACTION = 0.25

	fun create(httpClient: OkHttpClient, cacheDir: File): ImageLoader =
		ImageLoader.Builder(PlatformContext.INSTANCE)
			.components {
				// Local archives first: the fetcher factories are tried in order and the archive
				// one declines anything that is not a cbz url, so putting it ahead of the network
				// costs nothing and keeps a `cbz://` url from being handed to OkHttp.
				add(ArchiveFetcher.Factory())
				add(OkHttpNetworkFetcherFactory(callFactory = { httpClient }))
			}
			.memoryCache {
				MemoryCache.Builder()
					.maxSizePercent(PlatformContext.INSTANCE, MEMORY_CACHE_FRACTION)
					.build()
			}
			.diskCache {
				DiskCache.Builder()
					.directory(File(cacheDir, "images").toOkioPath())
					.maxSizeBytes(DISK_CACHE_BYTES)
					.build()
			}
			.diskCachePolicy(CachePolicy.ENABLED)
			.memoryCachePolicy(CachePolicy.ENABLED)
			// Quiet and quick, per the motion tokens. Long enough not to flash, short enough that
			// scrolling a grid does not look like a slideshow.
			.crossfade(120)
			.build()

	/**
	 * A request for one image from one source.
	 *
	 * [headers] comes from `MangaSourceClient.imageRequestHeaders()` and is not optional in
	 * practice: many sources serve page images only with a matching `Referer`, and return a 403
	 * otherwise. Getting this wrong produces a library grid of broken covers for one source while
	 * every other source works, which reads as the source being dead.
	 */
	fun request(url: String, headers: Map<String, String>): ImageRequest =
		ImageRequest.Builder(PlatformContext.INSTANCE)
			.data(url)
			.httpHeaders(
				NetworkHeaders.Builder().apply {
					for ((name, value) in headers) set(name, value)
				}.build(),
			)
			.build()
}
