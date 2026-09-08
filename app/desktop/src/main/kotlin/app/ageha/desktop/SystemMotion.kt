package app.ageha.desktop

import java.util.concurrent.TimeUnit

/**
 * Whether Windows has been told to stop animating things.
 *
 * ## Why this has to be asked at all
 *
 * Reduced motion is an accessibility setting, and the platform already owns the answer: Windows
 * puts it in Settings -> Accessibility -> Visual effects -> Animation effects. Compose Desktop
 * exposes no equivalent of the web's `prefers-reduced-motion` or Android's
 * `ANIMATOR_DURATION_SCALE`, and neither does the JDK -- `Toolkit.getDesktopProperty` carries font
 * smoothing, drag-full-windows and a dozen others, but not this one.
 *
 * So Ageha reads it directly, and only so that its own default can be "whatever you already
 * decided". A user who has turned animation off system-wide has said something about how they want
 * software to behave; making them say it again in every application is the failure this exists to
 * avoid. `MotionPreference.FULL` and `MotionPreference.REDUCED` override it either way.
 *
 * ## Why the registry, and why this key
 *
 * There is no supported Win32 call reachable from a plain JVM -- `SystemParametersInfo` with
 * `SPI_GETCLIENTAREAANIMATION` needs JNI or a JNA dependency, which is a native binding and a new
 * dependency for one boolean. The Windows toggle also writes `MinAnimate` under
 * `Control Panel\Desktop\WindowMetrics`, which `reg.exe` can read, and `reg.exe` ships with
 * Windows.
 *
 * `MinAnimate` is documented as the minimise and restore animation specifically, and the Animation
 * effects switch sets it along with several other flags. So this is a *proxy*, and an honest one:
 * it is correct for the switch people actually use, and its failure mode -- somebody who set
 * `MinAnimate` alone through some other tool -- lands on a setting they can override in
 * Settings -> Appearance.
 *
 * ## Every failure means motion stays on
 *
 * Missing key, unexpected output, `reg.exe` absent, a JVM started on something that is not
 * Windows, the process hanging: all of them return true. This is a *preference*, not a
 * permission -- guessing "reduce motion" wrong takes an interface away from someone who never
 * asked for that, whereas guessing "animate" wrong is a setting one click from being fixed.
 *
 * Read once per launch and cached, because it is a process spawn: re-reading it on recomposition
 * would fork `reg.exe` on every frame.
 */
object SystemMotion {

	private val cached: Boolean by lazy { probe() }

	/** True when Windows has not asked for animation to be turned down. Cached for the process. */
	fun allowsMotion(): Boolean = cached

	private fun probe(): Boolean = runCatching {
		// Not gated on `os.name`. CLAUDE.md 9 is clear that Ageha is a Windows application, and
		// the runtime OS branches that survive elsewhere in this codebase are there because the
		// JVM *can* be started anywhere. The same applies here: off Windows there is no
		// `reg.exe`, the process fails to start, and the catch below returns the right answer
		// without a special case to maintain.
		val process = ProcessBuilder("reg", "query", REGISTRY_KEY, "/v", REGISTRY_VALUE)
			.redirectErrorStream(true)
			.start()
		val output = process.inputStream.bufferedReader().use { it.readText() }
		if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			process.destroyForcibly()
			return@runCatching true
		}
		parseMinAnimate(output) ?: true
	}.getOrDefault(true)

	/**
	 * Pull the animation flag out of `reg query` output. Null means it was not in there.
	 *
	 * Split out from [probe] so the parsing can be tested without spawning a process, which is the
	 * half that can actually be wrong. Its input looks like:
	 *
	 * ```
	 * HKEY_CURRENT_USER\Control Panel\Desktop\WindowMetrics
	 *     MinAnimate    REG_SZ    0
	 * ```
	 *
	 * Split on whitespace and take the last field, rather than a fixed column: the separator is
	 * four spaces on some Windows builds and a tab on others, and matching the exact spacing is
	 * how a parser breaks on a machine nobody tested on.
	 */
	internal fun parseMinAnimate(output: String): Boolean? {
		val line = output.lineSequence()
			.firstOrNull { it.trim().startsWith(REGISTRY_VALUE) }
			?: return null
		val value = line.trim().split(Regex("\\s+")).lastOrNull() ?: return null
		return when (value) {
			"0" -> false
			"1" -> true
			// Anything else is a value this does not understand, which is not the same as a value
			// meaning "off". Reported as unknown so the caller falls back to motion on.
			else -> null
		}
	}

	private const val REGISTRY_KEY = "HKCU\\Control Panel\\Desktop\\WindowMetrics"
	private const val REGISTRY_VALUE = "MinAnimate"

	/**
	 * A ceiling on a hang, not a measurement.
	 *
	 * `reg.exe` answers in a few milliseconds. This exists so that a machine where it does not --
	 * a stalled registry, a security product interposing on process creation -- delays the window
	 * by two seconds at worst rather than never opening it.
	 */
	private const val PROBE_TIMEOUT_SECONDS = 2L
}
