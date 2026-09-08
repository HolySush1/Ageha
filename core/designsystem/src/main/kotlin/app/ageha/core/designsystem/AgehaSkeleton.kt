package app.ageha.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * What a screen shows while it is loading, instead of a spinner on an empty window.
 *
 * ## Why not the spinner that was there
 *
 * Four screens in Ageha answered "still loading" with a centred `CircularProgressIndicator` on an
 * otherwise blank window: the details screen twice, the source list, and a source's first page of
 * covers. That is the single shape that makes an application feel slowest, for two reasons that
 * have nothing to do with how fast it actually is.
 *
 * It **reserves no space**, so when the content lands it lands somewhere the spinner never was,
 * and the whole screen jumps. And it **previews nothing**, so the two seconds spent watching it
 * tell the user nothing about what is coming -- whether this source returns twenty results or
 * four hundred, whether this manga has six chapters or nine hundred.
 *
 * A skeleton fixes both. It is drawn at the real proportions of the content that will replace it,
 * so nothing moves when the content arrives, and its shape *is* the preview. The gain is entirely
 * perceptual -- not one byte arrives sooner -- and perception is what "feels responsive" means.
 *
 * ## Why it shimmers rather than sits still
 *
 * A static grey rectangle where content should be is indistinguishable from content that failed to
 * load. That is the exact problem `Modifier.diagonalStripe` was written to solve for covers, and a
 * skeleton has it worse, because a skeleton is *supposed* to be replaced. The sweep is the part
 * that says "still working" rather than "broken", and it is the only animation in this file.
 *
 * The sweep runs on [rememberInfiniteTransition] and that choice is load-bearing. Compose's test
 * framework installs an `InfiniteAnimationPolicy` that suspends `withInfiniteAnimationFrameNanos`,
 * which is what an infinite transition is built on -- so `waitForIdle` in `AgehaJourneyTest` still
 * settles with a skeleton on screen. The obvious alternative, a `LaunchedEffect` with a forever
 * loop, animates identically and hangs that test.
 */
@Composable
fun Modifier.shimmer(): Modifier {
	// Off means genuinely off, not slowed. A sweep that never stops is the one animation a user
	// with a motion sensitivity cannot look away from, because it is in the middle of the screen
	// and it is what they are waiting on.
	if (!LocalMotionEnabled.current) return this
	val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = SHIMMER_ALPHA)
	val transition = rememberInfiniteTransition(label = "shimmer")
	val progress by transition.animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		// Linear, unusually for this codebase. Every other easing here shapes a movement between
		// two resting states; this one has no rest, and an eased loop visibly hesitates at the
		// seam where it restarts.
		animationSpec = infiniteRepeatable(tween(AgehaMotion.SHIMMER_MS, easing = LinearEasing)),
		label = "shimmer-sweep",
	)
	return this.drawWithContent {
		drawContent()
		val band = size.width * SHIMMER_BAND
		// Starts fully off the left edge and ends fully off the right, so the highlight is never
		// clipped mid-sweep at either end.
		val head = (size.width + band * 2f) * progress - band
		drawRect(
			brush = Brush.linearGradient(
				colors = listOf(Color.Transparent, highlight, Color.Transparent),
				start = Offset(head - band, 0f),
				end = Offset(head + band, size.height),
			),
		)
	}
}

/**
 * One rectangle of not-yet-content.
 *
 * Filled from `surfaceContainerHigh` -- the same fill a cover sits on before its artwork arrives,
 * so a skeleton and a half-loaded grid are the same colour rather than two different greys.
 */
@Composable
fun SkeletonBlock(
	modifier: Modifier = Modifier,
	shape: Shape = MaterialTheme.shapes.extraSmall,
) {
	Box(
		modifier
			.clip(shape)
			.background(MaterialTheme.colorScheme.surfaceContainerHigh)
			.shimmer(),
	)
}

/**
 * A grid of covers that have not arrived, at the real card's proportions.
 *
 * Reuses [coverPlaceholder] rather than drawing its own fill, which is the point: the skeleton and
 * a cover whose image is still downloading are then literally the same surface, so the grid does
 * not visibly change material as the first few images land.
 *
 * A non-scrolling `LazyVerticalGrid` rather than hand-rolled rows, so the reflow maths is the same
 * `GridCells.Adaptive` the real grid uses and the skeleton columns land where the real ones will.
 */
