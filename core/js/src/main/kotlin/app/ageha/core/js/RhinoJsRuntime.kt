package app.ageha.core.js

import app.ageha.core.model.JsCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined

/**
 * The [JsCapability.PLAIN_SCRIPT] tier, on Rhino.
 *
 * ## Why Rhino and not QuickJS
 *
 * QuickJS was the engine chosen up front, before the workload was known precisely. Having now read
 * what actually gets evaluated, Rhino is the better fit, and the difference is entirely about
 * packaging:
 *
 *  - **QuickJS is native.** It needs a `.dll`, `.so` and `.dylib`, each for x64 and arm64 -- six
 *    binaries to obtain, verify, bundle per platform and keep in step. Conveyor can carry them,
 *    but each is a thing that can be missing, mismatched or blocked, and a failure to load one
 *    degrades a source to "broken" with no useful error.
 *  - **Rhino is a 1.6MB jar.** It goes wherever the app goes, jlinks cleanly, and cross-compiles
 *    for free because there is nothing to cross-compile.
 *
 * The workload does not need what QuickJS is better at. This tier is the NetShield / slowAES path
 * in `MangaReaderParser`: a site's `min.js` plus an inline script, doing AES in pure ES5 with no
 * DOM, no network and no timers. Rhino runs it, and handles ES6 besides -- arrow functions, `Map`,
 * template literals and `const` were all verified before this was written.
 *
 * **This is a swap, not a lock-in.** `JsRuntime` is unchanged; a QuickJS backend would be another
 * implementation of the same interface, and nothing above this class would move.
 *
 * ## Why the sandbox is not optional
 *
 * The script being executed is **served by the site Ageha is scraping**. It is hostile input by
 * default. Rhino's ordinary `initStandardObjects()` exposes a Java bridge, which would let that
 * script call `java.lang.Runtime.getRuntime().exec(...)` on the reader's machine. Three things
 * prevent it here, and none is decorative:
 *
 *  1. [Context.initSafeStandardObjects] -- omits `Packages`, `JavaAdapter`, `getClass` and the
 *     rest of the Java bridge.
 *  2. A [ClassShutter] denying every class name outright, as a second line in case a future Rhino
 *     reaches Java by a route the safe scope does not close.
 *  3. A wall-clock deadline, so a script that loops forever stops instead of pinning a core.
 *
 * All three are configured when the `Context` is *created*, not afterwards. Rhino only calls
 * `observeInstructionCount` for a context its own factory built with a threshold already in place,
 * and `setClassShutter` throws if called twice -- so creation is the only correct place for them.
 */
