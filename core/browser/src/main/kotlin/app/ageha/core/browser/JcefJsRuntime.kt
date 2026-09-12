package app.ageha.core.browser

import app.ageha.core.js.InterceptedHttpRequest
import app.ageha.core.js.JsRuntime
import app.ageha.core.js.JsUnavailableException
import app.ageha.core.js.refuseJsCapability
import app.ageha.core.model.BrowserCookie
import app.ageha.core.model.JsCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefDevToolsClient
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * The browser-backed half of Ageha's JavaScript layer.
 *
 * Provides every tier except [JsCapability.PLAIN_SCRIPT], which is left to Rhino: a pure
 * computation does not need 200MB of Chromium started to run it, and spinning one up for a
 * NetShield check would turn a 3ms operation into a multi-second one. `CompositeJsRuntime` joins
 * the two so that callers see one runtime with one capability set.
 *
 * ## Why every operation gets its own browser
 *
 * A browser is created per call and destroyed at the end of it, which costs a page load each time
 * and is worth it. Sources otherwise hand each other state -- localStorage, sessionStorage,
 * service workers, a half-finished challenge -- and a parser that silently reads the residue of
 * the previous source's page is a bug that only appears when two particular sources are used in
 * sequence, which is close to undebuggable. Cookies are the deliberate exception: they live in
 * CEF's profile on disk, because staying signed in is the entire point of having a browser.
 *
 * ## Why the calls are serialised
 *
 * The [JsRuntime] contract requires it, and CEF's threading is the reason: browser creation and
 * destruction must be ordered against each other. The Android implementation this is a port of
 * guards its WebView with a mutex for the same reason.
 */
