package app.ageha.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.ageha.core.browser.BrowserComponent
import app.ageha.core.browser.BrowserInstallState
import app.ageha.core.designsystem.FailureCopy
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
	private val notices: NoticeCenter,
	private val scope: CoroutineScope,
) {

	/**
	 * Act on a remedy for a source, given where that source lives.
	 *
	 * [siteUrl] is the source's public address and may be null -- a failure can arrive before
	 * Ageha knows which site produced it, in which case the browser-opening remedies have nowhere
	 * to go and say so rather than opening a blank window.
	 */
	fun handle(remedy: FailureCopy.Remedy, siteUrl: String?) {
		when (remedy) {
			FailureCopy.Remedy.INSTALL_BROWSER -> install()
			// Signing in and clearing a challenge are the same action from Ageha's side: open the
			// site in the user's own browser and let them do it there, in a session that keeps its
			// own cookies. They differ only in what the user does once it opens, which the panel's
			// copy has already told them.
			FailureCopy.Remedy.OPEN_IN_BROWSER, FailureCopy.Remedy.SIGN_IN -> open(siteUrl)
			FailureCopy.Remedy.REPORT -> open(ISSUES_URL)
		}
	}

	/**
	 * Download and start the browser component.
	 *
	 * Fire-and-forget from the caller's side: progress is reported through
	 * [BrowserComponent.state], which the failure panel reads, so the button relabels itself while
	 * this runs. Only the terminal outcomes become notices -- one per progress tick would bury the
	 * library under a hundred dismissible cards.
	 */
	private fun install() {
		scope.launch {
			when (val outcome = browser.install()) {
				is BrowserInstallState.Ready -> notices.post(
					"Browser component installed",
					"Sources that need a real browser engine will work from now on. Try the one " +
						"that sent you here again.",
				)

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
	 * Hand [url] to the system browser, with the same failure reporting the remedies get.
	 *
	 * Public for the Add site dialog's "Request it upstream". Two copies of "open a link, and say
	 * so when Windows will not" would drift, and the one that drifted would be the one that failed
	 * silently.
	 */
	fun openLink(url: String) = open(url)

	private fun open(url: String?) {
		if (url.isNullOrBlank()) {
			notices.post(
				"No address for that source",
				"Ageha does not know this source's web address, so there is nothing to open. " +
					"That normally means the source is missing from the parsers build currently " +
					"loaded.",
				isError = true,
			)
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
