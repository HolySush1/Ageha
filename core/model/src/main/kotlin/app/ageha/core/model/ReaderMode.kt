package app.ageha.core.model

/**
 * How pages are laid out in the reader.
 *
 * The [id] values are the Android app's, and they are **not** ordinals: `STANDARD` is 1,
 * `WEBTOON` is 2, `REVERSED` is 3, `VERTICAL` is 4. Upstream declares them in a different order
 * from their ids, so persisting an ordinal here would give a user a different reader mode after a
 * backup round trip -- silently, and only for manga they had customised.
 */
enum class ReaderMode(val id: Int) {

	/** Paged, left to right. Western reading order and most webtoon-origin releases. */
	STANDARD(1),

	/** Continuous vertical strip with no page gaps. Korean and Chinese webtoons. */
	WEBTOON(2),

	/** Paged, right to left. Japanese reading order -- what most scanlated manga expects. */
	REVERSED(3),

	/** Paged, top to bottom. One page at a time, advanced vertically. */
	VERTICAL(4),
	;

	/** True when pages are discrete rather than a continuous strip. */
	val isPaged: Boolean get() = this != WEBTOON

	/** True when the next page is to the *left*, which flips every arrow key and swipe. */
	val isRightToLeft: Boolean get() = this == REVERSED

	companion object {
		fun fromId(id: Int): ReaderMode? = entries.firstOrNull { it.id == id }

		/**
		 * What to use when the user has expressed no preference.
		 *
		 * Right-to-left, because the overwhelming majority of what a manga reader opens is
		 * Japanese and reads right to left. Guessing per-source from the content type was
		 * considered and rejected: it would be wrong often enough to be confusing, and the
		 * setting is one click away.
		 */
		val DEFAULT = REVERSED
	}
}

/** How a page is scaled into the viewport. */
enum class PageScale(val label: String) {
	/** Whole page visible. The default: no cropping, no scrolling to read one page. */
	FIT_PAGE("Fit page"),

	/** Fill the width; scroll vertically. Best for tall pages on a wide monitor. */
	FIT_WIDTH("Fit width"),

	/** Fill the height; scroll horizontally. Best for double-page spreads. */
	FIT_HEIGHT("Fit height"),

	/** One image pixel per screen pixel. For inspecting artwork or small text. */
	ORIGINAL("Original size"),
}
