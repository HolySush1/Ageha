package app.ageha.core.js

import app.ageha.core.model.JsCapability
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Records that a source asked for a JavaScript capability we did not have.
 *
 * ## Why this exists rather than just throwing
 *
 * Throwing [JsUnavailableException] is necessary but not sufficient. Parsers routinely wrap their
 * anti-bot fallbacks in `runCatchingCancellable { ... }` and swallow whatever comes out --
 * `MangaReaderParser.getNetShieldCookie` is exactly this shape, and it is the path all 257
 * MangaReader-derived sources take when a site serves a NetShield challenge. The parser then
 * carries on and fails later for a different, vaguer reason, so by the time the failure reaches
 * us the real cause is gone.
 *
 * That would leave the user staring at "could not load chapter" when the truthful message is
 * "this source needs the browser component; install it?".
 *
 * So the runtime leaves a breadcrumb in the coroutine context on its way out. The facade installs
 * a recorder around every source call and, when the call fails for any reason, asks the recorder
 * whether an unavailable capability was requested during it. If one was, the failure is reported
 * as [app.ageha.core.model.SourceFailure.MissingJsRuntime] instead of whatever generic error the
 * parser ended up producing.
 *
 * A coroutine-context element is the right carrier here: parser calls hop threads freely, so a
 * `ThreadLocal` would lose the breadcrumb, while the context propagates to every child coroutine
 * the parser launches.
 */
class JsAttemptRecorder : AbstractCoroutineContextElement(Key) {

	companion object Key : CoroutineContext.Key<JsAttemptRecorder> {

		/**
		 * Which refusal to report when several happened, ordered by what the user can do about it.
		 * An interactive-browser prompt is the most actionable; a plain-script refusal the least,
		 * since the QuickJS backend ships with the app and its absence means an Ageha bug.
		 */
		private val REPORTING_ORDER = listOf(
			JsCapability.INTERACTIVE_BROWSER,
			JsCapability.PAGE_CONTEXT,
			JsCapability.REQUEST_INTERCEPTION,
			JsCapability.LOCAL_STORAGE,
			JsCapability.PLAIN_SCRIPT,
		)
	}

	private val missing = AtomicReference<EnumSet<JsCapability>>(EnumSet.noneOf(JsCapability::class.java))

	/** Called by a [JsRuntime] that is about to refuse. */
	fun recordUnavailable(capability: JsCapability) {
		missing.updateAndGet { current ->
			EnumSet.copyOf(current).apply { add(capability) }
		}
	}

	/**
	 * The capabilities that were asked for and refused during this call, or an empty set.
	 *
	 * When more than one was refused, [primaryMissing] picks the one worth telling the user about.
	 */
	val missingCapabilities: Set<JsCapability>
		get() = missing.get().toSet()

	/** The capability worth reporting, when several were refused. */
	val primaryMissing: JsCapability?
		get() = missing.get().minByOrNull { REPORTING_ORDER.indexOf(it) }
}

/**
 * Leave a breadcrumb, then throw.
 *
 * Every [JsRuntime] that cannot serve a request should refuse through this, so the failure is
 * recoverable even if the calling parser swallows the exception.
 */
suspend fun refuseJsCapability(capability: JsCapability): Nothing {
	coroutineContext[JsAttemptRecorder]?.recordUnavailable(capability)
	throw JsUnavailableException(capability)
}