class JcefJsRuntime(
	private val component: BrowserComponent,
	/**
	 * The viewport pages are told they have.
	 *
	 * Not zero, and not tiny. Sites gate content on viewport size, and a 0x0 browser both reads as
	 * a bot and makes every "is this element visible" check in a lazy-loading page answer no --
	 * which for a manga reader means a page of images that never start loading.
	 */
	private val viewportWidth: Int = 1280,
	private val viewportHeight: Int = 800,
	/**
	 * Whether a check is shown the moment it is detected, rather than attempted out of sight first.
	 *
	 * A supplier rather than a value because it mirrors a user setting, and this object is built
	 * once at startup: read as a value it would take a restart to change, which for a switch whose
	 * whole subject is "ask me first" is the wrong way round.
	 *
	 * Defaults to showing. A caller that says nothing -- the CLI does -- gets the window.
	 */
	private val showChecksImmediately: () -> Boolean = { true },
) : JsRuntime {


	private val lock = Mutex()

	/**
	 * Everything but [JsCapability.PLAIN_SCRIPT], once Chromium is installed -- started or not.
	 *
	 * "Installed" rather than "running" is the fix for the install that did nothing. This used to
	 * answer from whether CEF had been initialised *in this process*, and nothing initialised it at
	 * launch, so from the second session on every browser source was refused while the component
	 * sat on disk. Chromium is now started on the first call that needs it (see [withBrowser]),
	 * which also keeps its seconds of startup off the ~1,340 sources that never do.
	 *
	 * Computed on every read rather than stored, because the answer changes mid-session: an install
	 * finishing has to take effect immediately, not at the next launch.
	 */
	override val capabilities: Set<JsCapability>
		get() = if (component.appOrNull() != null || component.isInstalledOnDisk()) BROWSER_TIERS else emptySet()

	/**
	 * Null, deliberately, rather than a hardcoded Chrome string.
	 *
	 * The contract asks for the user-agent this backend *really* presents. CEF composes its own
	 * from the Chromium it was built against, and a literal here would drift from it on every
	 * dependency bump -- producing exactly the mismatch between claimed and observed browser that
	 * this field exists to prevent. Callers fall back to a plausible desktop UA, which is what
	 * they must do anyway while the component is not installed. [openInteractive] takes the
	 * caller's user agent instead, for the same reason.
	 */
	override val browserUserAgent: String? = null

	/**
	 * Refused, on purpose, even though a browser could obviously do it.
	 *
	 * PLAIN_SCRIPT belongs to Rhino. If this returned a result instead of throwing, the composite
	 * would have two backends claiming one tier and the cheap one would stop being reached.
	 */
	override suspend fun evaluate(script: String): String? =
		throw JsUnavailableException(JsCapability.PLAIN_SCRIPT)

	/**
	 * Load the page and keep asking [script] until it has an answer, as Android does.
	 *
	 * This is upstream's `WebViewExecutor.evaluateJs`, followed step by step, because parsers are
	 * written against it and nothing else. Ageha's first version evaluated once, at load end, and
	 * that was wrong in two ways that each returned nothing from a working page:
	 *
	 *  - **Parsers poll.** Their scripts return `null` to mean "not rendered yet, ask again" --
	 *    ComicK's says so in a comment -- and upstream asks again every second until the timeout.
	 *    One question at load end catches a single-page app before it has drawn anything.
	 *  - **The answer is the script's completion value.** `evaluateJavascript` hands back whatever
	 *    the script's last expression evaluated to, so `(() => {...})();` answers with what the
	 *    arrow function returns. Ageha pasted scripts into a function body, where that same line
	 *    evaluates and discards its value, and every IIFE-shaped script answered `null`.
	 *
	 * Running out of time answers `null` rather than throwing, as upstream does: parsers treat a
	 * null as "the page had nothing" and carry on, which is what they are written to do.
	 */
	override suspend fun evaluateInPage(
		baseUrl: String,
		script: String,
		timeoutMillis: Long,
	): String? = withBrowser(JsCapability.PAGE_CONTEXT, baseUrl, timeoutMillis + GRACE_MILLIS) { session ->
		session.evaluateUntilAnswered(script, timeoutMillis)
	}

	override suspend fun interceptRequests(
		pageUrl: String,
		filterScript: String?,
		pageScript: String?,
		maxRequests: Int,
		timeoutMillis: Long,
		urlPattern: Regex?,
	): List<InterceptedHttpRequest> = withBrowser(
		JsCapability.REQUEST_INTERCEPTION,
		pageUrl,
		// The outer bound is the caller's deadline plus a grace period, and the *inner* wait uses
		// the caller's figure exactly. Interception is the one operation where running out of time
		// is an ordinary outcome rather than a failure -- the answer is "these are the requests
		// that happened" -- so the inner wait returns what it has instead of throwing, and this
		// outer bound exists only to guarantee a wedged browser still gets torn down.
		timeoutMillis + GRACE_MILLIS,
	) { session ->
		session.captureUpTo(maxRequests, pageScript, urlPattern, timeoutMillis)
	}

	/**
	 * Get past a check at [url], in a window the user can see if it will not clear on its own.
	 *
	 * Upstream's `tryResolveCaptcha`, adapted: load the page, watch it with upstream's own
	 * [CF_STATE_JS] until it has shown the real page three polls running, then hand back the
	 * cookies. Closing the window gives up.
	 *
	 * ## When the window appears
	 *
	 * By default, at once -- see `showChecksImmediately`. A bot check is a site asking whether a
	 * person is there, and answering it out of sight is the app answering for them.
	 *
	 * The older behaviour is still reachable through that setting: because most Cloudflare checks
	 * do clear by themselves in a real browser, the window could start where no one could see it
	 * and come on screen only once it had been stuck for [REVEAL_AFTER_MILLIS], by which point the
	 * check is one that wants a person and usually has a box to tick. That is quieter, and it is
	 * quiet about the wrong thing.
	 */
	override suspend fun openInteractive(url: String, userAgent: String?): List<BrowserCookie>? =
		withBrowser(
			JsCapability.INTERACTIVE_BROWSER,
			url,
			CHECK_TIMEOUT_MILLIS + GRACE_MILLIS,
			interactive = true,
		) { session ->
			session.passCheck(userAgent, CHECK_TIMEOUT_MILLIS, showChecksImmediately())
		}

	override suspend fun close() {
		// The CefApp deliberately outlives this object -- see BrowserComponent, which explains why
		// a process gets exactly one and never shuts it down. Individual browsers are already
		// closed at the end of each operation, so there is nothing left here to release.
	}

	/**
	 * Run [block] against a freshly created browser, and destroy it afterwards whatever happens.
	 *
	 * Starts Chromium first when it is installed but not yet running -- the normal state for the
	 * first browser call of every session after the one that installed it.
	 *
	 * The `finally` is the half that matters. An undisposed browser keeps a renderer process
	 * alive, and a parser timing out is exactly the case that would leak one -- so a source that
	 * fails repeatedly would pile up Chromium processes until the machine ran out of them.
	 */
	private suspend fun <T> withBrowser(
		capability: JsCapability,
		url: String,
		timeoutMillis: Long,
		interactive: Boolean = false,
		block: suspend (BrowserSession) -> T,
	): T {
		// Refused through the recorder when Chromium will not start, so the reason survives a
		// parser that swallows it. The panel then offers the install, whose own failure notice
		// carries the reason Chromium would not start.
		val app = running() ?: refuseJsCapability(capability)
		return lock.withLock {
			val session = BrowserSession(app.createClient(), url, viewportWidth, viewportHeight, interactive)
			try {
				withTimeout(timeoutMillis) { block(session) }
			} catch (timeout: TimeoutCancellationException) {
				throw BrowserTimeoutException(url, timeoutMillis, timeout)
			} finally {
				session.dispose()
			}
		}
	}

	private suspend fun running(): CefApp? =
		component.appOrNull() ?: if (component.start()) component.appOrNull() else null
}

/**
 * How much longer than the caller's deadline a browser is given before it is killed outright.
 *
 * Only ever reached when something is genuinely stuck: the inner waits already honour the caller's
 * timeout and return normally at it.
 */
