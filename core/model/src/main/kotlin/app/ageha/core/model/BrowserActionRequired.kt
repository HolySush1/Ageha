package app.ageha.core.model

/**
 * A parser has decided that only a human with a browser can get past this.
 *
 * Raised when a parser calls `requestBrowserAction` or `requestCloudflareVerification`, both of
 * which upstream declares as `Nothing`-returning -- a parser reaching one has already given up on
 * doing the job itself.
 *
 * It lives in `:core:model` rather than beside the loader context because it crosses the
 * classloader boundary: it is thrown inside the child, where the parsers live, and classified in
 * code that may be running there too. Only types the parent and child agree on may cross, and
 * that is exactly what this module holds.
 *
 * [isCloudflare] separates "clear a challenge" from "sign in or click something", because the
 * remedies differ.
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
