package app.ageha.core.jvmcontext

import app.ageha.core.js.NoJsRuntime
import app.ageha.core.network.AgehaHttpClient
import app.ageha.core.network.CommonHeadersInterceptor
import app.ageha.core.network.HttpHeaders
import app.ageha.core.network.PersistentCookieJar
import app.ageha.core.network.RateLimitInterceptor
import com.sun.net.httpserver.HttpServer
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.mergeWith
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * The path a request actually takes through the source stack's HTTP client.
 *
 * Two defects lived here at once, and neither cost anything at compile time.
 *
 * **Images never reached their parser.** `MangaParser` extends `okhttp3.Interceptor`, and a good
 * number of parsers do their real work there, on the *image* response -- MANGA Plus XOR-decrypts
 * page bytes, and eight sources reassemble scrambled tiles through `redrawImageResponse`. But
 * nothing tagged an image request with its source, and the image loader used the base client,
 * which carries no parser dispatch at all. So the descrambler Ageha implements was never called,
 * and those sources looked dead rather than unwired.
 *
 * **Ageha's generic headers beat every parser's own.** `CommonHeadersInterceptor` was outermost,
 * so it filled in Referer, User-Agent and Accept-Language *before* the parser was reached -- and
 * upstream's `MangaParserWrapper` merges parser headers with `replace = false`, which skips any
 * name already present. 52 parser classes set one of those headers and lost it.
 *
 * Both are properties of interceptor *order*, so the order is asserted directly, and then the
 * consequences are proved over a real socket.
 */
class SourceRequestPipelineTest {

	private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
	private val received = ConcurrentHashMap<String, Map<String, String>>()
	private val clients = mutableListOf<OkHttpClient>()

	/**
	 * The cache and cookie directory, owned by this test rather than by `@TempDir`.
	 *
	 * Deliberate, and for the reason `ImageLoaderWiringTest` records about Coil's: an OkHttp Cache
	 * keeps its journal file open and does not release the handle synchronously, and on Windows an
	 * open handle makes a directory undeletable -- so `@TempDir` fails the test during cleanup,
	 * *after* its assertions have already passed. A scratch directory is not what is under test;
	 * a best-effort delete is the right amount of ceremony for it.
	 */
	private lateinit var dir: File

	private val baseUrl: String get() = "http://127.0.0.1:" + server.address.port

	init {
		server.createContext("/") { exchange ->
			received[exchange.requestURI.path] = exchange.requestHeaders.entries
				.associate { (name, values) -> name.lowercase() to values.joinToString(",") }
			val body = "page-bytes".toByteArray()
			exchange.sendResponseHeaders(200, body.size.toLong())
			exchange.responseBody.use { it.write(body) }
			exchange.close()
		}
		server.executor = null
		server.start()
	}

	@BeforeEach
	fun createScratchDir() {
		dir = Files.createTempDirectory("ageha-pipeline-test").toFile()
	}

	@AfterEach
	fun tearDown() {
		server.stop(0)
		// The derived client shares the base client's pool and cache, so each is released once and
		// the second attempt is a no-op -- but the cache journal must be closed either way, or the
		// @TempDir cleanup fails on Windows after the assertions have already passed.
		clients.forEach { client ->
			runCatching { client.dispatcher.executorService.shutdown() }
			runCatching { client.connectionPool.evictAll() }
			runCatching { client.cache?.close() }
		}
		dir.deleteRecursively()
	}

	// ---- ordering ----------------------------------------------------------------------------

	@Test
	@DisplayName("Ageha's generic headers run below the parser, never above it")
	fun commonHeadersRunInsideTheParser() {
		val order = context().httpClient.interceptors.map { it::class.java.simpleName }

		val dispatch = order.indexOf(ParserDispatchInterceptor::class.java.simpleName)
		val common = order.indexOf(CommonHeadersInterceptor::class.java.simpleName)
		assertTrue(dispatch >= 0, "no parser dispatch in the stack: $order")
		assertTrue(common >= 0, "no common headers in the stack: $order")
		assertTrue(
			dispatch < common,
			"CommonHeadersInterceptor must run *inside* the parser dispatch, or upstream's " +
				"non-replacing header merge silently drops every header the parser set. Order: $order",
		)
	}

