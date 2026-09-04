package app.ageha.core.network

import java.io.File

/**
 * Where Ageha keeps its data on each platform.
 *
 * Not `~/.ageha` everywhere: putting a dotfile in the home directory is a Unix convention and is
 * wrong on Windows and macOS, and it matters here because the parser JAR cache and the HTTP cache
 * can both reach hundreds of megabytes. Users should find them where their OS says they live.
 */
object AgehaPaths {

	private const val APP_NAME = "Ageha"

	/**
	 * An explicit override for both directories, from the environment.
	 *
	 * This exists so that anything which is not the installed app -- the end-to-end driver, the
	 * shell and gallery renderers, the webtoon profiler, a second copy run for debugging -- can be
	 * pointed at a scratch profile instead of the one the user actually reads their manga in.
	 *
	 * It was added after a render tool wrote its throwaway sample CBZ into a real reading history.
	 * Nothing was lost, but a tool that quietly edits the user's library while "just rendering a
	 * screenshot" is a tool that will eventually lose something, and the fix is one environment
	 * variable rather than a convention nobody remembers.
	 *
	 * Both spellings are read: `AGEHA_DATA_DIR` from the environment, and `ageha.data.dir` as a
	 * system property. They mean the same thing; the property exists because a JVM can set one on
	 * itself and cannot set an environment variable on itself, which is what the end-to-end test
	 * needs.
	 *
	 * Read once, through `by lazy`. A process therefore has exactly one profile for its whole
	 * lifetime, and changing the property halfway through will not move it -- which is the right
	 * behaviour, since half the app would still be holding files open in the old one.
	 */
	private fun override(name: String): File? {
		// The system property first, because a test JVM can set one and cannot set an environment
		// variable for itself. The environment variable is what a person launching a second copy
		// from a shell will reach for.
		val property = System.getProperty(name.lowercase().replace('_', '.'))
		return (property ?: System.getenv(name))?.takeIf { it.isNotBlank() }?.let(::File)
	}

	/** Config and small state. Backed up by the OS on macOS and Windows. */
	val dataDir: File by lazy {
		override("AGEHA_DATA_DIR")?.let { return@lazy it }
		val os = System.getProperty("os.name").orEmpty().lowercase()
		val home = File(System.getProperty("user.home"))
		when {
			os.contains("win") -> File(
				System.getenv("LOCALAPPDATA") ?: File(home, "AppData/Local").path,
				APP_NAME,
			)

			os.contains("mac") -> File(home, "Library/Application Support/$APP_NAME")

			else -> File(
				System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path,
				"ageha",
			)
		}
	}

	/** Regenerable bulk data: HTTP responses, page images, downloaded parser JARs. */
	val cacheDir: File by lazy {
		override("AGEHA_CACHE_DIR")?.let { return@lazy it }
		// A data-dir override with no cache override keeps the two together, which is what someone
		// pointing Ageha at a scratch profile means. Splitting them would leave the scratch run
		// sharing -- and evicting from -- the real installation's image and parser caches.
		override("AGEHA_DATA_DIR")?.let { return@lazy File(it, "cache") }
		val os = System.getProperty("os.name").orEmpty().lowercase()
		val home = File(System.getProperty("user.home"))
		when {
			os.contains("win") -> File(dataDir, "cache")
			os.contains("mac") -> File(home, "Library/Caches/$APP_NAME")
			else -> File(
				System.getenv("XDG_CACHE_HOME") ?: File(home, ".cache").path,
				"ageha",
			)
		}
	}

	val cookieFile: File get() = File(dataDir, "cookies.json").also { it.parentFile.mkdirs() }

	val httpCacheDir: File get() = File(cacheDir, "http").also { it.mkdirs() }

	val imageCacheDir: File get() = File(cacheDir, "images").also { it.mkdirs() }

	/** Downloaded parser JARs, one directory per commit SHA. Milestone 3. */
	val parsersDir: File get() = File(cacheDir, "parsers").also { it.mkdirs() }
}
