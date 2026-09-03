package app.ageha.core.parsers

import app.ageha.core.js.JsRuntime
import app.ageha.core.js.NoJsRuntime
import app.ageha.core.network.PersistentCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fetches newer parsers builds and decides whether Ageha can run them.
 *
 * ## Why this is not "compare version numbers"
 *
 * Upstream publishes no version tags at all. git ls-remote --tags is empty and JitPack's latestOk
 * endpoint returns nothing, so every build is identified by a commit SHA (docs/FINDINGS.md 1). A
 * SHA carries no ordering and no compatibility signal, so "is there something newer" can only mean
 * "is master's HEAD different from what we are running", and "is it safe" can only be answered by
 * loading it, which is what [CompatibilityGate] does.
 *
 * JitPack also builds on demand. Asking for a SHA nobody has requested starts a build and returns
 * a status that is neither success nor failure for a while, so fetching means polling. It also
 * rate limits anonymous callers with HTTP 429 rather than waiting -- which is how the very first
 * build of this project failed -- so the poll treats 429 as "not yet" rather than as failure.
 */
class ParsersUpdateService(
	private val httpClient: OkHttpClient,
	private val installation: ParsersInstallation,
	private val cookieJar: PersistentCookieJar,
	private val jsRuntime: JsRuntime = NoJsRuntime,
	private val clock: () -> Long = System::currentTimeMillis,
) {

	/**
	 * Look for a newer build and, if there is one, fetch and gate it.
	 *
	 * Does not activate anything. Activation is a separate, explicit step so the caller can honour
	 * the user's automatic / notify-only / manual preference.
	 */
	suspend fun checkForUpdate(): UpdateOutcome = withContext(Dispatchers.IO) {
		val state = installation.read()

		// A pin means a pin. Reporting what is available is fine; acting on it is not.
		state.pinnedVersion?.let { return@withContext UpdateOutcome.Pinned(it) }

		val head = runCatching { resolveHeadSha() }.getOrElse {
			return@withContext UpdateOutcome.CheckFailed("could not reach GitHub: " + it.message)
		}
		installation.write(installation.read().copy(lastCheckedAt = clock()))

		val current = state.activeVersion ?: BundledParsers.VERSION
		if (head.startsWith(current) || current.startsWith(head)) {
			return@withContext UpdateOutcome.UpToDate(current)
		}

		// Never re-fetch something already refused. Without this a six-hourly check re-downloads
		// and re-rejects the same broken build forever, and re-notifies about it forever.
		state.rejected[head]?.let {
			return@withContext UpdateOutcome.PreviouslyRejected(head, it)
		}

		fetchAndGate(head)
	}

	/** Download [version], stage it, and put it through the gate. */
	suspend fun fetchAndGate(version: String): UpdateOutcome = withContext(Dispatchers.IO) {
		val ready = runCatching { awaitJitPackBuild(version) }.getOrElse {
			return@withContext UpdateOutcome.CheckFailed("JitPack could not be reached: " + it.message)
		}
		if (!ready) {
			return@withContext UpdateOutcome.CheckFailed("JitPack has no usable build for " + version)
		}

		// Staged beside the final directory, never into it, so an interrupted download cannot
		// leave behind something that looks installed.
		val staging = File(installation.directoryFor(version).parentFile, version + ".staging")
		staging.deleteRecursively()
		staging.mkdirs()
		try {
			val parsersJar = File(staging, "kotatsu-parsers.jar")
			download(artifactUrl(version, "jar"), parsersJar, artifactUrl(version, "jar.sha1"))

			val libs = File(staging, ParsersInstallation.LIBS_DIR).apply { mkdirs() }
			for (dependency in readChildDependencies(version)) {
				val failure = runCatching {
					download(dependency.url, File(libs, dependency.fileName), dependency.url + ".sha1")
				}.exceptionOrNull()
				if (failure != null) {
					return@withContext UpdateOutcome.CheckFailed(
						"could not fetch " + dependency.fileName + ": " + failure.message,
					)
				}
			}

			// Ageha's own child-side code is not downloaded -- it ships with the app. If a build
			// has moved past what this bridge was compiled against, the gate is what notices.
			val bridgeJar = File(staging, "ageha-bridge.jar")
			installation.bridgeJarFor(BundledParsers.ensureExtracted(installation))
				.copyTo(bridgeJar, overwrite = true)

			when (
				val verdict = CompatibilityGate.evaluate(
					parsersJar = parsersJar,
					bridgeJar = bridgeJar,
					extraJars = libs.listFiles()?.toList().orEmpty(),
					version = version,
					httpClient = httpClient,
					cookieJar = cookieJar,
					jsRuntime = jsRuntime,
				)
			) {
				is GateVerdict.Accepted -> {
					// Locked while still staged, so the manifest describes exactly what was
					// gated. Every later load re-verifies against it.
					val lock = ParsersLock.create(version, staging)
					ParsersLock.write(staging, lock)

					val target = installation.directoryFor(version)
					target.deleteRecursively()
					check(staging.renameTo(target)) { "could not place the staged build" }
					UpdateOutcome.Ready(
						version = version,
						sourceCount = verdict.sourceCount,
						sha256 = lock.files.getValue("kotatsu-parsers.jar"),
					)
				}

				is GateVerdict.Rejected -> {
					installation.reject(version, verdict.reason)
					UpdateOutcome.Rejected(version, verdict.reason, verdict.userMessage())
				}
			}
		} finally {
			staging.deleteRecursively()
		}
	}

	/** Make a gated build the one that loads next launch, keeping the current one to roll back to. */
	fun activate(version: String) {
		require(installation.isInstalled(version)) { version + " is not installed" }
		installation.activate(version)
	}

	// ---- upstream ----------------------------------------------------------------------------

	private fun resolveHeadSha(): String {
		val request = Request.Builder()
			.url("https://api.github.com/repos/" + REPO + "/commits/" + BRANCH)
			.header("Accept", "application/vnd.github.sha")
			.build()
		httpClient.newCall(request).execute().use { response ->
			check(response.isSuccessful) { "HTTP " + response.code }
			return response.body.string().trim().take(SHA_LENGTH)
		}
	}

	/**
	 * Wait for JitPack to have a usable build, asking it to make one if it has not.
	 *
	 * Anything other than 200 is treated as "not yet". That is deliberate rather than lazy: 404
	 * means the build has not been requested, 4xx-with-429 means we are being rate limited, and
	 * 5xx means JitPack is mid-build. None of those say the build is bad, and treating any of
	 * them as failure would make updates fail for reasons unrelated to the build.
	 */
	private suspend fun awaitJitPackBuild(version: String): Boolean {
		var wait = INITIAL_POLL_MILLIS
		repeat(MAX_POLLS) {
			val request = Request.Builder().url(artifactUrl(version, "pom")).head().build()
			val code = runCatching {
				httpClient.newCall(request).execute().use { it.code }
			}.getOrDefault(-1)
			if (code == 200) return true
			delay(wait)
			wait = (wait * 2).coerceAtMost(MAX_POLL_INTERVAL_MILLIS)
		}
		return false
	}

	/**
	 * The dependencies that belong in the child, read from the build's own POM.
	 *
	 * Resolved rather than listed, because the list is neither obvious nor stable: alongside the
	 * documented org.json and androidx.collection it turns up androidx.annotation, which no
	 * documentation mentions. Anything the parent already provides is skipped, or the child would
	 * hold a second copy of a type that has to be shared across the boundary.
	 */
	private fun readChildDependencies(version: String): List<RemoteArtifact> {
		val request = Request.Builder().url(artifactUrl(version, "pom")).build()
		val text = httpClient.newCall(request).execute().use { response ->
			check(response.isSuccessful) { "HTTP " + response.code + " fetching the POM" }
			response.body.string()
		}

		val document = DocumentBuilderFactory.newInstance().apply {
			// The POM is third-party content fetched over the network. No doctypes, no external
			// entities: an XXE here would read local files on a user's machine during an update.
			setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
			setFeature("http://xml.org/sax/features/external-general-entities", false)
			setFeature("http://xml.org/sax/features/external-parameter-entities", false)
			isXIncludeAware = false
			isExpandEntityReferences = false
		}.newDocumentBuilder().parse(text.byteInputStream())

		val nodes = document.getElementsByTagName("dependency")
		return (0 until nodes.length).mapNotNull { index ->
			val element = nodes.item(index)
			fun child(tag: String): String? {
				val children = element.childNodes
				for (i in 0 until children.length) {
					val node = children.item(i)
					if (node.nodeName == tag) return node.textContent?.trim()
				}
				return null
			}
			val group = child("groupId") ?: return@mapNotNull null
			val artifact = child("artifactId") ?: return@mapNotNull null
			val dependencyVersion = child("version") ?: return@mapNotNull null
			if (SHARED_WITH_PARENT.any { group == it || group.startsWith(it + ".") }) {
				return@mapNotNull null
			}
			RemoteArtifact(group, artifact, dependencyVersion)
		}
	}

	/**
	 * Fetch [url] to [target], and where the repository publishes a checksum beside the artifact,
	 * check what arrived against it.
	 *
	 * This is the only point in the whole flow where provenance can be checked at all. The lock
	 * file records what was downloaded, which catches later corruption and tampering but would
	 * faithfully record a bad download; comparing against the repository's own published digest is
	 * what catches a truncated transfer or a mangling proxy at the moment it happens.
	 *
	 * A missing checksum is not fatal -- not every repository publishes one for every artifact --
	 * but a checksum that is present and wrong is.
	 */
	private fun download(url: String, target: File, checksumUrl: String? = null) {
		val request = Request.Builder().url(url).build()
		httpClient.newCall(request).execute().use { response ->
			check(response.isSuccessful) { "HTTP " + response.code + " for " + url }
			target.parentFile.mkdirs()
			response.body.byteStream().use { input ->
				target.outputStream().use(input::copyTo)
			}
		}

		val published = checksumUrl?.let { fetchPublishedSha1(it) } ?: return
		val actual = sha1(target)
		check(actual.equals(published, ignoreCase = true)) {
			"checksum mismatch for " + url + ": published " + published + ", got " + actual
		}
	}

	private fun fetchPublishedSha1(url: String): String? = runCatching {
		httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
			if (!response.isSuccessful) return null
			// Maven checksum files are the hex digest, sometimes followed by a filename.
			response.body.string().trim().substringBefore(' ').takeIf { it.length == 40 }
		}
	}.getOrNull()

	private fun sha1(file: File) = digest(file, "SHA-1")

	private fun digest(file: File, algorithm: String): String {
		val digest = MessageDigest.getInstance(algorithm)
		file.inputStream().use { stream ->
			val buffer = ByteArray(1 shl 16)
			while (true) {
				val read = stream.read(buffer)
				if (read <= 0) break
				digest.update(buffer, 0, read)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	private fun artifactUrl(version: String, extension: String) =
		"https://jitpack.io/com/github/" + REPO + "/" + version + "/" +
			ARTIFACT + "-" + version + "." + extension

	private data class RemoteArtifact(
		val group: String,
		val artifact: String,
		val version: String,
	) {
		val fileName get() = artifact + "-" + version + ".jar"
		val url: String
			get() = "https://repo1.maven.org/maven2/" + group.replace('.', '/') + "/" +
				artifact + "/" + version + "/" + fileName
	}

	private companion object {
		/**
		 * The organisation repository, not the personal one the Android app still pins. That
		 * coordinate 301-redirects on GitHub because the repo was transferred, so new commits
		 * should not be expected to build under it (docs/FINDINGS.md 1).
		 */
		const val REPO = "Kotatsu-Redo/kotatsu-parsers-redo"
		const val ARTIFACT = "kotatsu-parsers-redo"
		const val BRANCH = "master"
		const val SHA_LENGTH = 10

		/** Groups the parent classloader already provides. See ParsersClassLoader. */
		val SHARED_WITH_PARENT = setOf(
			"org.jetbrains.kotlin",
			"org.jetbrains.kotlinx",
			"com.squareup.okhttp3",
			"com.squareup.okio",
		)

		const val INITIAL_POLL_MILLIS = 3_000L
		const val MAX_POLL_INTERVAL_MILLIS = 30_000L
		const val MAX_POLLS = 12
	}
}