	@Test
	@DisplayName("the whole stack is in the order the comments claim it is")
	fun stackOrderIsWhatTheCommentsClaim() {
		val order = context().httpClient.interceptors.map { it::class.java.simpleName }
		assertEquals(
			listOf(
				RateLimitInterceptor::class.java.simpleName,
				SourceTagInterceptor::class.java.simpleName,
				ParserDispatchInterceptor::class.java.simpleName,
				SourceRefererInterceptor::class.java.simpleName,
				CommonHeadersInterceptor::class.java.simpleName,
				CloudflareClearanceInterceptor::class.java.simpleName,
			),
			order,
			"this order is load-bearing; see AgehaMangaLoaderContext.httpClient for each position",
		)
	}

	// ---- consequences, over a real socket ----------------------------------------------------

	@Test
	@DisplayName("an image request Ageha built reaches its source's own interceptor")
	fun imageRequestReachesTheParser() {
		val parser = RecordingParser(parserDomain = "example.test")
		val client = context(parser).httpClient

		val response = client.newCall(
			Request.Builder()
				.url("$baseUrl/page.jpg")
				.header(HttpHeaders.SOURCE_NAME, SOURCE.name)
				.build(),
		).execute()

		// The parser's intercept rewrote the body, which is the whole point: a source that
		// descrambles or decrypts its pages does it there and nowhere else.
		assertEquals("descrambled", response.use { it.body.string() })
		assertEquals(1, parser.intercepted)
	}

	@Test
	@DisplayName("the marker naming the source is Ageha's business and never reaches the site")
	fun markerIsStrippedBeforeTheRequestGoesOut() {
		val client = context(RecordingParser(parserDomain = "example.test")).httpClient

		client.newCall(
			Request.Builder()
				.url("$baseUrl/page.jpg")
				.header(HttpHeaders.SOURCE_NAME, SOURCE.name)
				.build(),
		).execute().close()

		assertNull(received.getValue("/page.jpg")[HttpHeaders.SOURCE_NAME.lowercase()])
	}

	@Test
	@DisplayName("a header the parser set survives, rather than losing to Ageha's default")
	fun parserHeadersBeatAgehaDefaults() {
		val parser = RecordingParser(
			parserDomain = "example.test",
			requestHeaders = Headers.headersOf(
				"User-Agent", "ParserUA/1.0",
				"Referer", "https://chosen-by-parser.test/",
			),
		)
		val client = context(parser).httpClient

		client.newCall(
			Request.Builder()
				.url("$baseUrl/api/search")
				.header(HttpHeaders.SOURCE_NAME, SOURCE.name)
				.build(),
		).execute().close()

		val sent = received.getValue("/api/search")
		assertEquals("ParserUA/1.0", sent["user-agent"])
		assertEquals("https://chosen-by-parser.test/", sent["referer"])
	}

	@Test
	@DisplayName("a parser that sets no Referer gets its source's domain, not the request's host")
	fun refererNamesTheSourceNotTheHost() {
		val client = context(RecordingParser(parserDomain = "example.test")).httpClient

		client.newCall(
			Request.Builder()
				.url("$baseUrl/api/search")
				.header(HttpHeaders.SOURCE_NAME, SOURCE.name)
				.build(),
		).execute().close()

		// Not "http://127.0.0.1:<port>/", which is what a Referer filled in from the request alone
		// would say -- and what a CDN serving only its own site refuses exactly as it refuses none.
		assertEquals("https://example.test/", received.getValue("/api/search")["referer"])
	}

	@Test
	@DisplayName("an untagged request is left alone, so non-source traffic is unaffected")
	fun untaggedRequestsSkipTheParser() {
		val parser = RecordingParser(parserDomain = "example.test")
		val client = context(parser).httpClient

		val response = client.newCall(Request.Builder().url("$baseUrl/jitpack.json").build()).execute()

		assertEquals("page-bytes", response.use { it.body.string() })
		assertEquals(0, parser.intercepted)
	}

	// ---- fixtures ----------------------------------------------------------------------------

