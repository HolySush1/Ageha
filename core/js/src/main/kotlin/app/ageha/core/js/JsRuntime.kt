package app.ageha.core.js

import app.ageha.core.model.JsCapability

/**
 * Ageha's JavaScript backend, behind one interface so the backend can be swapped without
 * touching anything that calls it.
 *
 * Three implementations are planned (docs/FINDINGS.md 4):
 *
 *  - [NoJsRuntime] -- ships by default, provides nothing, fails legibly. About 1080 of 1360
 *    sources never notice.
 *  - a QuickJS backend -- provides [JsCapability.PLAIN_SCRIPT]. Small, embedded, and the same
 *    engine the parsers library's own test suite runs against, which is the strongest fidelity
 *    argument available.
 *  - a Playwright backend -- provides everything, and is an optional on-demand download because
 *    it costs a browser plus a Node driver process.
 *
 * Implementations must serialise calls. Both QuickJS and a browser page are single-context;
 * concurrent evaluation is not safe and the Android implementation guards it with a mutex for
 * exactly this reason.
 */
interface JsRuntime {

	/** What this backend can actually do. Callers check before calling; the UI checks before offering. */
	val capabilities: Set<JsCapability>

	/**
	 * Run a self-contained script. No DOM, no origin, no network.
	 *
	 * @return the script's result as a string, or null if it produced nothing.
	 * @throws JsUnavailableException if [JsCapability.PLAIN_SCRIPT] is not in [capabilities].
	 */
	suspend fun evaluate(script: String): String?

	/**
	 * Load [baseUrl] in a browser, let the page's own scripts run, then evaluate [script] in that
	 * page and await whatever promise it returns.
	 *
	 * The result must be **JSON-encoded**, matching what Android's `WebView.evaluateJavascript`
	 * hands back -- several parsers strip the encoding themselves with a local
	 * `decodeWebViewString()` helper, so returning a raw string breaks them quietly rather than
	 * loudly (docs/FINDINGS.md 4).
	 *
	 * @throws JsUnavailableException if [JsCapability.PAGE_CONTEXT] is not in [capabilities].
	 */
	suspend fun evaluateInPage(baseUrl: String, script: String, timeoutMillis: Long): String?

	/**
	 * Load [pageUrl] and capture the requests it makes, keeping those for which [filterScript]
	 * evaluates truthy.
	 *
	 * @throws JsUnavailableException if [JsCapability.REQUEST_INTERCEPTION] is not in [capabilities].
	 */
	suspend fun interceptRequests(
		pageUrl: String,
		filterScript: String?,
		pageScript: String?,
		maxRequests: Int,
		timeoutMillis: Long,
		/**
		 * Which requests the caller actually wants, and therefore which ones [maxRequests] counts.
		 *
		 * The pattern belongs down here rather than in a filter applied to the returned list, and
		 * that is not a tidiness argument. Parsers ask for very few requests -- ALLMANGA asks for
		 * exactly one -- and a backend that counts every request a page makes reaches the cap on
		 * the first stylesheet, stops watching, and hands back one asset instead of the API call
		 * the parser was waiting for. Filtering afterwards cannot recover what was never captured.
		 */
		urlPattern: Regex? = null,
	): List<InterceptedHttpRequest>

	/**
	 * Show the user a browser window at [url] so they can clear a challenge or sign in, and
	 * return once they are done. Cookies land in the shared jar.
	 *
	 * @throws JsUnavailableException if [JsCapability.INTERACTIVE_BROWSER] is not in [capabilities].
	 */
	suspend fun openInteractive(url: String, userAgent: String?): Boolean

	/**
	 * The user-agent this backend really presents, when it drives a real browser.
	 *
	 * Null means "we are not a browser, pick a plausible one". Claiming a Chrome user-agent while
	 * presenting a bare JVM TLS fingerprint is precisely what bot detection looks for, so this is
	 * not cosmetic (docs/ARCHITECTURE.md 8).
	 */
	val browserUserAgent: String?

	/** Release native resources. Safe to call more than once. */
	suspend fun close()
}

/** One request captured by [JsRuntime.interceptRequests]. */
data class InterceptedHttpRequest(
	val url: String,
	val method: String,
	val headers: Map<String, String>,
	val timestampMillis: Long,
	val body: String?,
)

/**
 * Thrown when a source asks for a JavaScript capability that is not installed.
 *
 * Extends [UnsupportedOperationException] on purpose: that is what the parsers library's own
 * `MangaLoaderContext` defaults throw, so parsers that already handle the base-class behaviour
 * keep handling it, while Ageha gets the extra [capability] detail it needs to offer a remedy.
 */
class JsUnavailableException(
	val capability: JsCapability,
) : UnsupportedOperationException(
	"JavaScript capability '$capability' is not available in this Ageha installation.",
)
