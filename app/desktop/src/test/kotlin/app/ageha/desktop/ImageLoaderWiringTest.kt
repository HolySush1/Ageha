package app.ageha.desktop

import app.ageha.core.image.AgehaImages
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Compose must draw images through *Ageha's* loader.
 *
 * This guards a bug that cost nothing at compile time and everything at runtime: the loader was
 * built, registered in Koin, and handed to nothing at all. `AsyncImage` resolves Coil's singleton,
 * so every cover and every page silently went through Coil's own default loader instead -- which
 * has neither the archive fetcher nor Ageha's OkHttp client, and therefore no cookie jar, no
 * User-Agent and no per-source `Referer`.
 *
 * The visible symptom was one blank page in a local CBZ. The invisible one was every image request
 * to every source arriving as an anonymous stranger, which is exactly what the sources that gate
 * their images check for.
 *
 * Deliberately not a test of the whole of [AgehaApplication.start]: that would boot the parsers
 * bridge and open the user's real database. The handoff is its own function so this can be cheap
 * and still test the thing that broke.
 */
class ImageLoaderWiringTest {

	private val built = mutableListOf<ImageLoader>()

	/**
	 * The disk cache directory, owned by this test rather than by `@TempDir`.
	 *
	 * Deliberate. Coil's disk cache keeps its journal file open and does not release the handle
	 * synchronously on `shutdown()`, and on Windows an open handle makes a directory undeletable --
	 * so `@TempDir` fails the test during cleanup, *after* its assertions have already passed. A
	 * cache directory is not what is under test; a best-effort delete is the right amount of
	 * ceremony for it.
	 */
	private lateinit var cacheDir: File

	@BeforeEach
	fun createCacheDir() {
		cacheDir = Files.createTempDirectory("ageha-image-test").toFile()
	}

	private fun loader(client: OkHttpClient = OkHttpClient()): ImageLoader =
		AgehaImages.create(client, cacheDir).also { built += it }

	// `reset` is delicate because it mutates process-global state; that is exactly why this
	// test calls it, so the singleton it sets cannot leak into whatever runs next in this JVM.
	@OptIn(coil3.annotation.DelicateCoilApi::class)
	@AfterEach
	fun tearDown() {
		stopKoin()
		built.forEach { it.shutdown() }
		built.clear()
		// The singleton is process-global. Left set, it would leak into any test that ran after.
		SingletonImageLoader.reset()
		cacheDir.deleteRecursively()
	}

	@Test
	@DisplayName("Coil's singleton resolves to the loader in the graph, not to Coil's default")
	fun singletonResolvesToOurs() {
		val ours = loader()
		val koin = startKoin { modules(module { single<ImageLoader> { ours } }) }.koin

		installImageLoader(koin)

		assertSame(
			ours,
			SingletonImageLoader.get(PlatformContext.INSTANCE),
			"AsyncImage resolves the singleton, so this identity is what makes the graph's loader " +
				"the one that actually fetches",
		)
	}

	/**
	 * And the loader it resolves to is the one that can open a local archive.
	 *
	 * Identity alone would still pass if `AgehaImages` stopped registering the archive fetcher, and
	 * the symptom would be the same blank page. Order matters as much as presence: the archive
	 * fetcher has to come *before* the network one, or a `cbz://` url is handed to OkHttp.
	 */
	@Test
	@DisplayName("the loader Compose gets tries archives before the network")
	fun archivesComeFirst() {
		val koin = startKoin { modules(module { single<ImageLoader> { loader() } }) }.koin
		installImageLoader(koin)

		val names = SingletonImageLoader.get(PlatformContext.INSTANCE)
			.components.fetcherFactories
			.map { it.first.javaClass.name }
		val archive = names.indexOfFirst { it.contains("ArchiveFetcher") }
		val network = names.indexOfFirst { it.contains("NetworkFetcher") }

		assertTrue(archive >= 0, "no archive fetcher: a local CBZ would render as a blank page. Got $names")
		assertTrue(network >= 0, "no network fetcher at all. Got $names")
		assertTrue(archive < network, "the archive fetcher must be consulted first. Got $names")
	}

	/**
	 * The network fetcher is backed by **Ageha's** OkHttp client.
	 *
	 * This cannot be asserted by type. Coil's OkHttp artifact self-registers through `ServiceLoader`,
	 * so the loader ends up holding *two* `NetworkFetcher.Factory` instances -- ours and Coil's own --
	 * and they are the same class. The only honest way to tell them apart is to fetch something and
	 * see whose client was used, so this puts an interceptor on the client Ageha builds with and
	 * checks that it saw the request.
	 *
	 * That is the whole substance of the original bug: images were being fetched by *a* client, just
	 * not the one carrying the cookie jar, the User-Agent and the per-source `Referer`.
	 */
	@Test
	@DisplayName("images are fetched through Ageha's own OkHttp client")
	fun fetchesThroughOurClient() {
		val seen = AtomicInteger()
		val client = OkHttpClient.Builder()
			.addInterceptor { chain ->
				seen.incrementAndGet()
				// Answered here rather than over the wire: this test is about *which client*, not
				// about reaching anybody's server.
				Response.Builder()
					.request(chain.request())
					.protocol(Protocol.HTTP_1_1)
					.code(200)
					.message("OK")
					.body(onePixelPng().toResponseBody("image/png".toMediaType()))
					.build()
			}
			.build()
		val koin = startKoin { modules(module { single<ImageLoader> { loader(client) } }) }.koin
		installImageLoader(koin)

		val context = PlatformContext.INSTANCE
		runBlocking {
			SingletonImageLoader.get(context).execute(
				ImageRequest.Builder(context).data("https://example.invalid/cover.png").build(),
			)
		}

		// The decode may or may not succeed depending on the graphics backend available to the test
		// JVM, and that is not what is under test. Reaching our client is.
		assertEquals(1, seen.get(), "the image request never reached Ageha's OkHttp client")
	}

	/** A real, minimal PNG, so OkHttp is handed a body of the type it claims. */
	private fun onePixelPng(): ByteArray {
		val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
		return ByteArrayOutputStream().use { out ->
			ImageIO.write(image, "png", out)
			out.toByteArray()
		}
	}
}
