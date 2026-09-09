package app.ageha.core.js

import app.ageha.core.model.JsCapability

/**
 * One [JsRuntime] made of two, so that callers never have to know there are two.
 *
 * Ageha's JavaScript layer is split by cost, not by tier. Rhino is embedded, starts in
 * milliseconds and ships with the app; a browser is 200MB, starts in seconds and is an optional
 * download. [JsCapability.PLAIN_SCRIPT] is the only tier Rhino can serve, and it is also the tier
 * asked for most often -- around 257 of the 1,360 sources hit it on an anti-bot fallback -- so
 * routing it to a browser merely because one happens to be installed would make the common case
 * pay the rare case's price.
 *
 * So: each call goes to the cheapest backend that claims the capability, and this runtime's own
 * [capabilities] is the union. Both parts are honest on their own; this only decides which to ask.
 */
class CompositeJsRuntime(
	/** Always present. Serves [JsCapability.PLAIN_SCRIPT]. */
	private val script: JsRuntime,
	/**
	 * The browser, when the optional component is installed and running.
	 *
	 * Never null in practice once the module is wired -- what changes at runtime is its
	 * *capability set*, which is empty until CEF has actually initialised. Asking the backend each
	 * time rather than caching the answer is what lets an install take effect immediately, without
	 * a restart and without this class being told that anything happened.
	 */
	private val browser: JsRuntime?,
) : JsRuntime {

	override val capabilities: Set<JsCapability>
		get() = script.capabilities + browser?.capabilities.orEmpty()

	/**
	 * The browser's answer, when there is a browser running, and null otherwise.
	 *
	 * A script engine has no user-agent to report, so this is the browser's answer or no answer --
	 * never an invented one. See [JsRuntime.browserUserAgent] for why inventing one is harmful.
	 */
	override val browserUserAgent: String?
		get() = browser?.browserUserAgent

	override suspend fun evaluate(script: String): String? =
		pick(JsCapability.PLAIN_SCRIPT).evaluate(script)

	override suspend fun evaluateInPage(baseUrl: String, script: String, timeoutMillis: Long): String? =
		pick(JsCapability.PAGE_CONTEXT).evaluateInPage(baseUrl, script, timeoutMillis)

	override suspend fun interceptRequests(
		pageUrl: String,
		filterScript: String?,
		pageScript: String?,
		maxRequests: Int,
		timeoutMillis: Long,
		urlPattern: Regex?,
	): List<InterceptedHttpRequest> = pick(JsCapability.REQUEST_INTERCEPTION)
		.interceptRequests(pageUrl, filterScript, pageScript, maxRequests, timeoutMillis, urlPattern)

	override suspend fun openInteractive(url: String, userAgent: String?): Boolean =
		pick(JsCapability.INTERACTIVE_BROWSER).openInteractive(url, userAgent)

	override suspend fun close() {
		script.close()
		browser?.close()
	}

	/**
	 * The cheapest backend that claims [capability], or a throw that names what is missing.
	 *
	 * Throwing here rather than delegating blindly is what keeps the failure legible: the
	 * exception carries the capability, which is what `SourceFailureMapper` turns into "this
	 * source needs the browser component" rather than a stack trace about a null page.
	 */
	private fun pick(capability: JsCapability): JsRuntime = when {
		capability in script.capabilities -> script
		browser != null && capability in browser.capabilities -> browser
		else -> throw JsUnavailableException(capability)
	}
}
