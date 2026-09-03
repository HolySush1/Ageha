package app.ageha.core.parsers

import app.ageha.core.js.NoJsRuntime
import app.ageha.core.network.AgehaHttpClient
import app.ageha.core.network.PersistentCookieJar
import app.ageha.core.source.ParserBridge
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Two parser builds, loaded into one JVM at the same time.
 *
 * This is the test the brief asks for, and the one that says whether Layer 1 works at all. An
 * update replaces the running build without restarting Ageha, so for a moment both exist. If they
 * share a class they must not share, that moment produces a `LinkageError` -- and only ever in
 * production, because during development there is only ever one build in play.
 *
 * There are two opposite things to prove, and getting either wrong breaks the design:
 *
 *  - Types the boundary is built from must be **the same class** in both, or the parent cannot
 *    talk to either.
 *  - Everything inside a build must be **a different class** in each, or the second build silently
 *    runs the first build's code while reporting its own version.
 */
class ClassLoaderIsolationTest {

	private fun install(dir: File, name: String): Pair<ParsersInstallation, String> {
		val installation = ParsersInstallation(File(dir, name))
		return installation to BundledParsers.ensureExtracted(installation)
	}

	private val clients = mutableListOf<OkHttpClient>()

	/** One client per test, shared by every build the test loads -- as in production. */
	private fun sharedClient(dir: File): OkHttpClient =
		AgehaHttpClient.build(
			PersistentCookieJar(File(dir, "shared-cookies.json")),
			cacheDir = File(dir, "http-cache"),
		).also { clients += it }

	private fun load(
		installation: ParsersInstallation,
		version: String,
		dir: File,
		label: String,
		httpClient: OkHttpClient,
	): Pair<ParsersClassLoader, ParserBridge> {
		val loader = ParsersClassLoader.create(
			parsersJar = installation.parsersJarFor(version),
			bridgeJar = installation.bridgeJarFor(version),
			extraJars = installation.libraryJarsFor(version),
			version = label,
		)
		val bridge = ParserBridgeLoader.instantiate(
			loader = loader,
			httpClient = httpClient,
			cookieJar = PersistentCookieJar(File(dir, label + "-cookies.json")),
			jsRuntime = NoJsRuntime,
			version = label,
		)
		return loader to bridge
	}

	@AfterEach
	fun releaseClients() {
		clients.forEach { client ->
			runCatching { client.dispatcher.executorService.shutdown() }
			runCatching { client.connectionPool.evictAll() }
			runCatching { client.cache?.close() }
		}
		clients.clear()
	}

	@Test
	@DisplayName("two builds coexist, isolated from each other but agreeing on the boundary")
	fun twoBuildsCoexist(@TempDir dir: File) {
		val (firstInstall, firstVersion) = install(dir, "first")
		val (secondInstall, secondVersion) = install(dir, "second")

		val client = sharedClient(dir)
		val (loaderA, bridgeA) = load(firstInstall, firstVersion, dir, "build-a", client)
		val (loaderB, bridgeB) = load(secondInstall, secondVersion, dir, "build-b", client)

		try {
			// --- isolation -------------------------------------------------------------------
			assertNotSame(loaderA, loaderB)
			assertNotSame(
				bridgeA.javaClass,
				bridgeB.javaClass,
				"each build must get its own RealParserBridge; sharing one means the second " +
					"build is running the first build's code",
			)
			assertSame(loaderA, bridgeA.javaClass.classLoader)
			assertSame(loaderB, bridgeB.javaClass.classLoader)

			val enumA = Class.forName("org.koitharu.kotatsu.parsers.model.MangaParserSource", false, loaderA)
			val enumB = Class.forName("org.koitharu.kotatsu.parsers.model.MangaParserSource", false, loaderB)
			assertNotSame(
				enumA,
				enumB,
				"the generated source enum must be per-build; it is KSP-generated and its " +
					"constants differ between builds",
			)

			// --- and the shared boundary ------------------------------------------------------
			// The counterpart to isolation. If ParserBridge resolved twice, the cast inside
			// ParserBridgeLoader would already have failed, but assert it directly so a future
			// change to the delegation policy fails here with a readable message.
			assertSame(
				ParserBridge::class.java,
				Class.forName(ParserBridge::class.java.name, false, loaderA),
				"ParserBridge must resolve to the parent's copy, or the boundary has no shared type",
			)
			assertSame(
				Class.forName(ParserBridge::class.java.name, false, loaderA),
				Class.forName(ParserBridge::class.java.name, false, loaderB),
			)

			// --- both actually work -----------------------------------------------------------
			val sourcesA = bridgeA.sourceDescriptors()
			val sourcesB = bridgeB.sourceDescriptors()
			assertTrue(sourcesA.size > 1000)
			assertEquals(sourcesA.size, sourcesB.size)
			assertEquals("build-a", bridgeA.parsersVersion)
			assertEquals("build-b", bridgeB.parsersVersion)
		} finally {
			runCatching { bridgeA.close() }
			runCatching { bridgeB.close() }
			loaderA.close()
			loaderB.close()
		}
	}

	@Test
	@DisplayName("the parsers library is not reachable from the application classloader")
	fun parsersAreNotOnTheAppClasspath() {
		// The whole isolation story rests on this. If the parsers library were also on the
		// application classpath, the child loaders would shadow it and the two copies would
		// diverge invisibly -- so assert the absence rather than trusting the build file.
		listOf(
			"org.koitharu.kotatsu.parsers.MangaLoaderContext",
			"org.koitharu.kotatsu.parsers.model.MangaParserSource",
			"app.ageha.core.jvmcontext.RealParserBridge",
		).forEach { name ->
			assertFalse(
				runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess,
				"$name must not be reachable from the application classloader",
			)
		}
	}

	@Test
	@DisplayName("closing a loader releases the jars, so an update can replace them")
	fun closingReleasesJarFiles(@TempDir dir: File) {
		val (installation, version) = install(dir, "closeable")
		val (loader, bridge) = load(installation, version, dir, "closeable", sharedClient(dir))
		assertTrue(bridge.sourceDescriptors().isNotEmpty())

		bridge.close()
		loader.close()

		// Windows will not delete a file that is still open. An update that cannot replace the
		// jar it is updating is not an update, so this is load-bearing rather than tidiness.
		assertTrue(
			installation.parsersJarFor(version).delete(),
			"the parsers jar is still held open after closing the loader",
		)
	}
}
