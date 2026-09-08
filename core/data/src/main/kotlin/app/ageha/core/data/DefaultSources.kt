package app.ageha.core.data

import app.ageha.core.model.SourceDescriptor

/**
 * Which sources a fresh installation starts with.
 *
 * Ageha ships around 1360 sources and used to start with every one of them off, on the principle
 * that the app should contact no site the user did not choose. That principle held and the first
 * run was still bad: the Explore screen opens on *enabled* sources, so a new user met an empty
 * list, and the shortest route to reading anything was to guess the name of a site they might not
 * know. A default set is the smaller cost -- it is visible on the screen that lists it, every
 * entry has a switch, and nothing is contacted until a source is actually opened.
 *
 * ## The rule, and why it is a rule
 *
 * English or multi-language, not adult, and not flagged broken upstream.
 *
 * Deliberately computed from the catalogue rather than kept as a list of names. A baked list of
 * 210 constants would be a second table describing the parsers library, and it would go stale in
 * both directions the first time that library moved: sources renamed upstream would silently drop
 * out of the defaults, and sources added upstream would never appear in them. Every other place in
 * this codebase that could have kept such a table -- `AgehaContentType.isAdult`, the source name
 * as the only persisted identity -- deliberately does not, for the same reason.
 *
 * The three clauses:
 *
 *  - **Language.** [SourceDescriptor.locale] is null for a source that serves many languages,
 *    which includes MangaDex and Comick. Those belong in an English default set as much as any
 *    `en` source does; excluding them would leave out the two largest catalogues Ageha can read.
 *  - **Adult.** [SourceDescriptor.isAdult] is upstream's own flag, `contentType == HENTAI`.
 *    Nothing adult is ever enabled by default, whatever the user later turns on themselves.
 *  - **Broken.** Upstream marks sources it knows are failing. About 107 of the sources that
 *    otherwise qualify carry that flag, and enabling them by default would mean a first run whose
 *    searches half fail for reasons the user cannot see. They stay in the catalogue and stay
 *    findable; they are just not switched on for someone who has not asked.
 */
object DefaultSources {

	/** The language tag treated as English. Upstream's tags are BCP-47-ish, so this is exact. */
	const val ENGLISH = "en"

	/**
	 * The default set, drawn from [catalogue].
	 *
	 * Order follows the catalogue, so the sort keys written for a fresh install are stable between
	 * two installs of the same parsers build.
	 */
	fun from(catalogue: List<SourceDescriptor>): List<SourceDescriptor> = catalogue.filter {
		(it.locale == ENGLISH || it.locale == null) && !it.isAdult && !it.isBroken
	}
}
