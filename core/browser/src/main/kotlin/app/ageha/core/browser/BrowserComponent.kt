package app.ageha.core.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import org.cef.CefApp
import java.io.File

/**
 * How far along the browser component is.
 *
 * Modelled as a state rather than a boolean because the middle of it is minutes long. Around
 * 200MB of Chromium arrives over the network the first time anyone asks for it, and a UI that can
 * only say "installed" or "not installed" has to draw a spinner with no end in sight -- for the
 * one operation in Ageha where the user genuinely needs to know whether to walk away.
 */
sealed interface BrowserInstallState {

	/** Never installed, or installed and then deleted. The starting state on a fresh machine. */
	data object Absent : BrowserInstallState

	/**
	 * Fetching or unpacking.
	 *
	 * [fraction] is 0..1 where jcefmaven can estimate it and null where it cannot -- extraction
	 * reports no total, so a determinate bar would have to invent one.
	 */
	data class Working(val step: String, val fraction: Float?) : BrowserInstallState

	/** Native Chromium is on disk and CEF has been initialised in this process. */
	data object Ready : BrowserInstallState

	/**
	 * The install was attempted and did not finish.
	 *
	 * Carries the cause because every plausible reason is one the user can act on -- no disk
	 * space, no network, a proxy that blocks the CDN -- and "installation failed" alone sends them
	 * to a forum instead of to their own disk.
	 */
	data class Failed(val reason: String, val cause: Throwable?) : BrowserInstallState
}

/**
 * The optional browser component: installing it, and holding the one [CefApp] a process may have.
 *
 * ## Why this is a separate download rather than part of the app
 *
 * Ageha needs a real browser for around 20 of its 1,360 sources (docs/FINDINGS.md 4). Bundling
 * Chromium would roughly triple the installer for every user, including the overwhelming majority
 * whose sources are plain HTML and who would never start it. So the *bindings* ship -- a few
 * hundred kilobytes of Java, which is why this class can exist and report `Absent` at all -- and
 * the natives are fetched on demand into the user's data directory.
 *
 * ## Why one instance, forever
 *
 * `CefApp` is a process singleton in CEF itself, not merely by convention: initialising it twice
 * terminates the JVM rather than throwing, and shutting it down makes the process unable to
 * initialise it again. So this class initialises at most once, never shuts down before exit, and
 * hands the same [CefApp] to every caller.
 */
class BrowserComponent(
	/**
	 * Where the natives are unpacked. Supplied rather than derived, so this module needs no
	 * dependency on the one that knows Ageha's directory layout -- and so a test can point it at
	 * a temporary directory instead of the user's real installation.
	 */
	private val installDir: File,
	/** Written into CEF's own disk cache, kept apart from OkHttp's. */
	private val cacheDir: File,
) {

	// Always starts Absent, even when the natives are already unpacked. On disk is not the same
	// as running: CEF still has to be initialised in this process before a page can be loaded, and
	// a state that claimed Ready before that would have the UI offer a browser that cannot answer.
	// `isInstalledOnDisk` is what the settings panel asks when it wants the other question.
	private val _state = MutableStateFlow<BrowserInstallState>(BrowserInstallState.Absent)

	/** Observable for the settings panel and the failure notice's install button. */
	val state: StateFlow<BrowserInstallState> = _state.asStateFlow()

	private val lock = Mutex()

	@Volatile
	private var app: CefApp? = null

	/**
	 * Whether the natives are already unpacked, without downloading anything.
	 *
	 * Answered by looking for the marker jcefmaven writes when an unpack completes, rather than by
	 * calling its `CefInstallationChecker` -- that class lives in an `impl` package, and a
	 * capability check that breaks the app on a routine dependency bump is a poor trade for the
	 * few lines it would save. A half-extracted directory therefore reads as absent, which is the
	 * safe direction to be wrong in: the worst case is re-downloading, and the alternative is
	 * initialising CEF against a truncated Chromium, which takes the process down with it.
	 */
	fun isInstalledOnDisk(): Boolean = File(installDir, "install.lock").isFile

	/**
	 * Download and unpack the natives if needed, then initialise CEF.
	 *
	 * Safe to call when already installed: jcefmaven skips straight to initialising, so this
	 * doubles as "start the browser" and the caller does not have to know which case it is in.
	 *
	 * Long-running by construction, and on [Dispatchers.IO] because it is a network fetch and a
	 * tar extraction. Only one runs at a time.
	 */
	suspend fun install(): BrowserInstallState = lock.withLock {
		app?.let { return@withLock BrowserInstallState.Ready }
		withContext(Dispatchers.IO) {
			runCatching {
				installDir.mkdirs()
				cacheDir.mkdirs()
				val builder = CefAppBuilder()
				builder.setInstallDir(installDir)
				builder.setProgressHandler { step, percent -> _state.value = step.toState(percent) }
				builder.cefSettings.apply {
					// *Not* off-screen rendering, despite every browser here being invisible.
					//
					// CEF's windowless mode paints through a JOGL `GLCanvas`, which needs a usable
					// OpenGL graphics configuration -- and a window placed off-screen with the GPU
					// disabled has none. It fails at `addNotify` with "Unable to determine
					// GraphicsConfiguration" before a single page has loaded. Ageha never looks at
					// these pixels, so it asks for an ordinary windowed browser and hides the
					// window instead. See the host frame in JcefJsRuntime.
					windowless_rendering_enabled = false
					// CEF's own cache, deliberately not OkHttp's. They store different things --
					// this one holds a Chromium profile, including its cookie database -- and
					// pointing them at one directory corrupts both.
					cache_path = cacheDir.absolutePath
					// Chromium is loud. At the default severity every page load writes warnings
					// to stderr, which on a desktop app means the user's console fills with
					// Chromium internals for a source that is working perfectly.
					log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_ERROR
				}
				// Disables the GPU process. Ageha never looks at what these browsers draw, so
				// hardware compositing buys nothing and costs a process plus a class of driver
				// crashes that would take the whole app down with it.
				//
				// `--disable-software-rasterizer` is deliberately *not* passed alongside it: with
				// both, Chromium has no way to compose a frame at all, and pages that wait on
				// paint or on an intersection observer never finish loading.
				builder.addJcefArgs("--disable-gpu")
				builder.build()
			}.fold(
				onSuccess = { built ->
					app = built
					BrowserInstallState.Ready.also { _state.value = it }
				},
				onFailure = { error ->
					BrowserInstallState.Failed(
						reason = error.message ?: error::class.simpleName.orEmpty(),
						cause = error,
					).also { _state.value = it }
				},
			)
		}
	}

	/** The initialised [CefApp], or null when [install] has not run or did not succeed. */
	fun appOrNull(): CefApp? = app
}

private fun EnumProgress.toState(percent: Float): BrowserInstallState = when (this) {
	EnumProgress.INITIALIZED -> BrowserInstallState.Ready
	else -> BrowserInstallState.Working(
		step = when (this) {
			EnumProgress.LOCATING -> "Looking for an existing install"
			EnumProgress.DOWNLOADING -> "Downloading Chromium"
			EnumProgress.EXTRACTING -> "Unpacking"
			EnumProgress.INSTALL -> "Installing"
			else -> "Starting the browser"
		},
		// jcefmaven signals "no estimate available" with a negative percentage rather than with
		// null, and reports it for every step that is not the download.
		fraction = percent.takeIf { it >= 0f }?.div(100f),
	)
}
