package app.ageha.core.parsers

import app.ageha.core.js.JsRuntime
import app.ageha.core.js.NoJsRuntime
import app.ageha.core.network.PersistentCookieJar
import app.ageha.core.source.ParserBridge
import okhttp3.OkHttpClient
import java.io.File

/**
 * Decides whether a parsers build is safe to activate, before anything depends on it.
 *
 * ## Why a gate is necessary at all
 *
 * Layer 1 cannot promise that source updates never require an app release, and the design should
 * not pretend otherwise. The proof is already in the history: `evaluateJs` gained a third
 * parameter between the build `kotatsu-dl` targets and the one Ageha ships. That is a breaking
 * change to `MangaLoaderContext`, the interface Ageha *implements*, and no amount of tolerance on
 * the calling side absorbs a change on the implementing side.
 *
 * The honest promise is narrower and still worth having: **routine source updates -- new sites,
 * fixed selectors, changed domains -- never require an app release.** Occasionally the host
 * contract moves, and then one is genuinely needed.
 *
 * Because that will happen, rejection is a designed outcome rather than an error path. The
 * running build stays active, the user keeps the source coverage they already had, and they are
 * told once, plainly, what happened. See [GateVerdict] and `ParsersInstallation`.
 */
object CompatibilityGate {

	/** How many parsers `selfCheck` constructs. Enough to be meaningful, quick enough to run at launch. */
	const val SELF_CHECK_SAMPLE = 25

	/**
	 * Load [parsersJar] in a throwaway classloader and decide whether it can be activated.
	 *
	 * Touches no network and mutates no state. The loader and everything in it are discarded
	 * before returning, whatever the verdict.
	 */
	fun evaluate(
		parsersJar: File,
		bridgeJar: File,
		extraJars: List<File> = emptyList(),
		version: String,
		httpClient: OkHttpClient,
		cookieJar: PersistentCookieJar,
		jsRuntime: JsRuntime = NoJsRuntime,
	): GateVerdict {
		// The candidate build gets a cache-less view of the shared client. It shares the
		// connection pool and dispatcher, which is what we want, but a build that is about to be
		// discarded must not write into the cache the live build is reading from.
		val sandboxed = httpClient.newBuilder().cache(null).build()
		var loader: ParsersClassLoader? = null
		var bridge: ParserBridge? = null
		return try {
			loader = ParsersClassLoader.create(
				parsersJar = parsersJar,
				bridgeJar = bridgeJar,
				extraJars = extraJars,
				version = version,
			)

			// 1. The bridge class exists, with the constructor signature the loader calls.
			//    Nothing checks this at build time, so it is checked here.
			bridge = ParserBridgeLoader.instantiate(loader, sandboxed, cookieJar, jsRuntime, version)

			// 2. The build reports sources at all. An empty enum means KSP output never made it
			//    into the jar -- a build that succeeded and produced nothing usable.
			val descriptors = bridge.sourceDescriptors()
			if (descriptors.isEmpty()) {
				return GateVerdict.Rejected(
					version,
					"this build reports no sources at all",
				)
			}

			// 3. Parsers actually construct and answer. This is the check that catches a changed
			//    signature: the bridge links against the jar it was loaded with, so a method that
			//    moved surfaces here as NoSuchMethodError rather than in front of a user.
			bridge.selfCheck(SELF_CHECK_SAMPLE)?.let {
				return GateVerdict.Rejected(version, it)
			}

			GateVerdict.Accepted(version, descriptors.size)
		} catch (e: LinkageError) {
			// The signal this gate exists for: the build and Ageha's own child-side code no longer
			// agree on a type or a signature.
			GateVerdict.Rejected(version, "incompatible with this version of Ageha: " + describe(e))
		} catch (e: ReflectiveOperationException) {
			GateVerdict.Rejected(version, "does not expose the interface Ageha expects: " + describe(e))
		} catch (e: Exception) {
			GateVerdict.Rejected(version, "could not be loaded: " + describe(e))
		} finally {
			runCatching { bridge?.close() }
			runCatching { loader?.close() }
		}
	}

	private fun describe(error: Throwable): String =
		error.javaClass.simpleName + (error.message?.let { ": " + it } ?: "")
}

/** The outcome of a gate run. */
sealed interface GateVerdict {

	val version: String

	data class Accepted(override val version: String, val sourceCount: Int) : GateVerdict

	/**
	 * The build was not activated and the previous one is still running.
	 *
	 * [reason] is written to be shown to a person, not only logged. The user needs to understand
	 * that their sources still work and that the fix is an Ageha update, not anything they did.
	 */
	data class Rejected(override val version: String, val reason: String) : GateVerdict {

		/** The one-line message for the UI. */
		fun userMessage(): String =
			"Parsers $version needs a newer version of Ageha, so it was not installed. " +
				"Your sources are still working on the version you have."
	}
}
