package app.ageha.core.jvmcontext

import app.ageha.core.network.HttpHeaders
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Names the *source* as the Referer for a source's requests, as the Android app does.
 *
 * `:core:network`'s [app.ageha.core.network.CommonHeadersInterceptor] cannot do this. It sees only
 * a request, so the best it can do is the request's own host -- and for a site that serves its
 * pages or its API from a separate host, that is the wrong answer in the one way that matters: a
 * CDN which hands out images only to its own site refuses its own hostname exactly as it refuses
 * no Referer at all. ComicK's does, which is how every page of a ComicK chapter came back 403.
 *
 * [imageHeaders] already fixes that for image requests, because Ageha builds those and knows the
 * source. This is the same repair for the requests the *parser* builds, where Ageha does not get
 * to choose the headers -- it only gets to fill the gaps the parser left.
 *
 * Sits below [ParserDispatchInterceptor], so the parser's own `getRequestHeaders()` have already
 * been merged and a Referer the parser set deliberately is left alone.
 */
internal class SourceRefererInterceptor(
	private val parserForSource: (MangaSource) -> MangaParser?,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (request.header(HttpHeaders.REFERER) != null) return chain.proceed(request)
		val source = request.tag(MangaSource::class.java) ?: return chain.proceed(request)
		val parser = parserForSource(source) ?: return chain.proceed(request)
		val referer = sourceReferer(parser.domain) ?: return chain.proceed(request)
		return chain.proceed(
			request.newBuilder().header(HttpHeaders.REFERER, referer).build(),
		)
	}
}
