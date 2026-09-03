package app.ageha.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The reader's background. **This is the one part of the app the brand does not reach.**
 *
 * The rule is absolute: nothing indigo, nothing vermillion, no tint of either, anywhere the
 * artwork is visible. Not a subtle wash behind the page, not a coloured letterbox around a
 * narrow page, not a themed scrollbar. Brand identity belongs in the library and the navigation;
 * over somebody's manga it is a stain on someone else's art.
 *
 * That is also why this lives outside `ColorScheme` entirely rather than as extra roles inside
 * it. A reader background is *not* a theme colour -- it is chosen independently of light/dark, it
 * persists per user rather than per theme, and keeping it structurally separate means no future
 * screen can reach for `MaterialTheme.colorScheme.surface` in the reader and quietly succeed.
 */
enum class ReaderBackground(val label: String, val color: Color, val onColor: Color) {

	/** For dark rooms and OLED panels. The default. */
	BLACK("Black", Color(0xFF000000), Color(0xFFB0B0B0)),

	/**
	 * Neutral dark grey. Easier on the eyes than pure black when the room is not dark, because
	 * the page's white gutters do not sit against a maximum-contrast field.
	 */
	GREY("Dark grey", Color(0xFF202020), Color(0xFFBDBDBD)),

	/**
	 * Warm off-white, for daylight reading and for scanned pages whose own paper is warm.
	 *
	 * This is a *neutral* warm grey, not the brand's paper tone. They are close, and the
	 * temptation to reuse `Brand.PAPER` here is exactly what the rule above exists to stop: the
	 * moment the reader background is a brand token, changing the brand changes the reader.
	 */
	PAPER("Paper", Color(0xFFEDEAE4), Color(0xFF3A3A3A)),

	/** Plain white. Some scanlations are keyed to it and anything else shows as a seam. */
	WHITE("White", Color(0xFFFFFFFF), Color(0xFF4A4A4A)),
	;

	val isDark: Boolean get() = this == BLACK || this == GREY
}

/**
 * Reader chrome: the page counter, the chapter label, the controls that fade away.
 *
 * Derived from the chosen background rather than the theme, so the overlay stays legible on a
 * white background and unobtrusive on a black one. Scrims are neutral greys at low alpha -- a
 * tinted scrim over artwork shifts the artwork's colour, which is the same mistake as a tinted
 * background wearing a different hat.
 */
@Immutable
data class ReaderChrome(
	val scrim: Color,
	val content: Color,
	val subdued: Color,
) {
	companion object {
		fun forBackground(background: ReaderBackground): ReaderChrome = if (background.isDark) {
			ReaderChrome(
				scrim = Color(0xCC101010),
				content = Color(0xFFE8E8E8),
				subdued = Color(0xFF9A9A9A),
			)
		} else {
			ReaderChrome(
				scrim = Color(0xE6F2F2F2),
				content = Color(0xFF262626),
				subdued = Color(0xFF6A6A6A),
			)
		}
	}
}
