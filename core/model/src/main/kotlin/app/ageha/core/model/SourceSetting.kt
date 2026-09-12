package app.ageha.core.model

/**
 * One option a source's own parser declares, and the value currently in force.
 *
 * ## Why this exists
 *
 * Every parser declares its settings through `ConfigKey`s, and the most important of them is the
 * domain: 258 of the sources in the bundled build carry a `ConfigKey.Domain` listing the mirrors
 * that site is reachable at. Manga sites move between those constantly, and when the one a parser
 * defaults to stops answering, the source is simply dead until someone points it at another --
 * which is what the Android app's per-source settings are for.
 *
 * Ageha had the whole mechanism and no way to reach it. `SourceConfigStore` implemented the
 * parsers-side interface faithfully, was consulted on every request, and nothing ever wrote to it,
 * so every source ran on its parser's defaults for the life of the installation. This type is what
 * carries a source's options out across the classloader boundary so they can be shown and changed.
 *
 * Deliberately a flat description rather than a typed hierarchy: the set of keys is whatever the
 * loaded parsers build declares, and new ones must not need an Ageha release to become visible.
 * [kind] is a rendering hint, and an unrecognised key still arrives as [Kind.TEXT] rather than
 * being dropped.
 */
data class SourceSetting(
	/** The parser's own key for this option, as stored. Stable across builds. */
	val key: String,
	val kind: Kind,
	/** The value in force: the user's choice where they made one, the parser's default otherwise. */
	val value: String,
	/** Whether [value] is the user's choice rather than the parser's default. */
	val isOverridden: Boolean,
	/** The parser's default, so "reset" and "is this still the default" need no second lookup. */
	val defaultValue: String,
	/** The choices the parser offers, where it offers a fixed set. Empty where it does not. */
	val presets: List<Choice> = emptyList(),
) {

	/** How a setting should be presented. A hint, never a promise about the value's syntax. */
	enum class Kind {
		/** Which mirror domain to use. The one worth surfacing most. */
		DOMAIN,

		/** A source that serves its images from more than one place. */
		IMAGE_SERVER,

		/** A boolean, stored as `true` or `false`. */
		TOGGLE,

		/** Anything else, including a key this Ageha release has never heard of. */
		TEXT,
	}

	/** One of a fixed set of values, with the label the parser gave it. */
	data class Choice(val value: String, val label: String)
}