private const val GRACE_MILLIS = 5_000L

/** How often a page is asked again. Upstream's `WebViewExecutor` figure. */
private const val POLL_INTERVAL_MILLIS = 1_000L

/** How often a check page is inspected. Upstream's `CHALLENGE_POLL_INTERVAL_MS`. */
private const val CHECK_POLL_MILLIS = 700L

/**
 * How many polls in a row a check page must look finished, or refused, before it is believed.
 *
 * Upstream's `REQUIRED_STABLE_PASSES`, and for upstream's reason: managed challenges pass through
 * several stages, some of which look like a finished page for a moment, and taking the first
 * glimpse destroys the browser before Cloudflare has issued the clearance.
 */
private const val STABLE_POLLS = 3

/** How long a check is left to clear by itself before its window is shown to the user. */
private const val REVEAL_AFTER_MILLIS = 6_000L

/** How long anyone is given to get through a check, window shown or not. */
private const val CHECK_TIMEOUT_MILLIS = 90_000L

/** How long a DevTools call that should answer at once is waited for. */
private const val CONTROL_CALL_MILLIS = 5_000L

/**
 * `-Dageha.browser.trace` prints what the browser tier sees, step by step, to stderr.
 *
 * Off by default and cheap when off. It exists because every failure in this tier so far has
 * looked the same from outside -- a source that returned nothing -- and each one was found only by
 * watching what the page was actually doing.
 */
private val TRACING: Boolean = System.getProperty("ageha.browser.trace") != null

/**
 * How often, while tracing, the expression in `AGEHA_BROWSER_PROBE` is evaluated in a page being
 * captured from -- a window onto a page script's progress, which otherwise shows only its result.
 */
private const val PROBE_INTERVAL_MILLIS = 2_000L

private fun trace(message: String) {
	if (TRACING) System.err.println("[browser] $message")
}

private val BROWSER_TIERS: Set<JsCapability> = JsCapability.entries.filter { it.requiresBrowser }.toSet()

/**
 * Where a check page stands: `"ok"`, `"error"` or `"wait"`.
 *
 * Copied verbatim from upstream Kotatsu-Redo's `CloudFlareDetection.kt` (GPL-3.0, as is Ageha).
 * Its judgement of what a finished Cloudflare page looks like is tuned against the real thing in
 * several languages, and a home-grown version would have to relearn each of those cases in
 * production.
 */
private const val CF_STATE_JS = """
	(function(){
		try {
			var href = (document.location && document.location.href) || '';
			if (href === '' || href === 'about:blank') return 'wait';
			if (document.readyState !== 'interactive' && document.readyState !== 'complete') return 'wait';
			var t = (document.title || '').toLowerCase();
			if (t.indexOf('attention required') !== -1 || t.indexOf('access denied') !== -1) return 'error';
			if (t.indexOf('just a moment') !== -1 || t.indexOf('un instant') !== -1 ||
				t.indexOf('einen moment') !== -1 || t.indexOf('un momento') !== -1 ||
				t.indexOf('один момент') !== -1) return 'wait';
			var challengeNodes = document.querySelectorAll(
				'#challenge-running, #challenge-stage, #cf-challenge-running, ' +
				'.cf-browser-verification, #turnstile-wrapper, #cf-please-wait'
			);
			for (var i = 0; i < challengeNodes.length; i++) {
				var node = challengeNodes[i];
				var style = window.getComputedStyle(node);
				if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') continue;
				var rect = node.getBoundingClientRect();
				if (rect.width > 0 && rect.height > 0) return 'wait';
			}
			var body = document.body;
			if (!body) return 'wait';
			if (body.children.length === 0 && (body.textContent || '').trim().length === 0) return 'wait';
			return 'ok';
		} catch (e) { return 'wait'; }
	})()
"""

/** A page load or script that did not finish inside the timeout the parser asked for. */
class BrowserTimeoutException(
	val url: String,
	val timeoutMillis: Long,
	cause: Throwable?,
) : RuntimeException("Browser gave up on $url after ${timeoutMillis}ms", cause)

/** The page reported an error before it finished loading. */
class BrowserLoadException(
	val url: String,
	val detail: String,
) : RuntimeException("Browser could not load $url: $detail")

/**
 * One browser, one page, one operation.
 *
 * Every handler is registered in the constructor, *before* the browser is created. CEF delivers
 * events to whatever is attached at the time, so a handler added afterwards misses the first
 * navigation -- which is the request most parsers are waiting for.
 */
