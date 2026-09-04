package app.ageha.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import app.ageha.core.image.AgehaImages
import coil3.compose.AsyncImage

/**
 * The layer everything else is glass *over*.
 *
 * Three things stacked, in this order, and each one earns its place:
 *
 *  1. **A brand gradient**, always drawn. It is what a fresh installation sees, and it is what
 *     shows through wherever the artwork does not reach. Without it the first launch is a grey
 *     void with a floating pill on it.
 *  2. **The artwork**, blurred, when there is any. This is the only place in Ageha that puts
 *     cover art behind the interface, and it is deliberately the *user's own* cover -- whatever
 *     they were last reading -- rather than a stock image. The app is a room furnished with their
 *     library.
 *  3. **The scrim**, at [AgehaGlass.BACKDROP_SCRIM].
 *
 * The scrim is not a matter of taste. Cover art is arbitrary: it can be a black gutter or a white
 * page, and glass panels drawn over an unbounded backdrop have no contrast guarantee whatsoever.
 * Scrimming clamps backdrop luminance into a narrow band around the theme's own surface colour,
 * which is the premise the alphas in [GlassTone] are derived from and that `AgehaContrastTest`
 * checks on every build. Lowering it without re-deriving those alphas breaks readability quietly,
 * on somebody else's artwork, which is the worst way for it to break.
 *
 * ## Not in the reader
 *
 * The reader never draws this. Cover art behind a page is exactly the tinted-wash mistake rule 8
 * exists to prevent, one layer further back -- and the shell already suppresses the backdrop under
 * the same `isImmersive` check that hides the navigation.
 */
@Composable
fun AgehaBackdrop(
	coverUrl: String?,
	imageHeaders: Map<String, String>,
	modifier: Modifier = Modifier,
	content: @Composable BoxScope.() -> Unit,
) {
	Box(modifier) {
		Box(Modifier.fillMaxSize().background(backdropGradient()))

		if (!coverUrl.isNullOrEmpty()) {
			AsyncImage(
				model = AgehaImages.request(coverUrl, imageHeaders),
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.fillMaxSize()
					// Rectangle edge treatment rather than the default: a blur that is allowed to
					// bleed past its bounds leaves a soft transparent halo down the window edges,
					// which reads as a rendering fault rather than as depth.
					.blur(AgehaGlass.BACKDROP_BLUR, BlurredEdgeTreatment.Rectangle),
			)
		}

		Box(
			Modifier
				.fillMaxSize()
				.background(
					MaterialTheme.colorScheme.surface.copy(alpha = AgehaGlass.BACKDROP_SCRIM),
				),
		)

		content()
	}
}

/**
 * The gradient under everything.
 *
 * Brand colour belongs here -- this is navigation and library chrome, which DESIGN.md names as
 * the place for it. It stays a wash rather than a statement: `surfaceDim` into `surface` with the
 * primary container barely present in one corner, so the indigo is something you would only
 * notice if you looked for it.
 */
@Composable
private fun backdropGradient(): Brush = Brush.linearGradient(
	listOf(
		MaterialTheme.colorScheme.surfaceDim,
		MaterialTheme.colorScheme.surface,
		MaterialTheme.colorScheme.primaryContainer.copy(alpha = BRAND_WASH),
	),
)

/** How much brand tint reaches the corner of the gradient. A hint, not a colour wash. */
private const val BRAND_WASH = 0.35f
