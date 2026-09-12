package app.ageha.core.source

import app.ageha.core.model.SourceDescriptor
import app.ageha.core.model.SourceSetting
import okhttp3.OkHttpClient

/**
 * The whole conversation between Ageha and a loaded parsers build.
 *
 * ## Why this interface exists
 *
 * The parsers library is loaded in an isolated classloader so a new build can replace it without
 * restarting the app. That creates a boundary, and every type crossing it must resolve to the
 * *same* `Class` on both sides or the JVM raises `LinkageError`.
 *
 * The obvious way to arrange that is to share the parser library's own API types across the
 * boundary. It does not survive contact with this library. `MangaLoaderContext.newLinkResolver`
 * returns `LinkResolver`, which imports `AbstractMangaParser`, the base class every one of the
 * 1300+ site parsers extends -- so sharing the boundary types transitively freezes most of the
 * library at whatever version shipped with the app. Worse, sharing the *models* means any change
 * to `Manga` needs an app release, and `Manga` is mid-migration right now (docs/FINDINGS.md 5).
 * Routine source updates would stop being routine, which is the one property the whole design
 * exists to protect.
 *
 * So nothing from the parsers library crosses the boundary at all. This interface does, and every
 * type in its signatures is either a JDK type or an Ageha type. Both sides see the same `Class`
 * for those because the loader delegates them to the parent, and the entire parsers library plus
 * the code that speaks to it live in the child, free to change together.
 *
 * ## What this costs
 *
 * Exactly one reflective call, to construct the implementation. Everything afterwards is an
 * ordinary typed method call through this interface. Reflection at the seam, not throughout.
 *
 * ## Implementing it
 *
 * The implementation is `app.ageha.core.jvmcontext.RealParserBridge`, and it is loaded from the
 * child loader, never from the application classpath. Its constructor is the ABI: changing that
 * signature breaks loading in a way the compiler cannot see, so it is asserted by the
 * compatibility gate before any build is activated.
 *
 * It also happens to be the right seam if the in-process design is ever abandoned for a separate
 * parser process: a remote implementation of this interface is a drop-in for the local one.
 */
interface ParserBridge : AutoCloseable {

	/** Identifies the parsers build behind this bridge. A commit SHA -- upstream publishes no tags. */
	val parsersVersion: String

	/**
	 * The client to use for any request Ageha makes *on a source's behalf* -- cover and page
	 * images, and a downloading chapter's pages.
	 *
	 * It is the loaded build's own client, so it carries that build's parser dispatch: a request
	 * naming its source is handed to that source's parser, which is how a source that descrambles
	 * or decrypts its page images gets to do so. Fetching an image with any other client skips all
	 * of that, and the source then looks broken rather than unwired.
	 *
	 * An `OkHttpClient` crosses the boundary safely for the reason the class comment gives: the
	 * loader delegates `okhttp3.` to the parent, so both sides see one `Class`. The image *bytes*
	 * still never cross it -- the caller fetches those itself, which `BridgeSurfaceTest` asserts.
	 */
	val imageHttpClient: OkHttpClient

	/** Every source in this build, including ones upstream has flagged broken. */
	fun sourceDescriptors(): List<SourceDescriptor>

	/**
	 * A client for one source.
	 *
	 * @throws app.ageha.core.model.SourceFailure.UnknownSource if this build has no such source.
	 */
	fun clientFor(name: String): MangaSourceClient

	/**
	 * Prove this build is usable without touching the network.
	 *
	 * Constructs a sample of parsers and checks each reports a domain and at least one sort order.
	 * Runs inside the compatibility gate, against a staged build, before anything is activated --
	 * so it must never throw for an ordinary reason such as one broken source.
	 *
	 * @return a human-readable description of what failed, or null if the build looks sound.
	 */
	fun selfCheck(sampleSize: Int): String?

	/**
	 * Which source handles [url], and which manga it points at if it points at one.
	 *
	 * Null when no source in this build handles the site -- an ordinary answer, not a failure, and
	 * the one most pasted links from outside the library will get.
	 *
	 * ## Why this has a body
	 *
	 * The default is not a convenience. The bridge implementation is loaded from a jar extracted
	 * into the user's cache, and that jar can be older than the application calling it -- the
	 * defect 0.3.2's freshness check exists to repair, which does not yet reach a build downloaded
	 * by the update engine. An abstract method here would turn that staleness into
	 * `AbstractMethodError` the first time anyone pasted a link. With a body, a stale bridge
	 * inherits "no source handles this", which is wrong but survivable.
	 */
	suspend fun resolveLink(url: String): ResolvedLink? = null

	/**
	 * Resolve [url] as [sourceName] would, rather than as the library's own resolver chose.
	 *
	 * For the multi-language families. When one domain is served by a source per language, the
	 * person picks which language they want, and a link that named a manga has to be asked again as
	 * that source -- otherwise "Open manga" opens the title in the language they just declined.
	 *
	 * Null when [sourceName] cannot read the link. That is an ordinary answer, not a failure: the
	 * caller falls back to offering the site rather than the title, which is right, because opening
	 * the wrong title is worse than opening none. A body here for the staleness reason
	 * [resolveLink] gives -- an older bridge inherits "cannot", which costs the re-resolve, not a
	 * crash.
	 */
	suspend fun resolveLinkAs(url: String, sourceName: String): ResolvedLink? = null

	/**
	 * Build whatever [resolveLink] needs ahead of the first call, if anything.
	 *
	 * Finding every source that serves a domain means constructing every parser in the build, and
	 * that cost is the same whenever it is paid -- so the dialog pays it when it opens rather than
	 * when someone presses Find. Idempotent, and does nothing at all where there is nothing to
	 * build.
	 */
	fun warmLinkIndex() = Unit

	/**
	 * Every option [name]'s own parser declares, with the value currently in force.
	 *
	 * Empty for a source this build does not have -- and, because of the body, for a bridge older
	 * than this application, for the staleness reason [resolveLink] gives. An empty list reads as
	 * "this source has nothing to configure", which is wrong but harmless; an abstract member here
	 * would be `AbstractMethodError` the first time anyone opened a source's settings.
	 */
	fun sourceSettings(name: String): List<SourceSetting> = emptyList()

	/**
	 * Set one of [name]'s options, or clear it back to the parser's default with a null [value].
	 *
	 * This is what makes a dead mirror survivable: 258 sources in the bundled build declare the
	 * domains their site is reachable at, sites move between them, and until this existed the
	 * parser's default was the only one Ageha could ever use.
	 *
	 * @return whether it was applied. False for an unknown source, and false from a stale bridge,
	 *   which is the honest answer -- the caller can say the setting could not be saved rather than
	 *   reporting success and changing nothing.
	 */
	fun applySourceSetting(name: String, key: String, value: String?): Boolean = false
}