private class BrowserSession(
	private val client: CefClient,
	private val url: String,
	viewportWidth: Int,
	viewportHeight: Int,
	/** A check the user may have to pass by hand: a window with a title, that can be shown. */
	private val interactive: Boolean,
) {

	/**
	 * Completed when CEF says the native browser exists.
	 *
	 * `createImmediately` only *starts* creation. A `loadURL` issued before it finishes is
	 * silently dropped -- no error, no navigation, and the caller waits out its entire timeout on
	 * a browser sitting on `about:blank`. Waiting for this is what turns that into a page load.
	 */
	private val created = CompletableDeferred<Unit>()

	/**
	 * Completed when the requested page has started to replace the parking page.
	 *
	 * Asking a script anything before this would ask `about:blank`, and a script as ordinary as
	 * "return the document's HTML" answers there with a perfectly good empty document.
	 */
	private val committed = CompletableDeferred<Unit>()
	private val loaded = CompletableDeferred<Unit>()
	private val captured = CopyOnWriteArrayList<InterceptedHttpRequest>()
	private val enough = CompletableDeferred<Unit>()

	/** Completed when the user closes an interactive session's window: they have given up. */
	private val closedByUser = CompletableDeferred<Unit>()
	private val browser: CefBrowser

	/** The off-screen window Chromium paints into. See the note where it is built. */
	private val host: JFrame

	/** The site an evaluation is confined to. See [isSameSite]. */
	private val originalHost: String? = runCatching { URI(url).host }.getOrNull()

	@Volatile
	private var limit: Int = Int.MAX_VALUE

	@Volatile
	private var pageScript: String? = null

	/** Which requests count. Null keeps everything -- see the interface's note on the cap. */
	@Volatile
	private var wanted: Regex? = null

	/** Cancel main-frame navigations that leave [originalHost]. Evaluations only. */
	@Volatile
	private var sameSiteOnly: Boolean = false

	@Volatile
	private var devTools: CefDevToolsClient? = null

	init {
		client.addLifeSpanHandler(
			object : CefLifeSpanHandlerAdapter() {
				override fun onAfterCreated(browser: CefBrowser?) {
					created.complete(Unit)
				}
			},
		)

		client.addLoadHandler(
			object : CefLoadHandlerAdapter() {
				override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
					if (!frame.isRealMainFrame()) return
					trace("loaded ($httpStatusCode): ${frame?.url?.take(160)}")
					loaded.complete(Unit)
					// Again at load end, as upstream injects on both page-started and page-finished.
					// Page scripts are written to be idempotent for exactly this, and a payload that
					// only turns up once the document is complete is caught by this second copy.
					pageScript?.let { frame?.executeJavaScript(it, url, 0) }
				}

				override fun onLoadError(
					browser: CefBrowser?,
					frame: CefFrame?,
					errorCode: CefLoadHandler.ErrorCode?,
					errorText: String?,
					failedUrl: String?,
				) {
					// Main frame only, and only the page actually asked for. Subresources fail on
					// every real site -- a blocked tracker, a 404 sprite -- and treating one as a
					// page failure would abandon pages that loaded perfectly well.
					// An abort is never a page failure *here*, and treating one as such is what
					// made this class report a working site as broken. Two aborts are structural
					// to the design: navigating away from the `about:blank` the session parks on
					// supersedes that load, and cancelling the marker URL a parser navigates to in
					// order to hand back its result is a cancellation by construction. Both arrive
					// as ERR_ABORTED on the main frame.
					if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
					if (frame?.isMain == true && failedUrl == url) {
						val failure = BrowserLoadException(url, "$errorCode: ${errorText.orEmpty()}")
						// Both, so a wait for the page to *start* learns it never will.
						committed.completeExceptionally(failure)
						loaded.completeExceptionally(failure)
					}
				}

				override fun onLoadStart(
					browser: CefBrowser?,
					frame: CefFrame?,
					transitionType: CefRequest.TransitionType?,
				) {
					if (frame.isRealMainFrame()) committed.complete(Unit)
				}
			},
		)

		client.addRequestHandler(
			object : CefRequestHandlerAdapter() {
				/**
				 * Navigations, including the fake ones parsers use to hand back a result.
				 *
				 * A parser's page script finishes by assigning `window.location` a URL on a host
				 * that does not exist. That is a navigation rather than a subresource load, so it
				 * arrives here -- and it is cancelled, because letting Chromium try to resolve
				 * `kotatsu.intercept` would replace the page with a DNS error page and destroy
				 * anything still in flight.
				 */
				override fun onBeforeBrowse(
					browser: CefBrowser?,
					frame: CefFrame?,
					request: CefRequest?,
					userGesture: Boolean,
					isRedirect: Boolean,
				): Boolean {
					val target = request?.url ?: return false
					trace("navigate ${if (frame?.isMain == true) "main" else "sub"}: ${target.take(160)}")
					// Only the caller's own pattern is treated as a signal and stopped. Everything
					// else is the site doing its job and is allowed through.
					//
					// Cancelling every navigation that was not the exact url asked for looked
					// safe -- nothing but the marker should be navigating, surely -- and it broke
					// modern sources outright. ALLMANGA is a single-page app: it routes itself
					// after the first load, and blocking that left a page whose bundles had all
					// downloaded and which then never requested a single thing from its own API.
					val pattern = wanted
					if (pattern != null && pattern.containsMatchIn(target)) {
						record(request)
						return true
					}
					// An evaluation stays on the site it was pointed at, as upstream's does. What
					// leaves it is an ad's pop-under or a redirect to a parked domain, and following
					// one replaces the page the parser is waiting on with one it knows nothing about.
					return sameSiteOnly && frame?.isMain == true && !isSameSite(target)
				}

				override fun getResourceRequestHandler(
					browser: CefBrowser?,
					frame: CefFrame?,
					request: CefRequest?,
					isNavigation: Boolean,
					isDownload: Boolean,
					requestInitiator: String?,
					disableDefaultHandling: BoolRef?,
				) = object : CefResourceRequestHandlerAdapter() {
					override fun onBeforeResourceLoad(
						browser: CefBrowser?,
						frame: CefFrame?,
						request: CefRequest?,
					): Boolean {
						request?.let(::record)
						// False means "carry on and load it". Capturing here is observation, not
						// blocking: a parser watching for an API call still needs that call to
						// happen and its response to come back.
						return false
					}
				}
			},
		)

		// Created on `about:blank`, deliberately, rather than on the target url.
		//
		// Creating it on the real url starts the load immediately -- before the caller has had a
		// chance to set the page script it wants injected, and before it has said how many
		// requests it wants. The first load then runs with no script, completes, and `loaded`
		// resolves against a page that was never the one asked for; a subsequent `loadURL` cannot
		// un-resolve it. That is not theoretical: it is what made the first working build of this
		// class return zero captured requests from a page that had loaded perfectly.
		browser = client.createBrowser(BLANK, /* isOffscreen = */ false, /* isTransparent = */ false)
		// Sized before creation. An off-screen browser takes its viewport from the AWT component,
		// and an unrealised component is 0x0 -- see the constructor parameter for why that is not
		// a harmless default.
		browser.uiComponent.setSize(viewportWidth, viewportHeight)
		// The browser is parented to a real window that is positioned where no screen is.
		//
		// This looks like a hack and is not one -- it is the price of Chromium. A CEF browser only
		// starts its renderer once its component belongs to a *realised* window; an unparented
		// component, or one in a window that was never made visible, is never realised, and the
		// result is a browser that accepts `loadURL` and silently does nothing. That is exactly
		// what the first build of this class did: `createImmediately` threw on the AWT thread with
		// no stack trace, no navigation followed, and every capture came back empty after the
		// parser's full 45-second timeout.
		//
		// So: sized to the viewport and moved far outside any plausible desktop. A utility window,
		// so no taskbar entry appears while it is there. Nothing appears on screen, and Chromium is
		// satisfied that it has somewhere to paint.
		host = JFrame(if (interactive) "Checking ${originalHost ?: url} - Ageha" else "").apply {
			// An interactive session may have to be shown, and a window cannot gain a title bar
			// once it exists -- so it is decorated from the start and simply kept out of sight.
			isUndecorated = !interactive
			// Hidden browsers must not take keystrokes from the application while a source loads,
			// which for a window the user cannot see would look like the app hanging. A check the
			// user may have to click through must be able to take them, but not by itself: it is
			// focused only when it is actually shown.
			focusableWindowState = interactive
			isAutoRequestFocus = false
			type = Window.Type.UTILITY
			defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
			setSize(viewportWidth, viewportHeight)
			setLocation(OFFSCREEN_X, OFFSCREEN_Y)
			contentPane.add(browser.uiComponent, BorderLayout.CENTER)
			if (interactive) {
				addWindowListener(
					object : WindowAdapter() {
						override fun windowClosing(e: WindowEvent?) {
							closedByUser.complete(Unit)
						}
					},
				)
			}
			isVisible = true
		}
		browser.createImmediately()
	}

	/**
	 * Wait for the native browser, then make it take the size of its window.
	 */
	private suspend fun awaitCreated() {
		created.await()
		val fitted = CompletableDeferred<Unit>()
		SwingUtilities.invokeLater {
			runCatching {
				host.setSize(host.width, host.height + 1)
				host.validate()
				host.setSize(host.width, host.height - 1)
				host.validate()
			}
			fitted.complete(Unit)
		}
		fitted.await()
	}

	/**
	 * The main frame, showing something other than the parking page.
	 *
	 * Every session starts on `about:blank` -- see `createBrowser` -- and that blank page fires a
	 * full set of load events of its own. Without this, the very first `onLoadEnd` resolves the
	 * load before the real navigation has begun, and the caller is handed the empty result of a
	 * page it never asked for.
	 */
	private fun CefFrame?.isRealMainFrame(): Boolean {
		val frame = this ?: return false
		return frame.isMain && frame.url != BLANK
	}

	/**
	 * Whether [target] is on the site this session was opened for.
	 *
	 * Upstream's rule exactly -- the target's host *contains* the original -- so that `www.` and
	 * other subdomains of the same site stay reachable. Anything without a host (`about:`, `data:`)
	 * is let through, as upstream lets it through.
	 */
	private fun isSameSite(target: String): Boolean {
		val host = runCatching { URI(target).host }.getOrNull() ?: return true
		val origin = originalHost ?: return true
		return host.contains(origin, ignoreCase = true)
	}

	private fun record(request: CefRequest) {
		if (captured.size >= limit) return
		// Matched here rather than over the returned list, so that the cap counts the requests the
		// caller asked about. A page makes hundreds; a parser wants one of them.
		val pattern = wanted
		if (pattern != null && !pattern.containsMatchIn(request.url)) return
		val headers = HashMap<String, String>()
		runCatching { request.getHeaderMap(headers) }
		captured += InterceptedHttpRequest(
			url = request.url,
			method = request.method ?: "GET",
			headers = headers,
			timestampMillis = System.currentTimeMillis(),
			// CEF exposes post data as native elements that must be read on its own IO thread. No
			// parser in the current build reads the body of a captured request, so it is left null
			// rather than half-read: an empty string here would be a lie.
			body = null,
		)
		if (captured.size >= limit) enough.complete(Unit)
	}

	/**
	 * Load the page, then ask [script] at load end and every second after, until it answers.
	 *
	 * The answer is the script's value JSON-encoded, which the [JsRuntime] contract requires:
	 * parsers strip the encoding themselves, matching Android's `evaluateJavascript`, and handing
	 * them a raw string breaks them quietly rather than loudly. Like upstream, `"null"` and blank
	 * are not answers, and time running out is `null`.
	 */
	suspend fun evaluateUntilAnswered(script: String, timeoutMillis: Long): String? {
		sameSiteOnly = true
		awaitCreated()
		browser.loadURL(url)
		return withTimeoutOrNull(timeoutMillis) {
			committed.await()
			var sawLoad = false
			var answer: String? = null
			while (answer == null) {
				// The first ask is at load end or a second after the page started, whichever comes
				// first -- upstream asks on both -- and every second from then on.
				if (sawLoad) {
					delay(POLL_INTERVAL_MILLIS)
				} else {
					sawLoad = withTimeoutOrNull(POLL_INTERVAL_MILLIS) { loaded.await() } != null
				}
				answer = evaluateOnce(script)?.toString()?.takeUnless { it == "null" || it.isBlank() }
			}
			answer
		}
	}

	/**
	 * Load the check page and wait until it is through, then read the cookies it earned.
	 *
	 * [userAgent] is applied before the page loads, because the clearance Cloudflare issues is only
	 * honoured for the user agent that earned it -- and the cookies are about to be presented by
	 * OkHttp, not by this browser.
	 */
	suspend fun passCheck(
		userAgent: String?,
		timeoutMillis: Long,
		/** Whether to put the window on screen straight away rather than trying it out of sight. */
		showImmediately: Boolean,
	): List<BrowserCookie>? {
		awaitCreated()
		val started = System.currentTimeMillis()
		userAgent?.let { presentAs(it) }
		trace("loading $url for a check")
		browser.loadURL(url)
		// Shown at once unless the user has asked for the older, quieter behaviour. Revealed before
		// the page has finished arriving, deliberately: a window that appears the instant a check
		// is detected is the app saying "a site is asking for you" -- one that appears six seconds
		// later, only on failure, is the app saying "I tried to handle this without you".
		var shown = showImmediately
		if (shown) reveal()
		return withTimeoutOrNull(timeoutMillis) {
			var passes = 0
			var refusals = 0
			while (passes < STABLE_POLLS && refusals < STABLE_POLLS && !closedByUser.isCompleted) {
				delay(CHECK_POLL_MILLIS)
				val state = (evaluateOnce(CF_STATE_JS, CONTROL_CALL_MILLIS) as? JsonPrimitive)?.contentOrNull
				trace("check state at ${System.currentTimeMillis() - started}ms: $state")
				when (state) {
					"ok" -> {
						passes++
						refusals = 0
					}

					// An outright refusal ("Access denied") does not change by waiting, so it ends
					// the attempt instead of holding a window open for the full timeout.
					"error" -> {
						refusals++
						passes = 0
					}

					else -> {
						passes = 0
						refusals = 0
					}
				}
				if (!shown && System.currentTimeMillis() - started >= REVEAL_AFTER_MILLIS) {
					reveal()
					shown = true
				}
			}
			if (passes >= STABLE_POLLS) cookies() else null
		}
	}

	/**
	 * Evaluate [expression] in the page as a script, and return its value.
	 *
	 * Through the DevTools protocol's `Runtime.evaluate`, not `executeJavaScript`, which cannot
	 * return anything. Two things make it the right channel rather than just a working one:
	 *
	 *  - It evaluates a *script* and returns its completion value, which is exactly what
	 *    `evaluateJavascript` does on Android. Getting the same from `executeJavaScript` would mean
	 *    `eval`, and the pages that most need a browser are the ones behind a Content Security
	 *    Policy that forbids it -- comick.live's check page sends one.
	 *  - `awaitPromise` lets a script that answers with a promise be awaited rather than handed
	 *    back as `{}`.
	 *
	 * Null when the script threw, produced `undefined`, or could not be returned by value -- the
	 * cases Android reports as `"null"` -- and when the page had no context to evaluate in, which
	 * mid-navigation it briefly does not.
	 */
	private suspend fun evaluateOnce(expression: String, timeoutMillis: Long? = null): JsonElement? {
		val reply = devToolsCall(
			"Runtime.evaluate",
			buildJsonObject {
				put("expression", expression)
				put("returnByValue", true)
				put("awaitPromise", true)
			},
			timeoutMillis,
		) ?: return null
		val root = runCatching { Json.parseToJsonElement(reply).jsonObject }.getOrNull() ?: return null
		if (root.containsKey("exceptionDetails")) return null
		return runCatching { root["result"]?.jsonObject?.get("value") }.getOrNull()
	}

	/**
	 * Present [userAgent], with the client hints a real Chrome of that version would send.
	 *
	 * Only when it differs from Chromium's own, because an override without hints is itself a tell:
	 * a browser that claims one version in its user agent and another in `Sec-CH-UA` is what bot
	 * detection is built to notice.
	 */
	private suspend fun presentAs(userAgent: String) {
		// Asked of the page rather than through `Browser.getVersion`, which a page's DevTools
		// session in CEF accepts and never answers -- it held the first version of this for the
		// whole check timeout.
		val own = (evaluateOnce("navigator.userAgent", CONTROL_CALL_MILLIS) as? JsonPrimitive)?.contentOrNull
		trace("own user agent: $own; presenting: $userAgent")
		if (own == userAgent) return
		val major = CHROME_MAJOR.find(userAgent)?.groupValues?.get(1)
		devToolsCall(
			"Emulation.setUserAgentOverride",
			buildJsonObject {
				put("userAgent", userAgent)
				if (major != null && userAgent.contains("Windows")) {
					putJsonObject("userAgentMetadata") {
						putJsonArray("brands") {
							addJsonObject { put("brand", "Chromium"); put("version", major) }
							addJsonObject { put("brand", "Google Chrome"); put("version", major) }
							addJsonObject { put("brand", "Not.A/Brand"); put("version", "99") }
						}
						put("fullVersion", "$major.0.0.0")
						put("platform", "Windows")
						put("platformVersion", "10.0.0")
						put("architecture", "x86")
						put("bitness", "64")
						put("model", "")
						put("mobile", false)
					}
				}
			},
			timeoutMillis = CONTROL_CALL_MILLIS,
		)
	}

	/** Bring an interactive session's window to where the user can see it, and give it focus. */
	private fun reveal() {
		SwingUtilities.invokeLater {
			host.setLocationRelativeTo(null)
			host.toFront()
			host.requestFocus()
		}
	}

	/**
	 * The cookies Chromium would send to [url] -- HttpOnly ones included, since `cf_clearance` is
	 * one -- or an empty list when it has none.
	 *
	 * Read through DevTools' `Network.getCookies`, the same channel every evaluation already uses.
	 * `CefCookieManager.visitUrlCookies` was the obvious API and it reported nothing at all for a
	 * page that had just been handed its clearance -- a passed check with no cookie to show for it,
	 * which from OkHttp's side is indistinguishable from a failed one.
	 */
	private suspend fun cookies(): List<BrowserCookie> {
		val reply = devToolsCall(
			"Network.getCookies",
			buildJsonObject { putJsonArray("urls") { add(JsonPrimitive(url)) } },
			timeoutMillis = CONTROL_CALL_MILLIS,
		) ?: return emptyList<BrowserCookie>().also { trace("no answer to Network.getCookies") }
		val cookies = runCatching {
			Json.parseToJsonElement(reply).jsonObject["cookies"]?.jsonArray.orEmpty().mapNotNull { element ->
				element.jsonObject.toBrowserCookie()
			}
		}.getOrDefault(emptyList())
		trace("cookies for $url: ${cookies.joinToString { it.name }}")
		return cookies
	}

	/**
	 * One DevTools method call, answered or null.
	 *
	 * [timeoutMillis] bounds calls that should answer at once. It is not applied to evaluations by
	 * default, because a parser's script is allowed to answer with a promise that takes a while --
	 * those are bounded by the caller's own deadline instead.
	 */
	private suspend fun devToolsCall(
		method: String,
		params: JsonObject,
		timeoutMillis: Long? = null,
	): String? = try {
		val tools = devTools ?: browser.devToolsClient.also { devTools = it }
		val reply = tools.executeDevToolsMethod(method, params.toString())
		if (timeoutMillis == null) reply.await() else withTimeoutOrNull(timeoutMillis) { reply.await() }
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		// DevTools answers "no context" with an error rather than a value while a page is between
		// documents. That is "not yet", the same as a script answering null.
		null
	}

	/**
	 * Load the page with [pageScript] injected, and return the requests it made.
	 *
	 * Returns as soon as [max] requests have been seen, and otherwise when the caller's timeout
	 * runs out -- see the note inside on why load end is not a completion signal.
	 */
	suspend fun captureUpTo(
		max: Int,
		pageScript: String?,
		urlPattern: Regex?,
		timeoutMillis: Long,
	): List<InterceptedHttpRequest> {
		limit = max.takeIf { it > 0 } ?: Int.MAX_VALUE
		this.pageScript = pageScript
		wanted = urlPattern
		awaitCreated()
		// Registered to run in every new document *before any of the site's own scripts*, which is
		// what a page script needs: it exists to hook something the page is about to do -- ALLMANGA's
		// wraps `JSON.parse` to catch the chapter list the site decrypts -- and a hook installed a
		// moment late misses the one call it was there for. `executeJavaScript` at load start was
		// that moment late: it is asynchronous, and it raced the site's bundles. Android gets the
		// same guarantee from document-start injection; this is Chromium's own form of it, and it
		// also covers the real page arriving after a challenge or a redirect, which is a new document.
		pageScript?.let { script ->
			val registered = devToolsCall(
				"Page.addScriptToEvaluateOnNewDocument",
				buildJsonObject { put("source", script) },
				timeoutMillis = CONTROL_CALL_MILLIS,
			)
			trace("page script registered for new documents: ${registered ?: "no answer"}")
		}
		browser.loadURL(url)
		// Waits for the requests, not for the page.
		//
		// Returning when the main frame finished loading looked like a sensible way to avoid
		// spending a parser's whole timeout, and it was wrong -- it is the reason this returned
		// nothing from a site that works. The request a parser is waiting for is made by the
		// site's *own* JavaScript after the document is complete, and the marker navigation its
		// page script performs to hand back the result comes later still. Load-end therefore
		// arrives reliably before the interesting part, every time.
		//
		// So the only completion signal is having what was asked for, bounded by the caller's
		// timeout. Running out of it returns the matches so far rather than throwing: a parser
		// that asked for one request and got none will say so far better than this class can.
		withTimeoutOrNull(timeoutMillis) {
			val probe = if (TRACING) System.getenv("AGEHA_BROWSER_PROBE") else null
			if (probe == null) {
				enough.await()
			} else {
				// Diagnostic only: what the page looks like while the capture waits.
				while (!enough.isCompleted) {
					delay(PROBE_INTERVAL_MILLIS)
					trace("probe: ${evaluateOnce(probe, CONTROL_CALL_MILLIS)}")
				}
			}
		}
		return captured.toList()
	}

	fun dispose() {
		runCatching { devTools?.close() }
		runCatching { browser.close(true) }
		// Disposed on the AWT thread, because Swing requires it and because a window leaked here
		// is a window leaked per page load.
		runCatching { SwingUtilities.invokeLater { host.dispose() } }
		runCatching { client.dispose() }
	}

	private companion object {

		/** Somewhere harmless to sit until the caller is ready to navigate. */
		const val BLANK = "about:blank"

		/**
		 * Far enough outside any real desktop to be invisible on every monitor arrangement.
		 *
		 * Not simply negative: a second monitor placed left of or above the primary one occupies
		 * negative coordinates, and a browser window appearing there would be a window the user
		 * can see and click. No desktop extends to 32,000 pixels.
		 */
		const val OFFSCREEN_X = -32000
		const val OFFSCREEN_Y = -32000

		val CHROME_MAJOR = Regex("""Chrome/(\d+)""")
	}
}

/**
 * A DevTools `Network.Cookie`, as Ageha's own type.
 *
 * DevTools gives expiry in *seconds* since the epoch, as a double, with `session: true` (and an
 * expiry of -1) for a session cookie. Taking the seconds for milliseconds would expire every
 * clearance in 1970 and the jar would throw it away on the next read.
 */
private fun JsonObject.toBrowserCookie(): BrowserCookie? {
	fun text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
	fun flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
	val name = text("name") ?: return null
	val domain = text("domain") ?: return null
	val session = flag("session")
	val expiresSeconds = (this["expires"] as? JsonPrimitive)?.doubleOrNull
	return BrowserCookie(
		name = name,
		value = text("value").orEmpty(),
		domain = domain,
		path = text("path") ?: "/",
		secure = flag("secure"),
		httpOnly = flag("httpOnly"),
		expiresAtMillis = if (session || expiresSeconds == null || expiresSeconds <= 0) null else (expiresSeconds * 1000).toLong(),
	)
}
