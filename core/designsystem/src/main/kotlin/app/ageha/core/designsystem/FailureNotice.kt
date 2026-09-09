package app.ageha.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.ageha.core.model.JsCapability
import app.ageha.core.model.SourceFailure

/**
 * What the user is told, and what they can do about it, for each kind of source failure.
 *
 * Kept in one place so that every screen phrases the same failure the same way, and so the
 * *actions* stay attached to the reasons. A generic "Something went wrong. Retry?" is the failure
 * mode this type exists to prevent: half of these are not retryable and two of them have a
 * specific remedy that has nothing to do with retrying.
 */
data class FailureCopy(
	val headline: String,
	val detail: String,
	/** Retrying could plausibly work without the user doing anything first. */
	val canRetry: Boolean,
	/** A remedy that is not "try again", if one exists. */
	val remedy: Remedy? = null,
) {
	enum class Remedy {
		/** Install the optional browser component. */
		INSTALL_BROWSER,

		/** Open the source in a real browser so the user can clear a challenge themselves. */
		OPEN_IN_BROWSER,

		/** Sign in to the source. */
		SIGN_IN,

		/** This is a bug in Ageha or the parsers build, not something the user can fix. */
		REPORT,
	}
}

/**
 * Turns a [SourceFailure] into something worth reading.
 *
 * The [SourceFailure.MissingJsRuntime] branch is the reason this function exists. Around 257 of
 * the 1360 sources fall back to a JavaScript anti-bot check only *sometimes*, so the same source
 * works for months and then does not. Reporting that as a network error would send the user to
 * check their connection, which is fine and wrong. It is reported as what it is -- a missing
 * optional component -- with the offer to install it.
 *
 * The JS tiers get different copy on purpose. [JsCapability.PLAIN_SCRIPT] is meant to ship with
 * the app, so needing it and not having it is a *bug* rather than a missing add-on, and saying
 * "install the browser component" there would send the user off to fix something that is not
 * broken on their side.
 */
fun describe(failure: SourceFailure): FailureCopy = when (failure) {
	// Split on `requiresBrowser` rather than on every tier by name. The capability enum tracks
	// what the parsers library asks for and gains members as upstream does; a `when` over each
	// constant would break the build on a version bump for a case whose copy is already correct.
	is SourceFailure.MissingJsRuntime -> when {
		!failure.capability.requiresBrowser -> FailureCopy(
			headline = "This source needs the script engine",
			detail = "${failure.sourceName} hit an anti-bot check that Ageha should be able to " +
				"solve on its own. The built-in script engine is missing or failed to start, " +
				"which is a bug rather than something you need to install.",
			canRetry = false,
			remedy = FailureCopy.Remedy.REPORT,
		)
		failure.capability == JsCapability.INTERACTIVE_BROWSER -> FailureCopy(
			headline = "${failure.sourceName} needs you to clear a check",
			detail = "This source wants a captcha solved or a login completed in a real browser " +
				"window. Ageha can open one for you once the browser component is installed.",
			canRetry = false,
			remedy = FailureCopy.Remedy.INSTALL_BROWSER,
		)
		else -> FailureCopy(
			headline = "This source needs the browser component",
			detail = "${failure.sourceName} runs its own JavaScript before it will serve pages, " +
				"which needs a real browser engine. It is an optional download, and Ageha works " +
				"without it for around 1,340 of its 1,360 sources.",
			canRetry = false,
			remedy = FailureCopy.Remedy.INSTALL_BROWSER,
		)
	}

	is SourceFailure.Network -> FailureCopy(
		headline = "Could not reach ${failure.sourceName}",
		detail = "The site did not answer. It may be down, or your connection may be interrupted.",
		canRetry = true,
	)

	is SourceFailure.Blocked -> FailureCopy(
		headline = "${failure.sourceName} refused the request",
		detail = "The site answered with HTTP ${failure.statusCode}. This is usually bot " +
			"protection rather than an outage -- opening it in a browser once often clears it.",
		canRetry = false,
		remedy = FailureCopy.Remedy.OPEN_IN_BROWSER,
	)

	is SourceFailure.ChallengeRequired -> FailureCopy(
		headline = "${failure.sourceName} wants a challenge solved",
		detail = "The site is showing an anti-bot interstitial. Opening it in a browser and " +
			"passing the check will let Ageha through afterwards.",
		canRetry = false,
		remedy = FailureCopy.Remedy.OPEN_IN_BROWSER,
	)

	is SourceFailure.AuthRequired -> FailureCopy(
		headline = "${failure.sourceName} needs you signed in",
		detail = "This source only serves content to logged-in accounts.",
		canRetry = false,
		remedy = FailureCopy.Remedy.SIGN_IN,
	)

	is SourceFailure.RateLimited -> FailureCopy(
		headline = "Slow down for ${failure.sourceName}",
		detail = failure.retryAfterMillis
			?.let { "The site asked for a pause. Try again in about ${it / 1000} seconds." }
			?: "The site is rate-limiting Ageha. Waiting a moment usually clears it.",
		canRetry = true,
	)

	is SourceFailure.Unparseable -> FailureCopy(
		headline = "${failure.sourceName} returned something unexpected",
		detail = "The page loaded but did not look the way the parser expects. This normally " +
			"means the site changed its layout, and it is fixed by a parsers update rather " +
			"than by anything you can do.",
		canRetry = false,
		remedy = FailureCopy.Remedy.REPORT,
	)

	is SourceFailure.NotFound -> FailureCopy(
		headline = "Not found on ${failure.sourceName}",
		detail = "The site no longer has this. It may have been removed or relicensed.",
		canRetry = false,
	)

	is SourceFailure.ContentUnavailable -> FailureCopy(
		headline = "Unavailable on ${failure.sourceName}",
		detail = failure.reason ?: "The site has this listed but will not serve it.",
		canRetry = false,
	)

	is SourceFailure.UnknownSource -> FailureCopy(
		headline = "Ageha does not know this source",
		detail = "'${failure.sourceName}' is not in the parsers build that is currently loaded. " +
			"It may have been removed upstream, or it may arrive in a newer build.",
		canRetry = false,
	)

	is SourceFailure.Unknown -> FailureCopy(
		headline = "${failure.sourceName} failed unexpectedly",
		detail = failure.cause?.message ?: "No further detail was available.",
		canRetry = true,
		remedy = FailureCopy.Remedy.REPORT,
	)
}