@Composable
fun CoverGridSkeleton(
	modifier: Modifier = Modifier,
	count: Int = SKELETON_COVERS,
	minCoverWidth: Dp = AgehaSpacing.minCoverWidth,
	contentPadding: PaddingValues = PaddingValues(AgehaSpacing.md),
) {
	LazyVerticalGrid(
		columns = GridCells.Adaptive(minSize = minCoverWidth),
		modifier = modifier,
		contentPadding = contentPadding,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.gridGutter),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.gridGutter),
		// Nothing to scroll to. A skeleton that scrolls invites the user to look for content
		// underneath it that does not exist yet.
		userScrollEnabled = false,
	) {
		items(count) {
			Column(
				Modifier.padding(AgehaSpacing.xs),
				verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
			) {
				Box(
					Modifier
						.fillMaxWidth()
						.aspectRatio(COVER_ASPECT_RATIO)
						.clip(CoverShape)
						.coverPlaceholder()
						.shimmer(),
				)
				// Two bars, the second short, because that is what a wrapped two-line manga title
				// looks like -- and `MangaCard` reserves two lines for one whether or not it needs
				// them, so this is the height the real card will be.
				SkeletonBlock(Modifier.fillMaxWidth().height(SKELETON_TEXT_HEIGHT))
				SkeletonBlock(Modifier.fillMaxWidth(SKELETON_SHORT_LINE).height(SKELETON_TEXT_HEIGHT))
			}
		}
	}
}

/**
 * A list of rows that have not arrived: chapters, sources, results.
 *
 * Each row is a wide bar and a narrow one, which is the shape of every row list in Ageha -- a
 * title and a piece of metadata beside it.
 */
@Composable
fun RowSkeleton(
	modifier: Modifier = Modifier,
	count: Int = SKELETON_ROWS,
) {
	Column(
		modifier.padding(AgehaSpacing.md),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
	) {
		repeat(count) { index ->
			Row(
				Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				// Varied widths, cycling. Rows of identical length read as a loading *bar* rather
				// than as a list, and the point of a skeleton is that its shape is the preview.
				SkeletonBlock(
					Modifier
						.fillMaxWidth(SKELETON_ROW_WIDTHS[index % SKELETON_ROW_WIDTHS.size])
						.height(SKELETON_TEXT_HEIGHT),
				)
				SkeletonBlock(Modifier.width(SKELETON_META_WIDTH).height(SKELETON_TEXT_HEIGHT))
			}
		}
	}
}

/**
 * [content], but only once it has been wanted for [delayMs] without interruption.
 *
 * A skeleton that appears and vanishes inside a tenth of a second is worse than no skeleton: the
 * flash is more disruptive than the wait it was covering, and it happens most on the fastest
 * loads -- a cached chapter list, a source that answers immediately -- which is exactly where the
 * application should look instantaneous rather than busy.
 *
 * The delay is not applied on the way out. Once a skeleton has been shown, it should be replaced
 * the moment there is something to replace it with.
 */
@Composable
fun DelayedAppearance(
	visible: Boolean,
	delayMs: Int = SKELETON_DELAY_MS,
	content: @Composable () -> Unit,
) {
	var settled by remember { mutableStateOf(false) }
	LaunchedEffect(visible) {
		if (!visible) {
			settled = false
		} else {
			delay(delayMs.toLong())
			settled = true
		}
	}
	if (visible && settled) content()
}

/** How long a load has to run before it is worth admitting to. */
private const val SKELETON_DELAY_MS = 120

/** Enough covers to fill a default window without pretending to know the real count. */
private const val SKELETON_COVERS = 12

/** Enough rows to reach the fold on a chapter or source list. */
private const val SKELETON_ROWS = 8

/** The highlight, at 8% of the ink. Bright enough to track, dim enough not to strobe. */
private const val SHIMMER_ALPHA = 0.08f

/** The sweep's width, as a fraction of the surface it crosses. */
private const val SHIMMER_BAND = 0.35f

/** A line of text, as a bar. Matches the metadata line height rather than the title's. */
private val SKELETON_TEXT_HEIGHT = 10.dp

/** The trailing metadata bar on a row: a chapter number, a language tag, a count. */
private val SKELETON_META_WIDTH = 48.dp

/** The second line of a wrapped title, as a fraction of the first. */
private const val SKELETON_SHORT_LINE = 0.6f

/** Row title widths, cycled, so a list of rows does not read as a single bar. */
private val SKELETON_ROW_WIDTHS = listOf(0.62f, 0.48f, 0.71f, 0.55f, 0.66f)
