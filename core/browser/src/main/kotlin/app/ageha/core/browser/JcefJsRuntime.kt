package app.ageha.core.browser

import app.ageha.core.js.InterceptedHttpRequest
import app.ageha.core.js.JsRuntime
import app.ageha.core.js.JsUnavailableException
import app.ageha.core.model.JsCapability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.awt.Window
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
) : JsRuntime {

	private val lock = Mutex()

	/**
	 * Everything but [JsCapability.PLAIN_SCRIPT], and only once CEF is actually running.
	 *
	 * Computed on every read rather than stored, because the answer changes mid-session: this is
	 * what the UI asks before offering a source, and it has to start saying yes the moment an
	 * install finishes rather than at the next launch.
	 */
	override val capabilities: Set<JsCapability>
		get() = if (component.appOrNull() == null) {
			emptySet()
		} else {
			JsCapability.entries.filter { it.requiresBrowser }.toSet()
		}

	/**
	 * Null, deliberately, rather than a hardcoded Chrome string.
	 *
	 * The contract asks for the user-agent this backend *really* presents. CEF composes its own
	 * from the Chromium it was built against, and a literal here would drift from it on every
	 * dependency bump -- producing exactly the mismatch between claimed and observed browser that
	 * this field exists to prevent. Callers fall back to a plausible desktop UA, which is what
	 * they must do anyway while the component is not installed.
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

	override suspend fun evaluateInPage(
		baseUrl: String,
		script: String,
		timeoutMillis: Long,
	): String? = withBrowser(JsCapability.PAGE_CONTEXT, baseUrl, timeoutMillis) { session ->
		session.navigate(pageScript = null)
		session.evaluate(script)
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
		timeoutMillis + CAPTURE_GRACE_MILLIS,
	) { session ->
		session.captureUpTo(maxRequests, pageScript, urlPattern, timeoutMillis)
	}

	/**
	 * Not built yet, and refusing rather than pretending.
	 *
	 * Everything above runs a browser nobody sees. This one is a browser the *user* drives, which
	 * means a real window, input routing, and a lifetime tied to a screen rather than to a call --
	 * a different problem, and the fiddliest part of JCEF. Until it lands, a source that demands
	 * an interactive challenge gets the same honest refusal it got before the component existed,
	 * which `SourceFailureMapper` already turns into readable copy.
	 */
	override suspend fun openInteractive(url: String, userAgent: String?): Boolean =
		throw JsUnavailableException(JsCapability.INTERACTIVE_BROWSER)

	override suspend fun close() {
		// The CefApp deliberately outlives this object -- see BrowserComponent, which explains why
		// a process gets exactly one and never shuts it down. Individual browsers are already
		// closed at the end of each operation, so there is nothing left here to release.
	}

	/**
	 * Run [block] against a freshly created browser, and destroy it afterwards whatever happens.
	 *
	 * The `finally` is the half that matters. An undisposed browser keeps a renderer process
	 * alive, and a parser timing out is exactly the case that would leak one -- so a source that
	 * fails repeatedly would pile up Chromium processes until the machine ran out of them.
	 */
	private suspend fun <T> withBrowser(
		capability: JsCapability,
		url: String,
		timeoutMillis: Long,
		block: suspend (BrowserSession) -> T,
	): T {
		val app = component.appOrNull() ?: throw JsUnavailableException(capability)
		return lock.withLock {
			val session = BrowserSession(app.createClient(), url, viewportWidth, viewportHeight)
			try {
				withTimeout(timeoutMillis) { block(session) }
			} catch (timeout: TimeoutCancellationException) {
				throw BrowserTimeoutException(url, timeoutMillis, timeout)
			} finally {
				session.dispose()
			}
		}
	}
}

/**
 * How much longer than the caller's deadline a browser is given before it is killed outright.
 *
 * Only ever reached when something is genuinely stuck: the inner wait already honours the caller's
 * timeout and returns normally at it.
 */
