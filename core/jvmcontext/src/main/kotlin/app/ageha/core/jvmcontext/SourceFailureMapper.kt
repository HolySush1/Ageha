package app.ageha.core.jvmcontext

import app.ageha.core.js.JsAttemptRecorder
import app.ageha.core.js.JsUnavailableException
import app.ageha.core.model.BrowserActionRequiredException
import app.ageha.core.model.JsCapability
import app.ageha.core.model.SourceFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import java.io.IOException

/**
 * Runs a parser call and turns whatever comes out into a [SourceFailure].
 *
 * ## Why this is not just a try/catch
 *
 * A parser that needs JavaScript we do not have does not necessarily throw something that says so.
 * `MangaReaderParser.getNetShieldCookie` -- the anti-bot path shared by 257 sources -- wraps its
 * `evaluateJs` call in `runCatchingCancellable { }` and swallows the result. The parser then
 * carries on without the cookie and fails later, somewhere else, with a vague parse error. By the
 * time the exception reaches us, the actual cause is gone.
 *
 * Reporting that as "could not load chapter" would be true and useless. The useful message is
 * "this source needs the browser component; install it?".
 *
 * So every call runs inside a [JsAttemptRecorder]. If the JavaScript layer refused anything during
 * the call, that is what gets reported, regardless of what the parser did with the exception.
 * A refusal always wins over the parser's own error, because a missing capability *causes* the
 * downstream failure -- reporting the symptom instead would send the user to look at the wrong
 * thing entirely.
 *
 * A call can also hit a refusal and still succeed, when the parser has a degraded fallback. That
 * is not a failure and is not reported as one: the capability gap only surfaces if the call
 * actually fails.
 */
internal suspend fun <T> runSourceCall(
	sourceName: String,
	block: suspend () -> T,
): T {
	val recorder = JsAttemptRecorder()
	return try {
		withContext(recorder) { block() }
	} catch (e: CancellationException) {
		throw e
	} catch (e: Throwable) {
		throw classify(sourceName, e, recorder)
	}
}

private fun classify(
	sourceName: String,
	error: Throwable,
	recorder: JsAttemptRecorder,
): SourceFailure {
	// 1. A refused JavaScript capability outranks everything else, even when the parser buried it.
	recorder.primaryMissing?.let { capability ->
		return SourceFailure.MissingJsRuntime(sourceName, capability, error)
	}

	// 2. An explicit, un-swallowed refusal, in case it was thrown outside a recorded scope.
	unwrap(error) { it is JsUnavailableException }?.let {
		return SourceFailure.MissingJsRuntime(sourceName, (it as JsUnavailableException).capability, error)
	}

	// 3. The parser asked for a human with a browser.
	unwrap(error) { it is BrowserActionRequiredException }?.let {
		val e = it as BrowserActionRequiredException
		return if (e.isCloudflare) {
			SourceFailure.ChallengeRequired(sourceName, e.url, error)
		} else {
			SourceFailure.MissingJsRuntime(sourceName, JsCapability.INTERACTIVE_BROWSER, error)
		}
	}

	// 4. Parser-library exceptions, which are the well-behaved case.
	unwrap(error) { it is TooManyRequestExceptions }?.let {
		val e = it as TooManyRequestExceptions
		val retryAfter = e.retryAt?.let { at -> at.toEpochMilli() - System.currentTimeMillis() }
		return SourceFailure.RateLimited(sourceName, retryAfter?.takeIf { ms -> ms > 0 }, error)
	}
	unwrap(error) { it is AuthRequiredException }?.let {
		return SourceFailure.AuthRequired(sourceName, error)
	}
	unwrap(error) { it is NotFoundException }?.let {
		return SourceFailure.NotFound(sourceName, error)
	}
	unwrap(error) { it is ContentUnavailableException }?.let {
		return SourceFailure.ContentUnavailable(sourceName, it.message, error)
	}
	unwrap(error) { it is ParseException }?.let {
		return SourceFailure.Unparseable(sourceName, (it as ParseException).url, error)
	}

	// 5. An HTTP status the parser did not handle. jsoup raises these, and they are IOExceptions,
	// so they must be read before the transport case below or every 403 becomes "network is down".
	unwrap(error) { it is HttpStatusException }?.let {
		val e = it as HttpStatusException
		return when (e.statusCode) {
			403 -> SourceFailure.Blocked(sourceName, e.statusCode, e.url, error)
			404, 410 -> SourceFailure.NotFound(sourceName, error)
			429 -> SourceFailure.RateLimited(sourceName, null, error)
			// 5xx really is the server failing, and really is worth retrying.
			in 500..599 -> SourceFailure.Network(sourceName, e)
			else -> SourceFailure.Blocked(sourceName, e.statusCode, e.url, error)
		}
	}

	// 6. Transport. Checked after the parser exceptions on purpose: several of them extend
	// IOException, and classifying them as "network is down" would be actively misleading.
	unwrap(error) { it is IOException }?.let {
		return SourceFailure.Network(sourceName, it as IOException)
	}

	return SourceFailure.Unknown(sourceName, error)
}

/**
 * Find the first exception in the cause chain matching [predicate].
 *
 * Needed because [app.ageha.core.jvmcontext.ParserDispatchInterceptor] has to re-wrap non-IO
 * exceptions as IOException (OkHttp permits nothing else to escape an interceptor), so the
 * interesting exception is often a cause rather than the top of the stack.
 */
private fun unwrap(error: Throwable, predicate: (Throwable) -> Boolean): Throwable? {
	var current: Throwable? = error
	var guard = 0
	while (current != null && guard++ < MAX_CAUSE_DEPTH) {
		if (predicate(current)) return current
		if (current.cause === current) return null
		current = current.cause
	}
	return null
}

private const val MAX_CAUSE_DEPTH = 16
