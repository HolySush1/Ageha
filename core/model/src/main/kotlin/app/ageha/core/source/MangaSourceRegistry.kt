package app.ageha.core.source

import app.ageha.core.model.SourceDescriptor

/**
 * The set of manga sources the currently loaded parsers build provides.
 *
 * Everything outside :core:parsers and :core:jvmcontext sees sources only through this. There is
 * no way from here to reach a parsers-library type, which is the point (CLAUDE.md rule 5).
 *
 * Two implementations are planned. Milestone 2 binds against the compiled parsers artifact
 * directly. Milestone 3 adds one that reads a dynamically loaded JAR reflectively. The interface
 * does not change between them, and neither does any caller.
 */
interface MangaSourceRegistry {

	/** Every source in the loaded build, including ones upstream has flagged broken. */
	fun availableSources(): List<SourceDescriptor>

	/**
	 * Look a source up by its persisted name.
	 *
	 * Returns null rather than throwing when the name is not in this build. That is a normal,
	 * expected outcome: a user's library outlives any single parsers version, and sources do get
	 * renamed and removed upstream. Callers show such rows as unavailable and keep them.
	 */
	fun descriptorFor(name: String): SourceDescriptor?

	/**
	 * A client for one source.
	 *
	 * @throws app.ageha.core.model.SourceFailure.UnknownSource if [name] is not in this build.
	 */
	fun clientFor(name: String): MangaSourceClient

	/** Identifies the parsers build behind this registry, for diagnostics and the update engine. */
	val parsersVersion: String

	/**
	 * Which source handles [url], and the manga it names if it names one. Null when none does.
	 *
	 * A body here as well, so the test doubles implementing this interface need not all learn
	 * about links. The one real implementation overrides it and asks the bridge.
	 */
	suspend fun resolveLink(url: String): ResolvedLink? = null
}
