package app.ageha.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.ageha.core.browser.BrowserComponent
import app.ageha.core.browser.BrowserInstallState
import app.ageha.core.designsystem.FailureCopy
import app.ageha.core.js.JsRuntime
import app.ageha.core.model.SourceFailure
import app.ageha.core.network.PersistentCookieJar
import app.ageha.core.network.UserAgents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

/**
 * What each of the failure panel's remedy buttons actually does.
 *
 * These were drawn for two releases and wired to nothing: `SourceFailureNotice` rendered the
 * button and called an `onRemedy` that no screen ever passed, so every press was swallowed by a
 * null-safe call. This is the handler, in one place, because the four screens that can show a
 * failure should not each invent their own answer to "what does Sign in mean".
 */
class Remedies(
	private val browser: BrowserComponent,
	/** For [FailureCopy.Remedy.CLEAR_CHECK]: the browser tier that shows a check to the user. */
	private val jsRuntime: JsRuntime,
	/** Where a passed check's cookies go, so the source's own requests carry them. */
	private val cookieJar: PersistentCookieJar,
	private val notices: NoticeCenter,
	private val scope: CoroutineScope,
) {

	/**
	 * Act on a remedy for a source, given where that source lives.
	 *
	 * [siteUrl] is the source's public address and may be null -- a failure can arrive before
	 * Ageha knows which site produced it, in which case the browser-opening remedies have nowhere
	 * to go and say so rather than opening a blank window. [failure] supplies the exact page a
	 * check is on, which is where passing it has to happen.
	 *
	 * [retry] is the screen asking again. Every remedy that changes something -- an install, a
	 * passed check -- runs it once the change has taken, because the panel's failure is a record of
	 * what happened *before*: leaving it on screen after the fix is the panel insisting the fix did
	 * nothing. That is exactly what the install looked like for two releases.
	 */
	fun handle(
		remedy: FailureCopy.Remedy,
		siteUrl: String?,
		failure: SourceFailure? = null,
		retry: (() -> Unit)? = null,
	) {
		when (remedy) {
			FailureCopy.Remedy.INSTALL_BROWSER -> install(retry)
			FailureCopy.Remedy.CLEAR_CHECK -> clearCheck(checkUrl(failure) ?: siteUrl, retry)
			// The user's own browser. Right for reading about a source or signing in to the site
			// itself; not a way to get Ageha past a check, which is what CLEAR_CHECK is for.
			FailureCopy.Remedy.OPEN_IN_BROWSER, FailureCopy.Remedy.SIGN_IN -> open(siteUrl)
			FailureCopy.Remedy.REPORT -> open(ISSUES_URL)
		}
	}

	/**
	 * Download and start the browser component, then ask the source again.
	 *
	 * Fire-and-forget from the caller's side: progress is reported through
	 * [BrowserComponent.state], which the failure panel reads, so the button relabels itself while
	 * this runs. Only the terminal outcomes become notices -- one per progress tick would bury the
	 * library under a hundred dismissible cards.
	 *
	 * Already installed and running, this finishes at once and simply retries -- so pressing the
	 * button a second time is never a no-op either.
	 */
	private fun install(retry: (() -> Unit)?) {
		scope.launch {
			when (val outcome = browser.install()) {
				is BrowserInstallState.Ready -> {
					notices.post(
						"Browser component ready",
						if (retry != null) {
							"Trying that source again now."
						} else {
							"Sources that need a real browser engine will use it from now on."
						},
					)
					retry?.invoke()
				}

				is BrowserInstallState.Failed -> notices.post(
					"Could not install the browser component",
					// The reason, verbatim. Every plausible cause here is one the user can act on
					// -- no disk space, no network, a proxy blocking the CDN -- and a generic
					// "installation failed" sends them to a forum instead of to their own disk.
					outcome.reason,
					isError = true,
				)

				// Neither is reachable: `install` returns only once it has finished or failed.
				// Left exhaustive so a new state cannot be added without this being revisited.
				BrowserInstallState.Absent, is BrowserInstallState.Working -> Unit
			}
		}
	}

	/**
	 * Get past a check at [url] in Ageha's own browser window, keep the cookies, ask again.
	 *
	 * The component is installed first when it is missing -- a check cannot be passed without a
	 * browser, and making the user find the install elsewhere and come back would be two buttons
	 * for one job. The window starts hidden and comes up only if the check does not clear on its
	 * own; see `JcefJsRuntime.openInteractive`.
	 */
	private fun clearCheck(url: String?, retry: (() -> Unit)?) {
		if (url.isNullOrBlank()) {
			noAddress()
			return
		}
		scope.launch {
			if (!browser.isInstalledOnDisk()) {
				val installed = browser.install()
				if (installed is BrowserInstallState.Failed) {
					notices.post("Could not install the browser component", installed.reason, isError = true)
					return@launch
				}
			}
			val host = runCatching { URI(url).host }.getOrNull() ?: url
			// Presented as the user agent the source's own requests use, which is what the
			// clearance will be checked against when they come back with it.
			val cookies = runCatching { jsRuntime.openInteractive(url, UserAgents.CHROME_DESKTOP) }.getOrNull()
			if (cookies.isNullOrEmpty()) {
				notices.post(
					"Did not get past the check",
					"$host did not finish its check, or the window was closed first. The source " +
						"is left as it was.",
					isError = true,
				)
				return@launch
			}
			cookieJar.saveBrowserCookies(url, cookies)
			notices.post("Through the check", "$host let Ageha in. Trying the source again now.")
			retry?.invoke()
		}
	}

	/** The page a check is actually on, when the failure knows it. */
	private fun checkUrl(failure: SourceFailure?): String? = when (failure) {
		is SourceFailure.ChallengeRequired -> failure.url
		is SourceFailure.Blocked -> failure.url
		else -> null
	}?.takeIf { it.isNotBlank() }

	/**
	 * Hand [url] to the system browser, with the same failure reporting the remedies get.
	 *
	 * Public for the Add site dialog's "Request it upstream". Two copies of "open a link, and say
	 * so when Windows will not" would drift, and the one that drifted would be the one that failed
	 * silently.
	 */
	fun openLink(url: String) = open(url)

	private fun open(url: String?) {
		if (url.isNullOrBlank()) {
			noAddress()
			return
		}
		runCatching {
			// Checked rather than assumed: on a headless JVM, and on a Linux box with no
			// xdg-open, `Desktop.getDesktop()` throws instead of returning something inert.
			check(Desktop.isDesktopSupported()) { "no desktop integration on this JVM" }
			Desktop.getDesktop().browse(URI(url))
		}.onFailure { error ->
			notices.post(
				"Could not open your browser",
				"Ageha could not hand $url to the system browser: ${error.message}",
				isError = true,
			)
		}
	}

	private fun noAddress() {
		notices.post(
			"No address for that source",
			"Ageha does not know this source's web address, so there is nothing to open. " +
				"That normally means the source is missing from the parsers build currently " +
				"loaded.",
			isError = true,
		)
	}

	private companion object {
		const val ISSUES_URL = "https://github.com/Kotatsu-Redo/Ageha/issues"
	}
}

/**
 * The remedy button's label while an install is running, or null when none is.
 *
 * Lives here rather than in `:core:designsystem` because it is the one place that knows both what
 * a browser install is and how Ageha phrases things. The design system is handed a finished
 * sentence and never learns that a browser component exists.
 */
@Composable
fun browserInstallProgress(browser: BrowserComponent): String? {
	val state by browser.state.collectAsState()
	val working = state as? BrowserInstallState.Working ?: return null
	// A percentage only where jcefmaven can estimate one. Extraction reports no total, so a
	// determinate figure there would be invented -- and a bar that sticks at a made-up 90% is
	// worse than an honest ellipsis for an operation that legitimately takes minutes.
	return working.fraction
		?.let { "${working.step}, ${(it * 100).toInt()}%" }
		?: "${working.step}…"
}
