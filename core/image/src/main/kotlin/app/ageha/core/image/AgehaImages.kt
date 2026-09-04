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
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Size
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
			.httpHeaders(headers.toNetworkHeaders())
			.build()

	/**
	 * A request for a page in the reader, decoded at the source's own resolution.
	 *
	 * This is [request] with the sizing turned off, and the reason is not subtlety about quality --
	 * it is that the default produces an image the reader cannot zoom into.
	 *
	 * Coil sizes a decode to the composable that asked for it. Off Android it then resamples with
	 * `Canvas.drawImageRect(image, src, dst)` -- the three-argument overload, which takes no
	 * `SamplingMode` and therefore uses no mipmaps. So a 2000px scan shown in a 900px viewport
	 * becomes a *900px bitmap*, resampled badly, and that bitmap is all the reader has: zooming it
	 * magnifies the 900px raster rather than revealing the other 1100px of detail that were
	 * decoded away. The page looks, in the words of the bug report, "like a screenshot taken at a
	 * zoomed out place".
	 *
	 * [Size.ORIGINAL] makes the size multiplier exactly 1, so Coil's resampler never runs and the
	 * decode is a straight blit. Any scaling then happens once, at draw time, where the reader asks
	 * for `FilterQuality.Medium` and Skia does it with a cached mip chain.
	 *
	 * The cost is real -- a page is now a full-size bitmap rather than a viewport-size one -- which
	 * is why [MAX_PAGE_PIXELS] exists and why `:app:desktop:webtoonProfile` is the gate on this
	 * change. Covers deliberately keep [request]: a grid of hundreds of full-resolution decodes
	 * would be pure waste, and nobody zooms a cover.
	 */
	fun readerRequest(url: String, headers: Map<String, String>): ImageRequest =
		ImageRequest.Builder(PlatformContext.INSTANCE)
			.data(url)
			.httpHeaders(headers.toNetworkHeaders())
			.size(Size.ORIGINAL)
			// INEXACT with an original size is belt and braces: it forbids scaling *up* as well,
			// so a small page can never be decoded into a larger bitmap than the source.
			.precision(Precision.INEXACT)
			.maxBitmapSize(MAX_PAGE_PIXELS)
			.build()

	/**
	 * The ceiling on a decoded page.
	 *
	 * Tall rather than square because webtoon pages are: a single strip image of 800x12000 is
	 * ordinary, and clamping it to a square bound would halve its resolution for no reason. Well
	 * above any display, so in practice this only catches a source serving something pathological.
	 */
	private val MAX_PAGE_PIXELS = Size(8192, 16384)

	private fun Map<String, String>.toNetworkHeaders(): NetworkHeaders =
		NetworkHeaders.Builder().apply {
			for ((name, value) in this@toNetworkHeaders) set(name, value)
		}.build()
}