private const val CAPTURE_GRACE_MILLIS = 5_000L

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
) {

	/**
	 * Completed when CEF says the native browser exists.
	 *
	 * `createImmediately` only *starts* creation. A `loadURL` issued before it finishes is
	 * silently dropped -- no error, no navigation, and the caller waits out its entire timeout on
	 * a browser sitting on `about:blank`. Waiting for this is what turns that into a page load.
	 */
	private val created = CompletableDeferred<Unit>()
	private val loaded = CompletableDeferred<Unit>()
	private val captured = CopyOnWriteArrayList<InterceptedHttpRequest>()
	private val enough = CompletableDeferred<Unit>()
	private val result = CompletableDeferred<String?>()
	private val router: CefMessageRouter = CefMessageRouter.create()
	private val browser: CefBrowser

	/** The off-screen window Chromium paints into. See the note where it is built. */
	private val host: JFrame

	@Volatile
	private var limit: Int = Int.MAX_VALUE

	@Volatile
	private var pageScript: String? = null

	/** Which requests count. Null keeps everything -- see the interface's note on the cap. */
	@Volatile
	private var wanted: Regex? = null

	init {
		// `window.cefQuery` is CEF's own JS-to-Java channel. It is why the injected script needs
		// no polling and no network round trip: the value arrives here the instant the parser's
		// promise resolves.
		router.addHandler(
			object : CefMessageRouterHandlerAdapter() {
				override fun onQuery(
					browser: CefBrowser?,
					frame: CefFrame?,
					queryId: Long,
					request: String?,
					persistent: Boolean,
					callback: CefQueryCallback?,
				): Boolean {
					result.complete(request)
					callback?.success("")
					return true
				}
			},
			true,
		)
		client.addMessageRouter(router)

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
					if (frame.isRealMainFrame()) loaded.complete(Unit)
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
					if (frame.isRealMainFrame() && failedUrl == url) {
						loaded.completeExceptionally(
							BrowserLoadException(url, "$errorCode: ${errorText.orEmpty()}"),
						)
					}
				}

				override fun onLoadStart(
					browser: CefBrowser?,
					frame: CefFrame?,
					transitionType: CefRequest.TransitionType?,
				) {
					// Injected at load *start*, before the site's own scripts run. A parser's page
					// script exists to hook something the page is about to do -- wrap `fetch`, stub
					// a global -- and injecting it after load would be too late for all of them.
					val script = pageScript ?: return
					if (frame.isRealMainFrame()) frame?.executeJavaScript(script, url, 0)
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
					// Only the caller's own pattern is treated as a signal and stopped. Everything
					// else is the site doing its job and is allowed through.
					//
					// Cancelling every navigation that was not the exact url asked for looked
					// safe -- nothing but the marker should be navigating, surely -- and it broke
					// modern sources outright. ALLMANGA is a single-page app: it routes itself
					// after the first load, and blocking that left a page whose bundles had all
					// downloaded and which then never requested a single thing from its own API.
					// The symptom was a parser reporting that its data never arrived, which reads
					// exactly like a dead source and was entirely self-inflicted.
					val pattern = wanted ?: return false
					if (!pattern.containsMatchIn(target)) return false
					record(request)
					return true
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
		// So: undecorated, unfocusable, sized to the viewport, and moved far outside any plausible
		// desktop. Nothing appears on screen, no taskbar entry is created, and Chromium is
		// satisfied that it has somewhere to paint.
		host = JFrame().apply {
			isUndecorated = true
			// Focusable windows steal keystrokes from the application while a source is loading,
			// which for a browser the user cannot see would be indistinguishable from the app
			// hanging.
			focusableWindowState = false
			type = Window.Type.UTILITY
			setSize(viewportWidth, viewportHeight)
			setLocation(OFFSCREEN_X, OFFSCREEN_Y)
			add(browser.uiComponent)
			isVisible = true
		}
		browser.createImmediately()
	}

	/**
	 * The main frame, showing something other than the parking page.
	 *
	 * Every session starts on `about:blank` -- see `createBrowser` -- and that blank page fires a
	 * full set of load events of its own. Without this, the very first `onLoadEnd` resolves the
	 * load before the real navigation has begun, and the caller is handed the empty result of a
	 * page it never asked for. That is the same defect as creating the browser on the target url,
	 * one layer further down, and it produced identical symptoms: an instant return and nothing
	 * captured.
	 */
	private fun CefFrame?.isRealMainFrame(): Boolean {
		val frame = this ?: return false
		return frame.isMain && frame.url != BLANK
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
	 * Go to the page, with [pageScript] injected at load start, and wait for it to finish.
	 *
	 * Separate from construction so that everything the load depends on is in place before it
	 * begins -- see the note on `createBrowser` for what happens when it is not.
	 */
	suspend fun navigate(pageScript: String?) {
		this.pageScript = pageScript
		created.await()
		browser.loadURL(url)
		loaded.await()
	}

	/**
	 * Evaluate [script] in the loaded page and await whatever it returns, promise included.
	 *
	 * The value comes back JSON-encoded, which the [JsRuntime] contract requires: parsers run a
	 * local `decodeWebViewString()` over it to match Android's `WebView.evaluateJavascript`, and
	 * handing them a raw string breaks them quietly rather than loudly.
	 */
	suspend fun evaluate(script: String): String? {
		browser.mainFrame?.executeJavaScript(bridge(script), url, 0)
		return result.await()
	}

	/**
	 * Load the page with [pageScript] injected, and return the requests it made.
	 *
	 * Returns as soon as [max] requests have been seen, and otherwise when the page finishes
	 * loading -- whichever happens first. Always waiting for the load to end would spend a
	 * parser's entire timeout on a page whose interesting request fired in the first 200ms.
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
		created.await()
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
		withTimeoutOrNull(timeoutMillis) { enough.await() }
		return captured.toList()
	}

	fun dispose() {
		runCatching { browser.close(true) }
		// Disposed on the AWT thread, because Swing requires it and because a window leaked here
		// is a window leaked per page load.
		runCatching { SwingUtilities.invokeLater { host.dispose() } }
		runCatching { router.dispose() }
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

		/**
		 * Wraps a parser's script so its result -- a value or a promise -- comes back through
		 * `cefQuery` JSON-encoded, and so a throw arrives as a null rather than as silence.
		 *
		 * Silence is the failure worth designing against: without the catch, a script that threw
		 * would leave the caller waiting out its whole timeout for a value that was never coming,
		 * and report a slow site rather than a broken script.
		 */
		fun bridge(script: String): String = buildString {
			append("(function(){")
			append("function send(v){try{window.cefQuery({request:JSON.stringify(v===undefined?null:v),")
			append("onSuccess:function(){},onFailure:function(){}});}catch(e){}}")
			append("try{var out=(function(){")
			append(script)
			append("})();")
			append("if(out&&typeof out.then==='function'){out.then(send,function(){send(null);});}")
			append("else{send(out);}")
			append("}catch(e){send(null);}")
			append("})();")
		}
	}
}
