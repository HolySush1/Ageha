package app.ageha.core.jvmcontext

import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.IOException

/**
 * Gives each source's parser a turn in the HTTP chain.
 *
 * This wiring is easy to miss and expensive to miss. `MangaParser` extends `okhttp3.Interceptor`,
 * but nothing installs it automatically: parsers issue requests straight through
 * `context.httpClient`, tagging each with their `MangaSource`, and it is the *host* that has to
 * notice the tag and hand the request to the right parser. The Android app does this inside its
 * `CommonHeadersInterceptor`.
 *
 * Skip it and nothing fails loudly. Requests still succeed. What quietly stops working is every
 * per-source behaviour a parser implements through interception: image descrambling, signed URL
 * rewriting, source-specific auth headers. Pages come back scrambled or 403, and it looks like the
 * site changed rather than like a wiring bug.
 *
 * Header merging is not done here: `MangaParserWrapper.intercept` (which wraps every parser the
 * generated factory produces) already merges `getRequestHeaders()` before delegating. Doing it
 * again here would be redundant and would risk overriding a parser that set a header deliberately.
 */
internal class ParserDispatchInterceptor(
	private val parserForSource: (MangaSource) -> MangaParser?,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val source = request.tag(MangaSource::class.java)
			?: return chain.proceed(request)
		val parser = parserForSource(source)
			?: return chain.proceed(request)

		return try {
			parser.intercept(chain)
		} catch (e: CancellationException) {
			throw e
		} catch (e: IOException) {
			throw e
		} catch (e: Error) {
			throw e
		} catch (e: Exception) {
			// OkHttp only tolerates IOException escaping an interceptor; anything else corrupts
			// the connection pool's bookkeeping and surfaces later as an unrelated failure. The
			// original is kept as the cause so the facade can still classify it properly.
			throw IOException("Parser interceptor for '${source.name}' failed: ${e.message}", e)
		}
	}
}
