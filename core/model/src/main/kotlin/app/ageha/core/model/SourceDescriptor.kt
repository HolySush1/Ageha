package app.ageha.core.model

/**
 * A manga source, as Ageha sees it.
 *
 * [name] is the parsers library's enum constant name, and it is the *only* identity we persist.
 * The reason is in docs/FINDINGS.md 5: `MangaParserSource` does not exist in the parsers source
 * tree at all -- it is generated at build time by KSP from `@MangaSourceParser` annotations, so
 * its constants differ between every JAR build. Compiling against a named constant would turn any
 * upstream rename into a `NoSuchFieldError`, and persisting an ordinal would silently repoint a
 * user's favourites at a different site.
 *
 * So: sources are addressed by string, everywhere, forever. A name we no longer recognise
 * resolves to null and renders as "unavailable in this parser version" -- never an exception,
 * never a lost favourite.
 */
data class SourceDescriptor(
	val name: String,
	val title: String,
	/** BCP-47-ish language tag from upstream, or null for multi-language sources. */
	val locale: String?,
	val contentType: AgehaContentType,
	/**
	 * Upstream's own "this source is currently broken" flag, carried on the generated enum.
	 * Surface it in the UI rather than letting the user discover it by failure.
	 */
	val isBroken: Boolean,
) {
	/**
	 * Whether this is an adult source, by upstream's own definition. See [AgehaContentType.isAdult].
	 *
	 * Derived rather than stored: it is a reading of [contentType], and a second persisted field
	 * saying the same thing is a second field that can disagree with the first after a parsers
	 * bump.
	 */
	val isAdult: Boolean get() = contentType.isAdult
}
