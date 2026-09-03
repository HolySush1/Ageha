package app.ageha.core.source

import app.ageha.core.model.SourceDescriptor

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
}
