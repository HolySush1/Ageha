package app.ageha.core.source

import app.ageha.core.model.AgehaManga

/**
 * What a pasted link turned out to point at.
 *
 * This is the answer to "can I read this site in Ageha", and it is deliberately narrow. A link is
 * only ever matched against sources the loaded parsers build already has -- Ageha does not learn
 * new sites from a URL, because a parser that could read an arbitrary site does not exist and
 * CLAUDE.md rule 2 forbids writing one here. What a link *can* do is find the source that already
 * handles its site, which with around 1,360 of them, several mirrors each, is otherwise a matter
 * of guessing at names in a list.
 *
 * An Ageha type rather than the parsers library's `LinkResolver`, for the reason [ParserBridge]
 * gives at length: nothing from the library may cross the classloader boundary.
 */
data class ResolvedLink(
	/**
	 * The source that handles the link's site, by its persisted name.
	 *
	 * Never null. A link no source handles produces no `ResolvedLink` at all, so a caller holding
	 * one always has somewhere to go.
	 */
	val sourceName: String,
	/**
	 * The title the link points at, when it points at one.
	 *
	 * Null for a site's front page, a search page, a genre listing -- anything that names the site
	 * without naming a manga. Those still resolve, to the source alone.
	 */
	val manga: AgehaManga?,
)

/**
 * Turning what a person pasted into something worth handing to a resolver.
 *
 * Kept apart from resolution, and pure, because the two failures mean different things to the
 * person who pasted: "that is not a link" is a typo to fix, while "no source handles that site"
 * is a fact about the library. The dialog says different things for each, so it has to be able
 * to tell them apart before any network is involved.
 */
object SiteLinks {

	/**
	 * A resolvable `http(s)` URL for [input], or null when it is not a link at all.
	 *
	 * Forgiving about the scheme -- people copy `comix.to` out of an address bar as often as
	 * `https://comix.to/`, and making them retype it would be pedantry -- and strict about
	 * everything else: no whitespace, a host with a dot in it, and no scheme but http or https.
	 * A `file:` or `javascript:` link is not a manga site however it is spelled.
	 */
	fun normalise(input: String): String? {
		val text = input.trim()
		if (text.isEmpty() || text.any(Char::isWhitespace)) return null
		val lower = text.lowercase()
		val withScheme = when {
			lower.startsWith("https://") || lower.startsWith("http://") -> text
			// Any other scheme is refused rather than having https bolted onto it.
			SCHEME.containsMatchIn(lower) -> return null
			else -> "https://$text"
		}
		val host = runCatching { java.net.URI(withScheme).host }.getOrNull() ?: return null
		return withScheme.takeIf { '.' in host }
	}

	/** `scheme:` at the start, per RFC 3986 -- but not `host:port`, which has digits after it. */
	private val SCHEME = Regex("""^[a-z][a-z0-9+.-]*:(?!\d)""")

	/** The bare host of an already-normalised link, for "no source handles comix.to" copy. */
	fun hostOf(link: String): String =
		runCatching { java.net.URI(link).host }.getOrNull()?.removePrefix("www.") ?: link
}
