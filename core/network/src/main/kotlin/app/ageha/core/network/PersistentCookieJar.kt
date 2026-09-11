package app.ageha.core.network

import app.ageha.core.model.BrowserCookie
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A cookie jar that survives restarts.
 *
 * Persistence is not a nicety here. Cloudflare clearance cookies are the difference between
 * "opens instantly" and "solve a challenge every launch", and several sources hold login state
 * only in cookies. `kotatsu-dl` uses an in-memory jar because it is a one-shot CLI; a desktop app
 * that forgets everything on quit would be worse than the Android app in a way users notice
 * immediately.
 *
 * Session cookies (those with no expiry) are deliberately *not* written to disk -- that is what
 * "session" means, and persisting them would keep stale auth state alive across restarts.
 */
class PersistentCookieJar(
	private val storageFile: File,
) : CookieJar {

	private val lock = ReentrantReadWriteLock()

	/** Keyed by identity so a re-issued cookie replaces the old one rather than accumulating. */
	private val cookies = LinkedHashMap<CookieKey, Cookie>()

	private var dirty = false

	init {
		load()
	}

	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		val now = System.currentTimeMillis()
		val expired = mutableListOf<CookieKey>()
		val matching = lock.read {
			cookies.entries.mapNotNull { (key, cookie) ->
				when {
					cookie.expiresAt <= now -> {
						expired += key
						null
					}

					cookie.matches(url) -> cookie
					else -> null
				}
			}
		}
		if (expired.isNotEmpty()) {
			lock.write {
				expired.forEach(cookies::remove)
				dirty = true
			}
		}
		return matching
	}

	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		if (cookies.isEmpty()) return
		lock.write {
			for (cookie in cookies) {
				this.cookies[CookieKey.of(cookie)] = cookie
			}
			dirty = true
		}
		persist()
	}

	/**
	 * Take the cookies a real browser earned at [url], so that OkHttp's next request carries them.
	 *
	 * This is what makes clearing a Cloudflare check in the browser component worth anything.
	 * Android gets it for free, because the WebView's cookie store *is* the app's; Ageha's browser
	 * keeps its own, and a `cf_clearance` left there lets the browser in and nothing else. A check
	 * passed and then thrown away looks, from the user's side, exactly like one that failed.
	 *
	 * @return how many were accepted -- a cookie Chromium reports with an unusable domain is dropped
	 *   rather than guessed at.
	 */
	fun saveBrowserCookies(url: String, cookies: List<BrowserCookie>): Int {
		val target = url.toHttpUrlOrNull() ?: return 0
		val converted = cookies.mapNotNull { it.toOkHttpCookie() }
		saveFromResponse(target, converted)
		return converted.size
	}

	/** Drop everything for one host. Used by "clear cookies for this source" in settings. */
	fun clearForHost(host: String) {
		lock.write {
			cookies.entries.removeIf { it.key.domain.equals(host, ignoreCase = true) }
			dirty = true
		}
		persist()
	}

	fun clearAll() {
		lock.write {
			cookies.clear()
			dirty = true
		}
		persist()
	}

	/** Flush to disk. Called after every write, and worth calling again before exit. */
	fun persist() {
		val snapshot = lock.read {
			if (!dirty) return
			// Session cookies stay in memory only.
			cookies.values.filter { it.persistent }.map(StoredCookie::of)
		}
		runCatching {
			storageFile.parentFile?.mkdirs()
			val tmp = File(storageFile.parentFile, storageFile.name + ".tmp")
			tmp.writeText(json.encodeToString(snapshot))
			// Atomic-ish replace, so a crash mid-write cannot leave a truncated jar behind.
			if (!tmp.renameTo(storageFile)) {
				storageFile.delete()
				tmp.renameTo(storageFile)
			}
			lock.write { dirty = false }
		}
	}

	private fun load() {
		if (!storageFile.isFile) return
		runCatching {
			val stored: List<StoredCookie> = json.decodeFromString(storageFile.readText())
			val now = System.currentTimeMillis()
			lock.write {
				for (entry in stored) {
					val cookie = entry.toCookie() ?: continue
					if (cookie.expiresAt > now) {
						cookies[CookieKey.of(cookie)] = cookie
					}
				}
			}
		}.onFailure {
			// A corrupt jar must not stop the app from starting. Worst case the user re-authenticates.
			runCatching { storageFile.delete() }
		}
	}

	private data class CookieKey(val name: String, val domain: String, val path: String) {
		companion object {
			fun of(cookie: Cookie) = CookieKey(cookie.name, cookie.domain, cookie.path)
		}
	}

	@Serializable
	private data class StoredCookie(
		val name: String,
		val value: String,
		val expiresAt: Long,
		val domain: String,
		val path: String,
		val secure: Boolean,
		val httpOnly: Boolean,
		val hostOnly: Boolean,
	) {

		/**
		 * Rebuild via [Cookie.Builder] rather than round-tripping through `Cookie.toString()`.
		 * The string form omits attributes for host-only cookies, so a parse/serialise cycle
		 * silently widens their scope.
		 */
		fun toCookie(): Cookie? = runCatching {
			Cookie.Builder()
				.name(name)
				.value(value)
				.expiresAt(expiresAt)
				.path(path)
				.apply {
					if (hostOnly) hostOnlyDomain(domain) else domain(domain)
					if (secure) secure()
					if (httpOnly) httpOnly()
				}
				.build()
		}.getOrNull()

		companion object {
			fun of(cookie: Cookie) = StoredCookie(
				name = cookie.name,
				value = cookie.value,
				expiresAt = cookie.expiresAt,
				domain = cookie.domain,
				path = cookie.path,
				secure = cookie.secure,
				httpOnly = cookie.httpOnly,
				hostOnly = cookie.hostOnly,
			)
		}
	}

	private companion object {
		val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	}
}

/**
 * Chromium's view of a cookie, rebuilt as OkHttp's, or null when it cannot be.
 *
 * Built field by field for the reason [PersistentCookieJar] gives for its own storage: the string
 * form loses the host-only flag. Chromium marks a cookie that covers subdomains with a leading dot
 * and a host-only one without, and that is the only place the difference is recorded.
 */
private fun BrowserCookie.toOkHttpCookie(): Cookie? = runCatching {
	Cookie.Builder()
		.name(name)
		.value(value)
		.path(path.ifEmpty { "/" })
		.apply {
			val bare = domain.removePrefix(".")
			if (domain.startsWith(".")) domain(bare) else hostOnlyDomain(bare)
			expiresAtMillis?.let(::expiresAt)
			if (secure) secure()
			if (httpOnly) httpOnly()
		}
		.build()
}.getOrNull()
