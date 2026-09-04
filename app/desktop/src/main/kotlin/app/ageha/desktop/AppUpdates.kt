package app.ageha.desktop

import app.ageha.core.model.AgehaVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** What a check found. */
sealed interface AppUpdateOutcome {

	/** Running the newest published release. */
	data class UpToDate(val version: String) : AppUpdateOutcome

	data class Available(val version: String, val url: String) : AppUpdateOutcome

	/** Could not tell. Never worth interrupting anyone over. */
	data class Failed(val reason: String) : AppUpdateOutcome

	fun describe(): String = when (this) {
		is UpToDate -> "Ageha " + version + " is the newest release."
		is Available -> "Ageha " + version + " is available. You are running " +
			AgehaVersion.NAME + ".\n" + url
		is Failed -> "Could not check for updates: " + reason
	}
}

/**
 * Asks GitHub whether there is a newer release.
 *
 * Deliberately only *asks*. Downloading and swapping an installed application is the packaging's
 * job, it is the part that needs a signature to be safe, and Ageha is not signed yet
 * (`docs/RELEASING.md`) -- so an in-app installer would be an unsigned binary fetching and
 * executing another unsigned binary, which is a worse answer than a link.
 *
 * Unauthenticated, because a public repository's releases are public and a token would be a
 * credential Ageha would have to hold for no gain. That caps the rate at 60 requests an hour per
 * address, which a once-per-launch check cannot reach.
 */
class AppUpdateChecker(
	private val httpClient: OkHttpClient,
	private val repo: String = AgehaVersion.REPO,
	private val currentVersion: String = AgehaVersion.NAME,
	private val apiBase: String = "https://api.github.com",
) {

	suspend fun check(): AppUpdateOutcome = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(apiBase + "/repos/" + repo + "/releases/latest")
			.header("Accept", "application/vnd.github+json")
			.build()
		try {
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					// A repository with no releases yet answers 404, which is not an error worth
					// showing anyone -- it is the state this project is in right now.
					return@withContext AppUpdateOutcome.Failed("GitHub answered HTTP " + response.code)
				}
				val release = json.decodeFromString<GithubRelease>(response.body.string())
				val latest = release.tagName.removePrefix("v")
				if (isNewer(latest, currentVersion)) {
					AppUpdateOutcome.Available(latest, release.htmlUrl)
				} else {
					AppUpdateOutcome.UpToDate(currentVersion)
				}
			}
		} catch (e: Exception) {
			AppUpdateOutcome.Failed(e.message ?: e::class.simpleName.orEmpty())
		}
	}

	companion object {

		private val json = Json { ignoreUnknownKeys = true }

		/**
		 * Compare two dotted versions numerically.
		 *
		 * String comparison is the trap: `"0.10.0" < "0.9.0"` lexicographically, so a user on 0.9
		 * would be told to upgrade to 0.10 and a user on 0.10 would be told they were ahead. Parts
		 * are compared as integers, a missing part counts as zero so `1.2` and `1.2.0` are equal,
		 * and anything unparseable makes the comparison return false -- failing to offer an update
		 * is a great deal better than nagging about one that does not exist.
		 */
		fun isNewer(candidate: String, current: String): Boolean {
			val a = parse(candidate) ?: return false
			val b = parse(current) ?: return false
			for (i in 0 until maxOf(a.size, b.size)) {
				val left = a.getOrElse(i) { 0 }
				val right = b.getOrElse(i) { 0 }
				if (left != right) return left > right
			}
			return false
		}

		/** `1.2.3-SNAPSHOT` parses as `[1, 2, 3]`; a pre-release suffix is not a version part. */
		private fun parse(version: String): List<Int>? = version
			.trim()
			.removePrefix("v")
			.substringBefore('-')
			.substringBefore('+')
			.split('.')
			.map { it.toIntOrNull() ?: return null }
			.takeIf { it.isNotEmpty() }
	}
}

@Serializable
private data class GithubRelease(
	@SerialName("tag_name") val tagName: String,
	@SerialName("html_url") val htmlUrl: String = "",
)