/**
 * The inline failure panel.
 *
 * Rendered *within* the content area rather than as a dialog or a snackbar, because a source
 * failing is not modal: the results already on screen stay usable, and the user may simply pick a
 * different source. A dialog would demand acknowledgement for something that needs none.
 */
@Composable
fun SourceFailureNotice(
	failure: SourceFailure,
	modifier: Modifier = Modifier,
	onRetry: (() -> Unit)? = null,
	onRemedy: ((FailureCopy.Remedy) -> Unit)? = null,
	/**
	 * What the remedy is doing right now, when it is doing something slow.
	 *
	 * A string rather than a percentage or a state enum, because the only caller that has anything
	 * to say here is the browser install -- 200MB over somebody's connection -- and what it has to
	 * say ("Downloading Chromium, 43%") is already a sentence by the time it reaches this. Keeping
	 * it a string is also what stops this module having to know that a browser component exists.
	 *
	 * Non-null replaces the button's label and disables it, so a second click cannot start a
	 * second download.
	 */
	remedyProgress: String? = null,
) {
	val copy = describe(failure)
	Column(
		modifier = modifier
			.fillMaxWidth()
			.clip(MaterialTheme.shapes.medium)
			.background(MaterialTheme.colorScheme.errorContainer)
			.padding(AgehaSpacing.lg),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
	) {
		Text(
			copy.headline,
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onErrorContainer,
		)
		Text(
			copy.detail,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onErrorContainer,
		)
		Row(horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm)) {
			// Drawn only when something is actually listening.
			//
			// This button used to render unconditionally and call `onRemedy?.invoke(remedy)`, and
			// no screen in the application ever passed an `onRemedy` -- so the null-safe call
			// swallowed every press and the most prominent control on the panel did nothing at
			// all. A remedy with no handler is not a remedy, and the copy above already explains
			// the situation without it.
			copy.remedy?.takeIf { onRemedy != null }?.let { remedy ->
				OutlinedButton(
					onClick = { onRemedy?.invoke(remedy) },
					enabled = remedyProgress == null,
					// The label and border default to `primary`, which on `errorContainer` is
					// indigo on dark red -- low enough contrast that the button read as disabled
					// even while it was enabled. Coloured from the container's own pair instead,
					// which is contrast-guaranteed against it in every skin.
					colors = ButtonDefaults.outlinedButtonColors(
						contentColor = MaterialTheme.colorScheme.onErrorContainer,
					),
					border = BorderStroke(1.dp, MaterialTheme.colorScheme.onErrorContainer),
				) {
					Text(remedyProgress ?: remedyLabel(remedy))
				}
			}
			if (copy.canRetry && onRetry != null) {
				TextButton(onClick = onRetry) { Text("Try again") }
			}
		}
	}
}

private fun remedyLabel(remedy: FailureCopy.Remedy): String = when (remedy) {
	FailureCopy.Remedy.INSTALL_BROWSER -> "Install browser component"
	FailureCopy.Remedy.OPEN_IN_BROWSER -> "Open in browser"
	FailureCopy.Remedy.SIGN_IN -> "Sign in"
	FailureCopy.Remedy.REPORT -> "Report this"
}

/** A one-line version for a list footer, where a full panel would be too much. */
@Composable
fun InlineFailureLine(failure: SourceFailure, modifier: Modifier = Modifier) {
	Text(
		text = describe(failure).headline,
		style = AgehaTextStyles.metadata,
		color = MaterialTheme.colorScheme.error,
		modifier = modifier.padding(AgehaSpacing.md),
	)
}
