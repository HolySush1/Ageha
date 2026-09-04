package app.ageha.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The layer everything else is glass *over*.
 *
 * An opaque surface with a gradient over it, both built entirely from the active colour scheme.
 * Which scheme that is comes from Settings -> Appearance and from nowhere else, so the window's
 * background is a setting the user chose rather than a side effect of what they last read.
 *
 * ## It used to be the user's cover art, and that was a mistake
 *
 * The first version drew the most recent Continue Reading cover here, blurred and scrimmed. The
 * idea -- the app as a room furnished with your own library -- was a good one, and the execution
 * had three problems, any one of which is enough on its own:
 *
 *  - **It made Appearance a half-truth.** Choosing Light and getting a window tinted by whatever
 *    was read last is a setting that does not settle the question it claims to settle.
 *  - **The source material could not carry it.** Sources serve cover thumbnails a few hundred
 *    pixels wide. Scaled to fill a 1280x860 window they are mush, and blur only disguises so much
 *    of that before the whole window looks out of focus.
 *  - **The background changed when nothing the user did should have changed it.** Finishing a
 *    chapter re-tinted the entire application.
 *
 * The cover art is not gone; it moved to where it belongs. The Continue Reading hero is *about*
 * one book, so it draws that book's cover at the cover's own size and takes its panel colour from
 * [CoverAccent] -- the same idea, at the one scale the artwork can actually support.
 *
 * ## What the glass alphas now sit on
 *
 * With arbitrary artwork gone, backdrop luminance is bounded by the theme's own tokens rather
 * than by a scrim over somebody's cover, and those tokens sit within a step or two of `surface`
 * by construction. [GlassTone]'s alphas therefore hold with more margin than before rather than
 * less, and `AgehaContrastTest` composites them over [backdropStops] instead of over scrimmed
 * black and white.
 *
 * ## Not in the reader
 *
 * The reader never draws this. Brand colour behind a page is the tinted-wash mistake rule 8 exists
 * to prevent, one layer further back -- and the shell suppresses the backdrop under the same
 * `isImmersive` check that hides the navigation.
 */
@Composable
fun AgehaBackdrop(
	modifier: Modifier = Modifier,
	content: @Composable BoxScope.() -> Unit,
) {
	Box(modifier) {
		// Opaque first, gradient second. The brand stop is deliberately translucent, and a
		// translucent gradient painted straight onto the window shows the toolkit's own clear
		// colour through that corner -- white, in a dark theme, which reads as a rendering fault.
		Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
		Box(Modifier.fillMaxSize().background(backdropGradient()))
		content()
	}
}

/**
 * The gradient under everything.
 *
 * Brand colour belongs here -- this is navigation and library chrome, which DESIGN.md names as
 * the place for it. It stays a wash rather than a statement: `surfaceDim` into `surface` with the
 * primary container barely present in one corner, so the indigo is something you would only
 * notice if you went looking for it.
 */
@Composable
private fun backdropGradient(): Brush = Brush.linearGradient(
	backdropStops(
		surfaceDim = MaterialTheme.colorScheme.surfaceDim,
		surface = MaterialTheme.colorScheme.surface,
		primaryContainer = MaterialTheme.colorScheme.primaryContainer,
	),
)

/**
 * The gradient's three stops, as a pure function of the scheme.
 *
 * Split out so `AgehaContrastTest` can composite glass over exactly the colours the window draws,
 * rather than over a second derivation of them that is free to drift.
 */
internal fun backdropStops(
	surfaceDim: Color,
	surface: Color,
	primaryContainer: Color,
): List<Color> = listOf(
	surfaceDim,
	surface,
	primaryContainer.copy(alpha = AgehaGlass.BACKDROP_BRAND_WASH),
)
