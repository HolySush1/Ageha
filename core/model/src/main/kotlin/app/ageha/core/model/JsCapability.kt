package app.ageha.core.model

/**
 * What a source needs from the JavaScript layer.
 *
 * The tiers come from measuring the parsers library at commit 434030d481 (docs/FINDINGS.md 4).
 * Of 1360 sources, roughly 1080 need none of this, up to 257 need [PLAIN_SCRIPT] on a
 * conditional anti-bot fallback, and about 20 need a real browser.
 */
enum class JsCapability {

	/**
	 * Run a self-contained script with no DOM, no origin and no network, and return its result.
	 *
	 * This is the deprecated `evaluateJs(script)` form. In practice it is the NetShield / slowAES
	 * path in `MangaReaderParser`, which fires only when a site serves an anti-bot interstitial.
	 * The script is pure computation, so an embedded engine such as QuickJS handles it exactly --
	 * which is what the parsers library's own test suite does.
	 */
	PLAIN_SCRIPT,

	/**
	 * Navigate to a url, let the site's own JavaScript run, then evaluate a script in that page
	 * and await the promise it returns.
	 *
	 * This is `evaluateJs(baseUrl, script, timeout)`, and it is a *headless browser* contract, not
	 * a script-engine one -- the scripts read `document.documentElement.outerHTML` after waiting on
	 * `readyState` and on content the site renders client-side. No JVM JS engine can satisfy it.
	 */
	PAGE_CONTEXT,

	/** Capture the HTTP requests a page makes while loading (`interceptWebViewRequests`). */
	REQUEST_INTERCEPTION,

	/** Read `window.localStorage` for a specific origin. */
	LOCAL_STORAGE,

	/**
	 * Show the user a real browser window so they can clear a captcha or log in
	 * (`requestBrowserAction` / `requestCloudflareVerification`).
	 */
	INTERACTIVE_BROWSER,
	;

	/** True when only a full browser engine can provide this. [PLAIN_SCRIPT] is the exception. */
	val requiresBrowser: Boolean
		get() = this != PLAIN_SCRIPT
}
