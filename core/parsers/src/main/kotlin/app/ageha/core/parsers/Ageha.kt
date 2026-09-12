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
import app.ageha.core.source.ResolvedLink
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
		val candidate = listOfNotNull(state.pinnedVersion, state.activeVersion)
			.firstOrNull { installation.isInstalled(it) && installation.verify(it) is LockVerification.Verified }
			?: bundled

		// Built before the version is settled rather than after, because settling it can now mean
		// running the compatibility gate, and the gate needs a client to build its sandbox from.
		val cookieJar = PersistentCookieJar(cookieFile)
		// One client, owned here, shared by every build that gets loaded. See the comment on
		// AgehaMangaLoaderContext.baseHttpClient for why this cannot live on the child side.
		val httpClient = AgehaHttpClient.build(cookieJar)

		val version = if (candidate == bundled) {
			bundled
		} else {
			withCurrentBridge(installation, candidate, bundled, httpClient, cookieJar)
		}
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
			// Read defensively, falling back to the base client rather than refusing to launch.
			// `imageHttpClient` is an abstract member, so a bridge jar older than this application
			// would raise AbstractMethodError right here -- and while withCurrentBridge below is
			// meant to make that impossible, "images lose their parser interception" is a far
			// better outcome for someone starting the app than "the app does not start". The
			// fallback is exactly what every release before this one did.
			//
			// runCatching, and this is the one place its catching of Error is the point.
			imageHttpClient = runCatching { bridge.imageHttpClient }.getOrDefault(httpClient),
			bridge = bridge,
			loader = loader,
			cookieJar = cookieJar,
			jsRuntime = jsRuntime,
		)
	}
}

/** Adapts a [ParserBridge] to the registry the rest of the app uses. */
/**
 * A downloaded build, carrying this release's bridge -- or the bundled build, if it cannot.
 *
 * ## The gap this closes
 *
 * A downloaded build's directory holds its own *copy* of the bridge, taken from the app at the
 * moment the build was downloaded. The bridge is Ageha's code, not the parsers project's, so that
 * copy goes stale the first time Ageha changes it -- and it is loaded anyway, because its lock
 * still describes it perfectly. 0.3.2 fixed the bundled build's copy and missed this one: anyone
 * who had accepted a parser update kept loading the previous release's bridge, whose calls into
 * the parent-first `JsRuntime` no longer matched and failed as `NoSuchMethodError` inside every
 * browser-tier source. A bridge method added since would fail quieter still -- `resolveLink` has a
 * default, so a stale bridge would answer "no source reads this site" for sites it does have.
 *
 * ## Why the gate runs again first
 *
 * The build was vetted against the bridge it shipped with, not this one, and a newer bridge may
 * reach parts of the parsers API an older build lacks. So the build is re-gated with the bridge
 * it is about to be given; the gate is offline and sized to run at launch. Accepted, the copy is
 * replaced and relocked. Refused, the build is stepped over for the bundled one -- the same
 * trade the lock check already makes: some source coverage lost until the update engine finds a
 * build that fits, rather than code loaded that nothing has vouched for.
 */
private fun withCurrentBridge(
	installation: ParsersInstallation,
	version: String,
	bundled: String,
	httpClient: okhttp3.OkHttpClient,
	cookieJar: PersistentCookieJar,
): String {
	val shipped = installation.bridgeJarFor(bundled)
	val carried = installation.bridgeJarFor(version)
	val matches = runCatching { ParsersLock.sha256(shipped) == ParsersLock.sha256(carried) }.getOrDefault(false)
	if (matches) return version

	// Any failure to reach a verdict is a refusal. This runs on every launch, and a corrupt build
	// that made the gate throw here would otherwise turn a bad download into an app that cannot
	// start -- a strictly worse outcome than the stale bridge this function exists to replace.
	val verdict = runCatching {
		CompatibilityGate.evaluate(
			parsersJar = installation.parsersJarFor(version),
			bridgeJar = shipped,
			extraJars = installation.libraryJarsFor(version),
			version = version,
			httpClient = httpClient,
			cookieJar = cookieJar,
		)
	}.getOrNull()
	if (verdict !is GateVerdict.Accepted) return bundled
	return runCatching {
		shipped.copyTo(carried, overwrite = true)
		installation.writeLock(version)
		version
	}
		// A copy that fails halfway leaves a jar that no longer matches its lock, which the next
		// launch would step over anyway. Falling back now just gets there one launch sooner.
		.getOrDefault(bundled)
}

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

	override suspend fun resolveLink(url: String): ResolvedLink? = bridge.resolveLink(url)

	override suspend fun resolveLinkAs(url: String, sourceName: String): ResolvedLink? =
		bridge.resolveLinkAs(url, sourceName)

	override fun warmLinkIndex() = bridge.warmLinkIndex()

	override fun sourceSettings(name: String) = bridge.sourceSettings(name)

	override fun applySourceSetting(name: String, key: String, value: String?) =
		bridge.applySourceSetting(name, key, value)
}

/** A constructed source stack, and the handles a host needs to shut it down cleanly. */
class SourceStack internal constructor(
	val registry: MangaSourceRegistry,
	val installation: ParsersInstallation,
	/** Shared with every loaded build, and with the update service. Closed here and nowhere else. */
	val httpClient: OkHttpClient,
	/**
	 * The client for requests Ageha makes *on a source's behalf*: cover and page images, and a
	 * downloading chapter's pages.
	 *
	 * Use this and not [httpClient] for anything fetched from a source. It is the loaded build's
	 * own client, so it carries that build's parser dispatch and its Cloudflare clearance, and
	 * that is what lets a source descramble or decrypt its page images -- see
	 * `ParserBridge.imageHttpClient`. [httpClient] is right for everything that is not source
	 * traffic: the update service, sync, the app update check.
	 *
	 * Derived from [httpClient], so it shares the cache, the connection pool and the dispatcher.
	 * Closing [httpClient] releases both; there is nothing separate to shut down here.
	 */
	val imageHttpClient: OkHttpClient,
	private val bridge: ParserBridge,
	private val loader: ParsersClassLoader,
	/**
	 * Shared with the update service, which needs it to fetch candidate builds through the same
	 * session the app already has. Exposed rather than duplicated: a second cookie jar would mean
	 * JitPack and the sources disagreeing about who Ageha is.
	 */
	val cookieJar: PersistentCookieJar,
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
