package app.ageha.core.parsers

import java.io.File
import java.net.URL
import java.net.URLClassLoader

/**
 * Loads one parsers build in isolation, so a newer one can replace it without restarting Ageha.
 *
 * ## The delegation policy, and why it is this way round
 *
 * Read this before changing anything here. Classloader mistakes do not fail at compile time and
 * usually do not fail at load time either; they surface later as `LinkageError`,
 * `ClassCastException` or `NoSuchMethodError`, and only once a *second* build is in play, which
 * is to say never during ordinary development.
 *
 * The rule is short:
 *
 * - **Parent-first** for the JDK, the Kotlin runtime, the HTTP and parsing libraries, and Ageha's
 *   own shared modules ([PARENT_FIRST_PREFIXES]). These types cross the boundary, so both sides
 *   must resolve them to the same `Class`.
 * - **Child-first** for everything else -- the entire parsers library, and Ageha's own code that
 *   speaks to it. Those are loaded together, from this loader, and are free to change together.
 *
 * The design that was tried first, and abandoned, was the opposite: share the parsers library's
 * API types and load only the site parsers in the child. Two things killed it.
 * `MangaLoaderContext.newLinkResolver` returns `LinkResolver`, which imports
 * `AbstractMangaParser` -- the base class all 1300+ site parsers extend -- so sharing the boundary
 * types transitively freezes most of the library at the bundled version. And sharing the models
 * means any change to `Manga` requires an app release, which is precisely the property Layer 1
 * exists to avoid, with `Manga` mid-migration upstream right now.
 *
 * The cost of the current policy is that the parent cannot hold a parsers type at all. That is
 * what `app.ageha.core.source.ParserBridge` is for.
 *
 * ## Ageha's own split
 *
 * `app.ageha.core.jvmcontext` is deliberately **absent** from the parent-first list. It implements
 * `MangaLoaderContext`, so it must live wherever the parsers library lives. Its jar is handed to
 * this loader alongside the parsers jar, and the copy on the application classpath, if any, is
 * shadowed. Adding that package to the parent-first list is the single most likely way to break
 * this file: it compiles, it loads, and then it raises `LinkageError` on first use.
 */
class ParsersClassLoader private constructor(
	urls: Array<URL>,
	parent: ClassLoader,
	private val version: String,
) : URLClassLoader("ageha-parsers-$version", urls, parent) {

	override fun loadClass(name: String, resolve: Boolean): Class<*> {
		synchronized(getClassLoadingLock(name)) {
			findLoadedClass(name)?.let {
				if (resolve) resolveClass(it)
				return it
			}

			if (isParentFirst(name)) {
				return super.loadClass(name, resolve)
			}

			// Child-first: our own copy if we have one, otherwise fall back to the parent so that
			// anything we did not anticipate still resolves rather than failing outright.
			return try {
				findClass(name).also { if (resolve) resolveClass(it) }
			} catch (e: ClassNotFoundException) {
				super.loadClass(name, resolve)
			}
		}
	}

	override fun getResource(name: String): URL? =
		findResource(name) ?: super.getResource(name)

	override fun toString(): String = "ParsersClassLoader($version)"

	companion object {

		/**
		 * Packages that must resolve to the same `Class` on both sides of the boundary.
		 *
		 * Every entry is here because a type from it appears in a signature that crosses:
		 * the JDK and Kotlin runtimes underpin everything; OkHttp and Okio because
		 * `MangaLoaderContext` exposes an `OkHttpClient` and a `CookieJar` that Ageha builds; and
		 * Ageha's own shared modules because `ParserBridge` traffics in them.
		 *
		 * jsoup is deliberately *not* here, though an earlier draft had it. Nothing jsoup-shaped
		 * crosses the boundary any more -- failures are classified on the child side and leave as
		 * `SourceFailure` -- so jsoup belongs in the child, versioned with the parsers build that
		 * expects it. The same reasoning covers org.json and androidx.collection.
		 *
		 * `app.ageha.core.jvmcontext` is **not** here, and must not be. See the class comment.
		 */
		val PARENT_FIRST_PREFIXES: List<String> = listOf(
			"java.",
			"javax.",
			"jdk.",
			"sun.",
			"kotlin.",
			"kotlinx.coroutines.",
			"okhttp3.",
			"okio.",
			"app.ageha.core.model.",
			"app.ageha.core.source.",
			"app.ageha.core.js.",
			"app.ageha.core.network.",
			"app.ageha.core.parsers.",
		)

		fun isParentFirst(className: String): Boolean =
			PARENT_FIRST_PREFIXES.any { className.startsWith(it) }

		/**
		 * @param parsersJar the parsers library build to load.
		 * @param bridgeJar Ageha's own child-side code, which must be loaded with it.
		 * @param extraJars transitive dependencies not already shared with the parent.
		 */
		fun create(
			parsersJar: File,
			bridgeJar: File,
			extraJars: List<File> = emptyList(),
			version: String,
			parent: ClassLoader = ParsersClassLoader::class.java.classLoader,
		): ParsersClassLoader {
			require(parsersJar.isFile) { "Parsers jar not found: $parsersJar" }
			require(bridgeJar.exists()) { "Bridge jar not found: $bridgeJar" }
			val urls = (listOf(bridgeJar, parsersJar) + extraJars)
				.map { it.toURI().toURL() }
				.toTypedArray()
			return ParsersClassLoader(urls, parent, version)
		}
	}
}
