package app.ageha.core.model

import java.io.IOException

/**
 * Why a source call failed, in terms the UI can act on.
 *
 * Every call through the facade fails as one of these. The point is that the caller never has to
 * pattern-match on parser-library exception types (which churn) or, worse, on message strings.
 *
 * [MissingJsRuntime] exists because of a specific requirement: when a source fails only because
 * the optional browser component is not installed, that must be distinguishable from the network
 * being down, and it must reach the user as an offer to install the component rather than as a
 * generic error. See [app.ageha.core.js.JsAttemptRecorder] for how it survives a parser that
 * swallows the underlying exception.
 */
sealed class SourceFailure(
	message: String,
	cause: Throwable?,
) : Exception(message, cause) {

	/** The source name this failure relates to, for UI attribution. */
	abstract val sourceName: String

	/** Whether retrying the identical call could plausibly succeed without user action. */
	open val isTransient: Boolean get() = false

	/**
	 * The source needs a JavaScript capability Ageha cannot currently provide.
	 *
	 * This is not an error the user caused and not one a retry fixes. The UI must offer the
	 * remedy: install the browser component (for [JsCapability.requiresBrowser]), or report a
	 * genuine bug (for [JsCapability.PLAIN_SCRIPT], which ships with the app).
	 */
	class MissingJsRuntime(
		override val sourceName: String,
		val capability: JsCapability,
		cause: Throwable? = null,
	) : SourceFailure(
		"Source '$sourceName' needs the '$capability' JavaScript capability, which is not installed.",
		cause,
	)

	/** Transport failed: DNS, TLS, timeout, connection reset. */
	class Network(
		override val sourceName: String,
		cause: IOException,
	) : SourceFailure("Could not reach source '$sourceName': ${cause.message}", cause) {
		override val isTransient get() = true
	}

	/** The site answered, but not with what the parser expected. Usually means the site changed. */
	class Unparseable(
		override val sourceName: String,
		val url: String?,
		cause: Throwable?,
	) : SourceFailure("Source '$sourceName' returned something the parser could not read.", cause)

	/**
	 * The server answered and refused us -- typically a 403 from bot protection.
	 *
	 * Kept separate from [Network] because the two need opposite responses. A network failure is
	 * worth retrying; being blocked is not, and the remedy is usually the browser component or a
	 * different mirror. Reporting this as "could not reach the source" sends the user to check
	 * their connection when their connection is fine.
	 */
	class Blocked(
		override val sourceName: String,
		val statusCode: Int,
		val url: String?,
		cause: Throwable? = null,
	) : SourceFailure(
		"Source '$sourceName' refused the request (HTTP $statusCode).",
		cause,
	)

	/** The site is behind a challenge that needs a browser and, usually, a human. */
	class ChallengeRequired(
		override val sourceName: String,
		val url: String,
		cause: Throwable? = null,
	) : SourceFailure("Source '$sourceName' is behind a verification challenge.", cause)

	/** The source needs credentials Ageha does not have. */
	class AuthRequired(
		override val sourceName: String,
		cause: Throwable? = null,
	) : SourceFailure("Source '$sourceName' requires you to sign in.", cause)

	/** Rate limited. [retryAfterMillis] is null when the site did not say. */
	class RateLimited(
		override val sourceName: String,
		val retryAfterMillis: Long?,
		cause: Throwable? = null,
	) : SourceFailure("Source '$sourceName' is rate limiting us.", cause) {
		override val isTransient get() = true
	}

	/** The manga, chapter or page is gone. */
	class NotFound(
		override val sourceName: String,
		cause: Throwable? = null,
	) : SourceFailure("Not found on source '$sourceName'.", cause)

	/** Legal or regional block, or the source withdrew the content. */
	class ContentUnavailable(
		override val sourceName: String,
		val reason: String?,
		cause: Throwable? = null,
	) : SourceFailure(
		"Content is unavailable on source '$sourceName'" + (reason?.let { ": $it" } ?: "."),
		cause,
	)

	/**
	 * The named source is not present in the currently loaded parsers build.
	 *
	 * Expected and survivable: a user's library can outlive a source being renamed or dropped
	 * upstream. Never delete the user's row -- show it as unavailable.
	 */
	class UnknownSource(
		override val sourceName: String,
	) : SourceFailure("Source '$sourceName' is not in the parser version currently loaded.", null)

	/** Anything the facade could not classify. Carries the original for the bug report. */
	class Unknown(
		override val sourceName: String,
		cause: Throwable?,
	) : SourceFailure(
		"Source '$sourceName' failed unexpectedly: ${cause?.javaClass?.simpleName ?: "unknown"}",
		cause,
	)
}
