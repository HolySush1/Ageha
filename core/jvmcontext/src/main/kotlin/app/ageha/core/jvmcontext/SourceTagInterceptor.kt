package app.ageha.core.jvmcontext

import app.ageha.core.network.HttpHeaders
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Turns [HttpHeaders.SOURCE_NAME] into the request tag [ParserDispatchInterceptor] dispatches on.
 *
 * ## Why the source travels as a header
 *
 * A tag is what the dispatch actually wants, and a header is the only way to get one there.
 * `MangaSource` is a parsers type, so only this module may name it -- but the code that asks for
 * an image is Coil, called from `:core:image` and `:core:designsystem`, which may not (CLAUDE.md
 * 5). Those layers can put a string into a header map they already carry per source; they cannot
 * construct a tag. So the name travels as a header and becomes a tag here, on the far side of the
 * wall, and the header is removed in the same breath -- it is Ageha's private business and no site
 * should see it. Source names are enum constant names, so they are always a legal header value.
 *
 * ## Why images need this at all
 *
 * A parser's own calls arrive tagged already -- the library's `OkHttpWebClient.addTags` does it --
 * so listing, details and page resolution reach their parser's `intercept` without help. Image
 * requests do not: Ageha builds those, from a url the parser handed back, and an untagged request
 * goes straight past [ParserDispatchInterceptor].
 *
 * That gap is not cosmetic. `MangaParser` extends `okhttp3.Interceptor` and a good number of
 * parsers do their real work there, *on the image response*: MANGA Plus XOR-decrypts the bytes
 * with a key from the url fragment, and ExHentai, MangaReader.to, Comix, Mangago, PhiliaScans,
 * CuuTruyen, MimiHentai and YuriGarden all reassemble scrambled tiles through
 * `MangaLoaderContext.redrawImageResponse`. Skip the interceptor and the descrambler Ageha
 * implements is simply never called: pages arrive encrypted or scrambled and the source looks
 * dead. Kagane needs it for an `Origin` its CDN insists on.
 *
 * Placed *above* [ParserDispatchInterceptor] so the tag exists before the dispatch reads it, and
 * so the parser never sees the marker.
 */
internal class SourceTagInterceptor(
	/** Resolves a source name to the loaded build's own `MangaSource`. Null if it has no such source. */
	private val sourceForName: (String) -> MangaSource?,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val name = request.header(HttpHeaders.SOURCE_NAME) ?: return chain.proceed(request)
		val source = sourceForName(name)
		val tagged = request.newBuilder()
			.removeHeader(HttpHeaders.SOURCE_NAME)
			.apply {
				// An unknown name still gets the header stripped. It means this build does not
				// have that source, which costs interception for this request and nothing else --
				// the same degradation ParserDispatchInterceptor already makes for an unknown tag.
				if (source != null) tag(MangaSource::class.java, source)
			}
			.build()
		return chain.proceed(tagged)
	}
}
