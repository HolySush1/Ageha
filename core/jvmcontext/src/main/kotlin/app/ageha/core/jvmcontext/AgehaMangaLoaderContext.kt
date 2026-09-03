package app.ageha.core.jvmcontext

import app.ageha.core.js.InterceptedHttpRequest
import app.ageha.core.js.JsRuntime
import app.ageha.core.network.AgehaHttpClient
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
import java.util.Locale
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
	httpClientFactory: (PersistentCookieJar) -> OkHttpClient = { AgehaHttpClient.build(it) },
) : MangaLoaderContext() {

	override val httpClient: OkHttpClient = httpClientFactory(cookieJar)
		.newBuilder()
		// Added last so it runs innermost of the application interceptors: the parser should see
		// headers the common interceptors have already set.
		.addInterceptor(ParserDispatchInterceptor(parserForSource))
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

	override fun getPreferredLocales(): List<Locale> = listOf(Locale.getDefault())

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
	).map(InterceptedHttpRequest::toParserModel)

	override suspend fun interceptWebViewRequests(
		url: String,
		config: InterceptionConfig,
	): List<InterceptedRequest> {
		val captured = jsRuntime.interceptRequests(
			pageUrl = url,
			filterScript = config.filterScript,
			pageScript = config.pageScript,
			maxRequests = config.maxRequests,
			timeoutMillis = config.timeoutMs,
		).map(InterceptedHttpRequest::toParserModel)
		// urlPattern is applied here rather than in the backend, so every backend behaves alike.
		val pattern = config.urlPattern ?: return captured
		return captured.filter { pattern.containsMatchIn(it.url) }
	}

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
	).map { it.url }.filter { urlPattern.containsMatchIn(it) }

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

/**
 * Thrown when a parser calls requestBrowserAction or requestCloudflareVerification.
 *
 * Both are declared Nothing-returning upstream, so a parser that reaches one has already given up
 * on doing the job itself. [isCloudflare] separates "clear a challenge" from "sign in or click
 * something", because the remedies differ.
 */
class BrowserActionRequiredException(
	val sourceName: String,
	val url: String,
	val isCloudflare: Boolean,
) : UnsupportedOperationException(
	if (isCloudflare) {
		"Source '" + sourceName + "' needs a browser to clear a Cloudflare challenge at " + url
	} else {
		"Source '" + sourceName + "' needs a browser for an interactive step at " + url
	},
)