	private fun context(parser: MangaParser? = null): AgehaMangaLoaderContext {
		val cookieJar = PersistentCookieJar(File(dir, "cookies.json"))
		val base = AgehaHttpClient.build(
			cookieJar = cookieJar,
			cacheDir = File(dir, "http-cache"),
			// The throttle floor is irrelevant to what is under test and would only slow the suite.
			minRequestIntervalMillis = 0L,
		).also { clients += it }
		return AgehaMangaLoaderContext(
			cookieJar = cookieJar,
			jsRuntime = NoJsRuntime,
			configStore = SourceConfigStore(),
			parserForSource = { if (it.name == SOURCE.name) parser else null },
			sourceForName = { if (it == SOURCE.name) SOURCE else null },
			baseHttpClient = base,
		).also { clients += it.httpClient }
	}

	private companion object {
		/**
		 * A source taken from the build's own generated enum rather than invented.
		 *
		 * The enum is KSP-generated, so no constant may be named (docs/FINDINGS.md 5) -- but any
		 * one of them is a real `MangaSource` with a real name, which is all a tag needs.
		 */
		val SOURCE: MangaSource = MangaParserSource.entries.first()
	}
}

/**
 * A parser that records whether it was consulted, and proves it by rewriting the response.
 *
 * Only `domain`, `getRequestHeaders` and `intercept` are reached by the interceptor stack. The
 * rest of `MangaParser` is stubbed rather than omitted because the interface is what it is;
 * anything reaching those would be a test asking a different question than this one.
 */
private class RecordingParser(
	private val parserDomain: String,
	private val requestHeaders: Headers = Headers.headersOf(),
) : MangaParser {

	@Volatile
	var intercepted = 0
		private set

	override fun intercept(chain: Interceptor.Chain): Response {
		intercepted++
		// What `MangaParserWrapper.intercept` does to every real parser before delegating, using
		// upstream's own merge rather than a local imitation of it. `replace = false` is the whole
		// reason interceptor order matters here: it *skips* any header already on the request, so
		// a default filled in above the parser does not lose to the parser -- the parser loses to
		// it. Copying the call keeps this test honest if upstream ever changes that flag.
		val request = chain.request()
		val merged = request.newBuilder()
			.headers(request.headers.newBuilder().mergeWith(requestHeaders, false).build())
			.build()
		val response = chain.proceed(merged)
		// Stands in for what the real ones do here: XOR-decrypt the bytes, or reassemble the
		// scrambled tiles through MangaLoaderContext.redrawImageResponse.
		return response.newBuilder()
			.body("descrambled".toResponseBody(response.body.contentType()))
			.build()
	}

	override val domain: String get() = parserDomain

	override fun getRequestHeaders(): Headers = requestHeaders

	override val source: MangaParserSource get() = MangaParserSource.entries.first()

	override val configKeyDomain: ConfigKey.Domain get() = ConfigKey.Domain(parserDomain)

	override val availableSortOrders: Set<SortOrder> get() = setOf(SortOrder.UPDATED)

	override val searchQueryCapabilities: MangaSearchQueryCapabilities get() = unsupported()

	override val filterCapabilities: MangaListFilterCapabilities get() = unsupported()

	override val config: MangaSourceConfig get() = unsupported()

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) = Unit

	@Suppress("OVERRIDE_DEPRECATION")
	override suspend fun getList(query: MangaSearchQuery): List<Manga> = unsupported()

	override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> =
		unsupported()

	override suspend fun getDetails(manga: Manga): Manga = unsupported()

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = unsupported()

	override suspend fun getPageUrl(page: MangaPage): String = unsupported()

	override suspend fun getFilterOptions(): MangaListFilterOptions = unsupported()

	override suspend fun getFavicons(): Favicons = unsupported()

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = unsupported()

	// Upstream marks this one internal-API. Opting in is safe here precisely because this stub
	// never answers it -- the HTTP stack under test does not reach link resolution at all.
	@OptIn(InternalParsersApi::class)
	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? = unsupported()

	private fun unsupported(): Nothing =
		throw UnsupportedOperationException("RecordingParser only answers the HTTP stack")
}
