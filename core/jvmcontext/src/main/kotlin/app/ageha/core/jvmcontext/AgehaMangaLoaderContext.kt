package app.ageha.core.jvmcontext

import app.ageha.core.js.InterceptedHttpRequest
import app.ageha.core.js.JsRuntime
import app.ageha.core.model.BrowserActionRequiredException
import app.ageha.core.network.AgehaHttpClient
import app.ageha.core.network.CommonHeadersInterceptor
import app.ageha.core.network.PersistentCookieJar
import app.ageha.core.network.UserAgents
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Ageha's desktop [MangaLoaderContext].
 *
 * This is the one class the parsers library holds a reference to, and the reason :core:jvmcontext
 * exists as a module separate from the facade: it is a *subclass* of an upstream abstract class, so
 * it has to track upstream exactly, while the facade next door exists to absorb upstream changes.
 * Opposite stability profiles, so opposite modules (docs/ARCHITECTURE.md 1.2).
 *
 * Modelled on the reference non-Android implementation upstream points at, kotatsu-dl's
 * MangaLoaderContextImpl, with two deliberate departures:
 *
 *  - **JavaScript does not go to Nashorn.** kotatsu-dl predates the fork's move from
 *    evaluateJs(baseUrl, script) to evaluateJs(baseUrl, script, timeout), and that change was not
 *    cosmetic: the method went from "run a script" to "drive a browser". No JVM script engine can
 *    satisfy the current contract, so it routes to a pluggable [JsRuntime] instead
 *    (docs/FINDINGS.md 4).
 *  - **No trust-all TLS.** See [AgehaHttpClient].
 *
 * ### If this file stops compiling after a parsers bump
 *
 * That is the abstract-member trap, working as designed: MangaLoaderContext is an abstract class,
 * so a new abstract member upstream breaks the build here, loudly, at the one place that can fix
 * it. A new *open* member is silently inherited instead, which is why the Milestone 3
 * compatibility gate re-checks this reflectively against dynamically loaded JARs.
 */
