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

	/** Config and small state. Backed up by the OS on macOS and Windows. */
	val dataDir: File by lazy {
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
