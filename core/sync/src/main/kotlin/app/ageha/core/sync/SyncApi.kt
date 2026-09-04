package app.ageha.core.sync

import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.HttpURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * The kotatsu-syncserver HTTP API.
 *
 * Four endpoints, all POST, all JSON, all reconstructed from the Android client because the server
 * has no separate specification:
 *
 * | Endpoint | Body | Answer |
 * |---|---|---|
 * | `/auth` | `{"email","password"}` | `{"token"}`, or a bare quoted string on failure |
 * | `/resource/history` | [SyncDto] with `history` set | 204, or a [SyncDto] to merge |
 * | `/resource/favourites` | [SyncDto] with `favourites` and `categories` set | 204, or a [SyncDto] |
 * | `/forgot-password` | `{"email"}` | 2xx |
 *
 * Three details that are easy to get wrong and impossible to guess:
 *
 *  - **The resource path segment is the database table name**, `history` and `favourites`. Not a
 *    resource name that happens to match -- the Android client interpolates its own `TABLE_HISTORY`
 *    constant straight into the url.
 *  - **204 No Content is a success meaning "nothing to merge"**, not an empty result to apply. An
 *    empty [SyncDto] applied as though it were an answer would delete nothing and merge nothing,
 *    so the distinction is harmless today and would stop being harmless the moment a caller
 *    treated a missing list as an empty one.
 *  - **An error body is a bare JSON string**, `"some message"` with the quotes, not an object with
 *    a message field. [SyncApiException] strips them so the text can go in front of a user.
 *
 * The client here is *given* to this class rather than built by it, so sync travels over the same
 * OkHttp stack as everything else -- one connection pool, one set of timeouts, one place that gets
 * shut down at exit.
 */
class SyncApi(
	private val httpClient: OkHttpClient,
) {

	suspend fun authenticate(syncUrl: String, email: String, password: String): String {
		val body = json.encodeToString(AuthRequest(email, password))
		val response = httpClient.newCall(
			Request.Builder()
				.url(syncUrl.trimEnd('/') + "/auth")
				.post(body.toRequestBody(JSON_MEDIA_TYPE))
				.build(),
		).await()
		response.use {
			val text = it.body?.string().orEmpty()
			if (!it.isSuccessful) throw SyncApiException(text.unquote(), it.code)
			return runCatching { json.decodeFromString<AuthResponse>(text).token }
				.getOrElse {
					// A 200 that is not the expected shape almost always means the url points at
					// something that is not a sync server -- a reverse proxy's landing page, or a
					// login form. Saying so beats a serialisation stack trace.
					throw SyncApiException(
						"The server answered, but not with a sync token. Check the address.",
						HttpURLConnection.HTTP_OK,
					)
				}
		}
	}

	suspend fun forgotPassword(syncUrl: String, email: String) {
		val body = json.encodeToString(ForgotPasswordRequest(email))
		httpClient.newCall(
			Request.Builder()
				.url(syncUrl.trimEnd('/') + "/forgot-password")
				.post(body.toRequestBody(JSON_MEDIA_TYPE))
				.build(),
		).await().use {
			if (!it.isSuccessful) {
				throw SyncApiException(it.body?.string().orEmpty().unquote(), it.code)
			}
		}
	}

	/**
	 * Exchange one resource with the server.
	 *
     * Returns null for 204: the server has nothing to send back. See the class comment.
	 */
	suspend fun exchange(
		syncUrl: String,
		resource: SyncResource,
		token: String,
		payload: SyncDto,
		appVersion: Int,
		databaseVersion: Int,
	): SyncDto? {
		val body = json.encodeToString(payload)
		val response = httpClient.newCall(
			Request.Builder()
				.url(syncUrl.trimEnd('/') + "/resource/" + resource.path)
				.post(body.toRequestBody(JSON_MEDIA_TYPE))
				.header("Authorization", "Bearer " + token)
				// Both headers are sent by the Android client on every request. The server is
				// entitled to refuse a database version it cannot reconcile, and it can only do
				// that if we tell it -- so these are part of the protocol, not diagnostics.
				.header("X-App-Version", appVersion.toString())
				.header("X-Db-Version", databaseVersion.toString())
				.build(),
		).await()
		response.use {
			val text = it.body?.string().orEmpty()
			if (!it.isSuccessful) throw SyncApiException(text.unquote(), it.code)
			if (it.code == HttpURLConnection.HTTP_NO_CONTENT || text.isBlank()) return null
			return runCatching { json.decodeFromString<SyncDto>(text) }.getOrElse { failure ->
				throw SyncApiException("The server sent a reply Ageha could not read: " + failure.message, it.code)
			}
		}
	}

	/**
	 * OkHttp's async call as a suspending one.
	 *
	 * `execute()` on a coroutine dispatcher would block a thread for the whole round trip, and
	 * cancelling the coroutine would not cancel the request. This cancels the call, so quitting
	 * during a sync does not leave a socket waiting on somebody's slow server.
	 */
	private suspend fun Call.await(): Response = suspendCoroutine { continuation ->
		enqueue(object : okhttp3.Callback {
			override fun onResponse(call: Call, response: Response) = continuation.resume(response)
			override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
		})
	}

	private companion object {
		val JSON_MEDIA_TYPE = "application/json".toMediaType()
		val json = Json {
			encodeDefaults = true
			ignoreUnknownKeys = true
			// A history row can hold a NaN percent. Refusing to serialise the payload over one
			// row would block the whole sync.
			allowSpecialFloatingPointValues = true
		}

		/** Error bodies arrive as `"message"`, quotes included. */
		fun String.unquote(): String = trim().removeSurrounding("\"").ifBlank { "The server rejected the request." }
	}
}

/** The two resources the protocol exposes. The path segment is the database table name. */
enum class SyncResource(val path: String) {
	HISTORY("history"),
	FAVOURITES("favourites"),
}

/**
 * The server said no, and said why.
 *
 * Carries the status because the caller's response depends on it: 401 means the token expired and
 * is worth one silent retry, anything else means telling the user.
 */
class SyncApiException(message: String, val code: Int) : Exception(message)

@kotlinx.serialization.Serializable
private data class AuthRequest(val email: String, val password: String)

@kotlinx.serialization.Serializable
private data class AuthResponse(val token: String)

@kotlinx.serialization.Serializable
private data class ForgotPasswordRequest(val email: String)
