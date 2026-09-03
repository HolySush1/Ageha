package app.ageha.core.parsers

import app.ageha.core.js.JsRuntime
import app.ageha.core.js.NoJsRuntime
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.network.AgehaHttpClient
import app.ageha.core.network.AgehaPaths
import app.ageha.core.network.PersistentCookieJar
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.source.MangaSourceRegistry
import app.ageha.core.source.ParserBridge
import okhttp3.OkHttpClient
import java.io.File

/**
 * Builds a working source stack.
 *
 * Every build, bundled or downloaded, is loaded through [ParsersClassLoader] and reached through
 * [ParserBridge]. There is no separate "static" path: the bundled build is simply the one that is
 * already on disk, so the code that runs at every launch is the same code that runs after an
 * update. A path only exercised during an upgrade is a path that is broken during an upgrade.
 */
object Ageha {

	/** @see BundledParsers.VERSION */
	const val BUNDLED_PARSERS_VERSION = BundledParsers.VERSION

	fun createSourceStack(
		jsRuntime: JsRuntime = NoJsRuntime,
		cookieFile: File = AgehaPaths.cookieFile,
		parsersDir: File = AgehaPaths.parsersDir,
	): SourceStack {
		val installation = ParsersInstallation(parsersDir)
		val bundled = BundledParsers.ensureExtracted(installation)
		val state = installation.read()

		// Prefer what the user pinned, then what is active, then the bundled build. Each step
		// falls through if the files are not actually there, so a half-deleted directory degrades
		// to the bundled build rather than to a crash on launch.
		// Prefer what the user pinned, then what is active, then the bundled build. A build only
		// counts if it is installed *and* verifies against its lock: an unverified build is not
		// loaded at all, it is stepped over. Falling back costs the user some source coverage;
		// loading unvouched-for code costs them more.
		val version = listOfNotNull(state.pinnedVersion, state.activeVersion)
			.firstOrNull { installation.isInstalled(it) && installation.verify(it) is LockVerification.Verified }
			?: bundled

		val cookieJar = PersistentCookieJar(cookieFile)
		// One client, owned here, shared by every build that gets loaded. See the comment on
		// AgehaMangaLoaderContext.baseHttpClient for why this cannot live on the child side.
		val httpClient = AgehaHttpClient.build(cookieJar)
		val loader = ParsersClassLoader.create(
			parsersJar = installation.parsersJarFor(version),
			bridgeJar = installation.bridgeJarFor(version),
			extraJars = installation.libraryJarsFor(version),
			version = version,
		)
		val bridge = ParserBridgeLoader.instantiate(loader, httpClient, cookieJar, jsRuntime, version)

		return SourceStack(
			registry = BridgedSourceRegistry(bridge),
			installation = installation,
			httpClient = httpClient,
			bridge = bridge,
			loader = loader,
			cookieJar = cookieJar,
			jsRuntime = jsRuntime,
		)
	}
}

/** Adapts a [ParserBridge] to the registry the rest of the app uses. */
private class BridgedSourceRegistry(
	private val bridge: ParserBridge,
) : MangaSourceRegistry {

	private val byName: Map<String, SourceDescriptor> by lazy {
		bridge.sourceDescriptors().associateBy { it.name }
	}

	override fun availableSources(): List<SourceDescriptor> = bridge.sourceDescriptors()

	override fun descriptorFor(name: String): SourceDescriptor? = byName[name]

	override fun clientFor(name: String): MangaSourceClient = bridge.clientFor(name)

	override val parsersVersion: String get() = bridge.parsersVersion
}

/** A constructed source stack, and the handles a host needs to shut it down cleanly. */
class SourceStack internal constructor(
	val registry: MangaSourceRegistry,
	val installation: ParsersInstallation,
	/** Shared with every loaded build, and with the update service. Closed here and nowhere else. */
	val httpClient: OkHttpClient,
	private val bridge: ParserBridge,
	private val loader: ParsersClassLoader,
	private val cookieJar: PersistentCookieJar,
	private val jsRuntime: JsRuntime,
) {

	/** The parsers build currently serving sources. */
	val parsersVersion: String get() = bridge.parsersVersion

	suspend fun close() {
		cookieJar.persist()
		jsRuntime.close()
		runCatching { bridge.close() }
		// The single owner releases the shared HTTP resources. OkHttp keeps a dispatcher thread
		// pool, live sockets and an open cache journal, none of which is reclaimed by dropping the
		// reference -- and on Windows the open journal stops an update replacing what it updates.
		runCatching { httpClient.dispatcher.executorService.shutdown() }
		runCatching { httpClient.connectionPool.evictAll() }
		runCatching { httpClient.cache?.close() }
		// Releases the jar file handles, for the same Windows reason.
		runCatching { loader.close() }
	}
}
