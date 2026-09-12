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
	private val clock: () -> Long = System::currentTimeMillis,
) : CookieJar {

	private val lock = ReentrantReadWriteLock()

	/** Keyed by identity so a re-issued cookie replaces the old one rather than accumulating. */
	private val cookies = LinkedHashMap<CookieKey, Cookie>()

	private var dirty = false

	/** When the jar last reached disk. Read by the write coalescing in [persistSoon]. */
	private var lastPersistAt = 0L

	init {
		load()
	}

	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		val now = clock()
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
		var touchedDisk = false
		lock.write {
			for (cookie in cookies) {
				val key = CookieKey.of(cookie)
				// Only a cookie that belongs on disk, or one displacing a cookie that was already
				// there, can change the file. A site re-issuing the same session cookie on every
				// response -- which plenty do -- used to mark the jar dirty and trigger a full
				// re-serialise for a list that had not changed.
				if (cookie.persistent || this.cookies[key]?.persistent == true) {
					touchedDisk = true
				}
				this.cookies[key] = cookie
			}
			if (touchedDisk) dirty = true
		}
		if (touchedDisk) persistSoon()
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

	/**
	 * Drop everything for one host. Used by "clear cookies for this source" in settings.
	 *
	 * Matches the whole domain family, not just the exact string. A cookie is stored under the
	 * domain that issued it, so a site whose login lives on `www.` and whose clearance lives on the
	 * bare domain writes two different domains -- and an equality check cleared one of them and
	 * left the user still signed in, or still carrying the clearance they asked to be rid of.
	 * "Clear cookies for this source" has to mean every cookie the source could receive back.
	 */
	fun clearForHost(host: String) {
		lock.write {
			cookies.entries.removeIf { it.key.domain.isSameSiteAs(host) }
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

	/**
	 * Flush to disk, but no more often than [PERSIST_INTERVAL_MS].
	 *
	 * The jar used to write itself out synchronously on every response carrying a `Set-Cookie`,
	 * on the thread that was in the middle of an HTTP call. That is a full JSON re-serialise plus
	 * a file write and a rename, and the reader hits it once per page image on any source that
	 * re-issues a cookie -- so a chapter of forty pages meant forty rewrites of the same jar.
	 *
	 * The cost of coalescing is that a hard kill can lose up to [PERSIST_INTERVAL_MS] of cookies.
	 * Ordinary exit cannot: `SourceStack.close` calls [persist], which always writes.
	 */
	private fun persistSoon() {
		val due = lock.read { clock() - lastPersistAt >= PERSIST_INTERVAL_MS }
		if (due) persist()
	}

	/** Flush to disk now, if anything has changed. Worth calling before exit. */
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
			lock.write {
				dirty = false
				lastPersistAt = clock()
			}
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

		/**
		 * How long a write waits for company. Short enough that a crash costs nothing anyone
		 * would notice, long enough that a chapter's worth of page requests is one write.
		 */
		const val PERSIST_INTERVAL_MS = 2_000L
	}
}

/**
 * Whether a stored cookie's domain belongs to [host]'s site, in either direction.
 *
 * Both directions, because either can be the wider one: a cookie stored for `example.test` is sent
 * to `www.example.test`, and a cookie stored for `www.example.test` is one the user means when they
 * say "this source". Suffix matching is deliberately naive about the public suffix list -- it is
 * used only to *delete* cookies the user asked to be rid of, where over-reach costs a re-login and
 * under-reach costs the thing they were trying to fix.
 */
private fun String.isSameSiteAs(host: String): Boolean {
	val a = lowercase()
	val b = host.lowercase()
	return a == b || a.endsWith(".$b") || b.endsWith(".$a")
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