class RhinoJsRuntime(
	private val timeBudgetMillis: Long = DEFAULT_TIME_BUDGET_MS,
) : JsRuntime {

	override val capabilities: Set<JsCapability> = setOf(JsCapability.PLAIN_SCRIPT)

	/**
	 * Null: Rhino is not a browser and has no User-Agent to report.
	 *
	 * Returning a plausible Chrome string would be worse than returning nothing -- the caller uses
	 * this to decide whether it can present itself as a browser, and claiming to be one while
	 * presenting a JVM TLS fingerprint is exactly the mismatch bot detection looks for
	 * (docs/ARCHITECTURE.md 8.5).
	 */
	override val browserUserAgent: String? = null

	private val contextFactory = BudgetedContextFactory()

	/**
	 * Run a script and return what it produced.
	 *
	 * **The contract is a function body, not an expression.** The parsers rewrite
	 * `document.cookie = <expr>` into `return <expr>` before calling, so what arrives contains a
	 * top-level `return` -- a syntax error in a plain script, and meaningful only inside a
	 * function. Wrapping is therefore not a convenience; it is what makes the caller's own
	 * transformation legal.
	 *
	 * A script that returns nothing is then evaluated as an ordinary script, so its completion
	 * value is still available to callers using the other convention. That second pass is safe
	 * precisely because this tier is defined as pure computation -- no DOM, no network, no storage
	 * -- so there are no side effects to repeat. It never runs for the anti-bot workload, which
	 * always returns.
	 */
	override suspend fun evaluate(script: String): String? = withContext(Dispatchers.Default) {
		val asFunctionBody = run(FUNCTION_WRAPPER_PREFIX + script + FUNCTION_WRAPPER_SUFFIX)
		if (asFunctionBody != null) return@withContext asFunctionBody
		run(script)
	}

	private fun run(source: String): String? {
		// Per evaluation, and read by the factory's observer. A thread-local because Rhino hands
		// the observer a Context rather than a call site, and one runtime may be evaluating on
		// several threads at once.
		DEADLINE.set(System.nanoTime() + timeBudgetMillis * NANOS_PER_MILLI)
		val context = contextFactory.enterContext()
		return try {
			// A fresh scope per evaluation: two sources' scripts must not see each other's globals.
			val scope: Scriptable = context.initSafeStandardObjects()
			val result = context.evaluateString(scope, source, SCRIPT_NAME, 1, null)
			if (result == null || Undefined.isUndefined(result)) null else Context.toString(result)
		} catch (timedOut: ScriptTimedOut) {
			throw timedOut
		} catch (failure: RhinoException) {
			// A site serving broken or hostile JavaScript is a *source* problem, not an Ageha
			// crash. It surfaces as "no cookie", which is what the caller already handles.
			null
		} finally {
			Context.exit()
			DEADLINE.remove()
		}
	}

	// The browser tiers refuse *through the recorder* rather than throwing directly, so the reason
	// survives a parser that swallows the exception -- which MangaReaderParser does, by wrapping
	// its call in runCatchingCancellable. A bare throw would lose it.
	override suspend fun evaluateInPage(baseUrl: String, script: String, timeoutMillis: Long): String? =
		refuseJsCapability(JsCapability.PAGE_CONTEXT)

	override suspend fun interceptRequests(
		pageUrl: String,
		filterScript: String?,
		pageScript: String?,
		maxRequests: Int,
		timeoutMillis: Long,
	): List<InterceptedHttpRequest> = refuseJsCapability(JsCapability.REQUEST_INTERCEPTION)

	override suspend fun openInteractive(url: String, userAgent: String?): Boolean =
		refuseJsCapability(JsCapability.INTERACTIVE_BROWSER)

	override suspend fun close() = Unit

	/** Denies every class, so no script can reach Java however it asks for it. */
	private object DenyAllClasses : ClassShutter {
		override fun visibleToScripts(fullClassName: String): Boolean = false
	}

	/**
	 * Builds every context already sandboxed, and stops one that overruns its deadline.
	 *
	 * The observer counts instructions but **the budget is time**, because time is what the user
	 * experiences. An instruction budget would let a slow machine fail on a script a fast one
	 * completes, and would need retuning whenever the engine's accounting changed. Instruction
	 * counting is only the polling hook: Rhino offers no other way to interrupt a running script.
	 */
	private class BudgetedContextFactory : ContextFactory() {

		override fun makeContext(): Context = super.makeContext().apply {
			languageVersion = Context.VERSION_ES6
			// Required for instruction observation, and it stops Rhino defining classes at
			// runtime -- one fewer thing for a locked-down JVM or an antivirus to object to.
			isInterpretedMode = true
			instructionObserverThreshold = INSTRUCTION_OBSERVER_STEP
			setClassShutter(DenyAllClasses)
		}

		override fun observeInstructionCount(context: Context, instructionCount: Int) {
			val deadline = DEADLINE.get() ?: return
			if (System.nanoTime() > deadline) throw ScriptTimedOut()
		}
	}

	companion object {
		/**
		 * How long a source's script may run.
		 *
		 * Generous, because slowAES over a real payload is genuinely expensive -- it is AES in
		 * interpreted JavaScript. Short enough that a script written to spin forever gives up
		 * before the user notices, rather than pinning a core for as long as Ageha is open.
		 */
		const val DEFAULT_TIME_BUDGET_MS = 10_000L

		/**
		 * How often the deadline is checked, in interpreter instructions.
		 *
		 * Small enough that an endless loop is caught promptly; large enough that the check is
		 * lost in the noise of real work.
		 */
		private const val INSTRUCTION_OBSERVER_STEP = 10_000

		private const val NANOS_PER_MILLI = 1_000_000L

		/** Set for the duration of one evaluation; read by the factory's observer. */
		private val DEADLINE = ThreadLocal<Long?>()

		/** Appears in stack traces, so a failing site script is identifiable in a bug report. */
		private const val SCRIPT_NAME = "source-script"

		private const val FUNCTION_WRAPPER_PREFIX = "(function(){\n"
		private const val FUNCTION_WRAPPER_SUFFIX = "\n})()"
	}
}

/**
 * A source's script ran past its time budget and was stopped.
 *
 * Deliberately not a `RhinoException`: script-level `catch` only sees errors from Rhino's own
 * hierarchy, so a hostile script cannot catch its own termination and carry on.
 */
class ScriptTimedOut : RuntimeException("A source's script ran too long and was stopped.")
