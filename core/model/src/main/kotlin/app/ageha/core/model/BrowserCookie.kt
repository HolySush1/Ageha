package app.ageha.core.model

/**
 * A cookie a real browser earned, on its way into Ageha's own cookie jar.
 *
 * The browser component and the HTTP client keep separate cookie stores -- Chromium's lives in its
 * profile on disk, OkHttp's in `PersistentCookieJar` -- and on Android they are one store, which is
 * the only reason a Cloudflare check cleared in a WebView lets the app through afterwards. This is
 * the hand-off that makes Ageha's two behave the same way: the browser reports what it was given,
 * and the jar takes it.
 *
 * Plain data in `:core:model` rather than an OkHttp `Cookie`, because it crosses both the module
 * wall (the browser module knows nothing of OkHttp) and the classloader wall (it is produced
 * beside the parsers and consumed in the app).
 */
data class BrowserCookie(
	val name: String,
	val value: String,
	/**
	 * As Chromium reports it: with a leading dot for a cookie that covers subdomains, without one
	 * for a host-only cookie. The distinction survives the hand-off -- widening a host-only cookie
	 * to every subdomain would send it somewhere the site never meant it to go.
	 */
	val domain: String,
	val path: String,
	val secure: Boolean,
	val httpOnly: Boolean,
	/** Null for a session cookie, which then stays in memory only, as sessions should. */
	val expiresAtMillis: Long?,
)
