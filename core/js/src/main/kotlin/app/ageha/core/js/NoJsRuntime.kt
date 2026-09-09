package app.ageha.core.js

import app.ageha.core.model.JsCapability

/**
 * The JavaScript backend Ageha ships with today: none.
 *
 * This is not a silent stub. It is the parsers library's own documented default -- `MangaLoaderContext`
 * declares `requestBrowserAction`, `interceptWebViewRequests` and friends as open members that
 * throw `UnsupportedOperationException` precisely so a host without a browser can exist. We
 * refuse the same way, with one addition: every refusal is recorded on the coroutine context
 * (see [refuseJsCapability]) so the facade can turn it into an actionable prompt rather than a
 * generic error.
 *
 * Measured against the parsers library at 434030d481, this loses about 20 of 1360 sources
 * outright and the anti-bot fallback path of up to 257 more. The other ~1080 never call in here.
 */
object NoJsRuntime : JsRuntime {

	override val capabilities: Set<JsCapability> = emptySet()

	override val browserUserAgent: String? = null

	override suspend fun evaluate(script: String): String? =
		refuseJsCapability(JsCapability.PLAIN_SCRIPT)

	override suspend fun evaluateInPage(baseUrl: String, script: String, timeoutMillis: Long): String? =
		refuseJsCapability(JsCapability.PAGE_CONTEXT)

	override suspend fun interceptRequests(
		pageUrl: String,
		filterScript: String?,
		pageScript: String?,
		maxRequests: Int,
		timeoutMillis: Long,
		urlPattern: Regex?,
	): List<InterceptedHttpRequest> = refuseJsCapability(JsCapability.REQUEST_INTERCEPTION)

	override suspend fun openInteractive(url: String, userAgent: String?): Boolean =
		refuseJsCapability(JsCapability.INTERACTIVE_BROWSER)

	override suspend fun close() = Unit
}
