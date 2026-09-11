package app.ageha.core.network

import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** User-agent strings. Kept here so the whole app tells one story about what it is. */
object UserAgents {
	/**
	 * Exactly the user agent the bundled Chromium presents, down to the zeroed minor versions.
	 *
	 * Not a cosmetic choice. A Cloudflare clearance is bound to the user agent that earned it, and
	 * the browser component earns clearances that OkHttp then presents -- so the two must say the
	 * same thing. The obvious alternative, telling Chromium to claim whatever this string says,
	 * was tried and fails: an engine claiming an older version than it is fails Cloudflare's check
	 * outright, where the same engine telling the truth passes it in two seconds.
	 *
	 * `BrowserUserAgentTest` fails the build when a JCEF bump moves Chromium's major version away
	 * from the one written here.
	 */
	const val CHROME_DESKTOP =
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
			"Chrome/146.0.0.0 Safari/537.36"
	const val FIREFOX_DESKTOP =
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
}

/**
 * Builds the one [OkHttpClient] the whole application shares.
 *
 * One client, not one per source: OkHttp's connection pool, DNS cache and thread pool are the
 * expensive parts and they are per-client. Per-source behaviour (headers, descrambling, auth)
 * arrives through parser interceptors instead -- see the dispatcher in :core:jvmcontext.
 *
 * Note what is *not* here: `kotatsu-dl` installs a trust-all `X509TrustManager` so it can debug
 * sources with broken certificates. That is defensible in a CLI you run yourself and indefensible
 * in a shipped desktop app -- it would disable certificate validation for every user, on every
 * request, permanently. Ageha does not copy it.
 */
object AgehaHttpClient {

	fun build(
		cookieJar: PersistentCookieJar,
		cacheDir: File = AgehaPaths.httpCacheDir,
		cacheSizeBytes: Long = DEFAULT_CACHE_BYTES,
		userAgent: () -> String = { UserAgents.CHROME_DESKTOP },
		acceptLanguage: () -> String = ::systemAcceptLanguage,
		proxy: Proxy? = null,
		minRequestIntervalMillis: Long = 250L,
	): OkHttpClient = OkHttpClient.Builder()
		.cookieJar(cookieJar)
		.cache(Cache(cacheDir, cacheSizeBytes))
		.addInterceptor(CommonHeadersInterceptor(userAgent, acceptLanguage))
		.addInterceptor(RateLimitInterceptor(minIntervalMillis = minRequestIntervalMillis))
		.connectTimeout(20, TimeUnit.SECONDS)
		.readTimeout(60, TimeUnit.SECONDS)
		.writeTimeout(20, TimeUnit.SECONDS)
		.followRedirects(true)
		.followSslRedirects(true)
		.apply { if (proxy != null) proxy(proxy) }
		.build()

	/** An Accept-Language built from the user's locale, with English as a universal fallback. */
	fun systemAcceptLanguage(): String {
		val locale = java.util.Locale.getDefault()
		val tag = locale.toLanguageTag()
		return if (tag.startsWith("en")) {
			"$tag,en;q=0.9"
		} else {
			"$tag,${locale.language};q=0.9,en;q=0.8"
		}
	}

	private const val DEFAULT_CACHE_BYTES = 256L * 1024 * 1024
}