class AgehaMangaLoaderContext(
	override val cookieJar: PersistentCookieJar,
	private val jsRuntime: JsRuntime,
	private val configStore: SourceConfigStore,
	/**
	 * Resolves the parser that should intercept a request, by its source tag. Supplied by the
	 * facade, which owns parser instances and their cache. Null before the registry is ready, or
	 * for a source the current parsers build does not have.
	 */
	private val parserForSource: (MangaSource) -> MangaParser?,
	/**
	 * Resolves a source *name* to this build's own `MangaSource`, for requests Ageha issues on a
	 * source's behalf rather than through a parser. Supplied by the facade, for the same reason as
	 * [parserForSource].
	 *
	 * @see SourceTagInterceptor
	 */
	private val sourceForName: (String) -> MangaSource?,
	/**
	 * The shared HTTP client, owned by the parent and injected -- never built here.
	 *
	 * This is not a style preference. An OkHttp Cache is a DiskLruCache over one directory, and
	 * two Cache instances on the same directory corrupt each other's journal; OkHttp's own docs
	 * call it an error. Two builds coexist whenever the compatibility gate runs, because it
	 * constructs a second context while the live one is still serving, so a context that built its
	 * own client against the default cache directory would corrupt the cache on every update
	 * check. Classloader isolation does not help: the directory is shared regardless.
	 *
	 * One client also means one connection pool and one dispatcher, which is what OkHttp asks for.
	 */
	baseHttpClient: OkHttpClient,
) : MangaLoaderContext() {

	/**
	 * Derived from the shared client with [OkHttpClient.newBuilder], so the cache, connection pool
	 * and dispatcher are the *same objects*, not copies. Only the interceptor stack differs.
	 */
	override val httpClient: OkHttpClient = baseHttpClient
		.newBuilder()
		.apply {
			// ---- the order below is the whole point of this block ----------------------------
			//
			// OkHttp runs application interceptors first-added-outermost, and `newBuilder()`
			// appends to the list it inherited. So simply adding ours put them *below* the base
			// client's CommonHeadersInterceptor -- and that inversion silently discarded a header
			// from every parser that sets one.
			//
			// The mechanism: CommonHeadersInterceptor fills in Referer, User-Agent and
			// Accept-Language wherever they are absent. Running above the parser, it filled them
			// in *first*; then `MangaParserWrapper.intercept` merged the parser's own
			// `getRequestHeaders()` with `OkHttpUtils.mergeWith(..., replace = false)`, which
			// skips every name already present. Ageha's generic defaults therefore beat the
			// source-specific headers 52 parser classes declare -- MadthemeParser, GroupleParser,
			// MangaboxParser, NatsuParser and ManhuaguiParser among them, several of which are
			// base classes serving dozens of sources each.
			//
			// The Android app has it the other way round: parser headers first, Ageha's defaults
			// into the gaps that remain. So CommonHeadersInterceptor is lifted out of the
			// inherited list and re-added below the parser, which is what the rest of this file
			// already assumed was happening.
			val common = interceptors().filterIsInstance<CommonHeadersInterceptor>()
			interceptors().removeAll(common)

			// Above the dispatch, so an image request Ageha built carries a tag by the time the
			// dispatch looks for one.
			addInterceptor(SourceTagInterceptor(sourceForName))
			addInterceptor(ParserDispatchInterceptor(parserForSource))
			// Below the dispatch, so a Referer the parser set deliberately is already in place and
			// is left alone.
			addInterceptor(SourceRefererInterceptor(parserForSource))
			common.forEach { addInterceptor(it) }
			// Inside everything else, so it sees the request as it will actually go out -- the
			// user agent is what a clearance is bound to -- and answers before the parser can
			// swallow a Cloudflare page and take a fallback that returns nothing. A no-op without
			// the browser component.
			addInterceptor(CloudflareClearanceInterceptor(jsRuntime, cookieJar))
		}
		.build()

	override fun getConfig(source: MangaSource): MangaSourceConfig = configStore.configFor(source)

	/**
	 * On Android this returns the WebView's real user agent, so the app's HTTP identity matches
	 * the browser identity that solves Cloudflare challenges. Same idea here: if a browser backend
	 * is installed, use its real user agent; otherwise present a plausible desktop one.
	 *
	 * The mismatch matters. A cf_clearance cookie is only honoured for the exact user agent it was
	 * issued to, so claiming one identity while browsing as another means a solved challenge does
	 * not stay solved.
	 */
	override fun getDefaultUserAgent(): String =
		jsRuntime.browserUserAgent ?: UserAgents.CHROME_DESKTOP

	/**
	 * Release what this context owns, which is deliberately almost nothing.
	 *
	 * The dispatcher, connection pool and cache belong to the parent's client and are shared with
	 * every other context derived from it -- shutting them down here would break the live stack
	 * every time the gate finished evaluating a candidate build. The parent closes them once, in
	 * SourceStack.close.
	 */
	fun close() {
		// Nothing owned. Kept as an explicit no-op so the asymmetry is visible rather than a
		// missing method someone later "fixes" by shutting down the shared client.
	}

	// ---- javascript --------------------------------------------------------------------------

	@Deprecated("Provide a base url")
	@Suppress("OVERRIDE_DEPRECATION")
	override suspend fun evaluateJs(script: String): String? = jsRuntime.evaluate(script)

	override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? =
		jsRuntime.evaluateInPage(baseUrl, script, timeout)

	override suspend fun interceptWebViewRequests(
		url: String,
		interceptorScript: String,
		timeout: Long,
	): List<InterceptedRequest> = jsRuntime.interceptRequests(
		pageUrl = url,
		filterScript = interceptorScript,
		pageScript = null,
		maxRequests = DEFAULT_MAX_INTERCEPTED_REQUESTS,
		timeoutMillis = timeout,
		urlPattern = null,
	).map(InterceptedHttpRequest::toParserModel)

	override suspend fun interceptWebViewRequests(
		url: String,
		config: InterceptionConfig,
	): List<InterceptedRequest> = jsRuntime.interceptRequests(
		pageUrl = url,
		filterScript = config.filterScript,
		pageScript = config.pageScript,
		maxRequests = config.maxRequests,
		timeoutMillis = config.timeoutMs,
		// Handed to the backend rather than applied to the result, because `maxRequests` has to
		// count the requests the parser asked about. Filtering afterwards looked equivalent and
		// was not: ALLMANGA asks for exactly one request, so the backend stopped watching at the
		// page's first stylesheet and this filter then removed it, turning a working source into
		// "did not return a result".
		urlPattern = config.urlPattern,
	).map(InterceptedHttpRequest::toParserModel)

	override suspend fun captureWebViewUrls(
		pageUrl: String,
		urlPattern: Regex,
		timeout: Long,
	): List<String> = jsRuntime.interceptRequests(
		pageUrl = pageUrl,
		filterScript = null,
		pageScript = null,
		maxRequests = DEFAULT_MAX_INTERCEPTED_REQUESTS,
		timeoutMillis = timeout,
		urlPattern = urlPattern,
	).map { it.url }

	/**
	 * A parser has decided that only a human with a browser can get past this.
	 *
	 * Not a suspend function, so it cannot leave a breadcrumb on the coroutine context the way
	 * [app.ageha.core.js.refuseJsCapability] does. It throws a typed exception instead, and the
	 * facade maps that to a SourceFailure the UI can act on.
	 */
	override fun requestBrowserAction(parser: MangaParser, url: String): Nothing =
		throw BrowserActionRequiredException(parser.source.name, url, isCloudflare = false)

	override fun requestCloudflareVerification(parser: MangaParser, url: String): Nothing =
		throw BrowserActionRequiredException(parser.source.name, url, isCloudflare = true)

	// ---- images ------------------------------------------------------------------------------

	override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response {
		// OkHttp 5 made Response.body non-null, so there is no null check to make here.
		val source = response.body.byteStream().use(ImageIO::read)
		checkNotNull(source) { "Could not decode the image to descramble it" }
		val redrawn = redraw(AwtBitmap(source)) as AwtBitmap
		// PNG rather than JPEG: descrambling reassembles tiles, and re-encoding lossily at that
		// point stacks artefacts onto artefacts along every seam.
		return response.newBuilder()
			.body(redrawn.compress("png").toResponseBody("image/png".toMediaTypeOrNull()))
			.build()
	}

	override fun createBitmap(width: Int, height: Int): Bitmap =
		AwtBitmap(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))

	private companion object {
		const val DEFAULT_MAX_INTERCEPTED_REQUESTS = 100
	}
}

private fun InterceptedHttpRequest.toParserModel() = InterceptedRequest(
	url = url,
	method = method,
	headers = headers,
	timestamp = timestampMillis,
	body = body,
)
