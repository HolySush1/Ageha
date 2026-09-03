package app.ageha.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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
