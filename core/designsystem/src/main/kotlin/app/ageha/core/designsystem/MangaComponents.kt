package app.ageha.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.ageha.core.image.AgehaImages
import app.ageha.core.model.AgehaManga
import coil3.compose.AsyncImage

/**
 * The manga cover, and the grid it lives in.
 *
 * These are in the design system rather than in a feature module because the library and the
 * explore screens must show the *same* card. Two implementations of a cover card is how a grid
 * ends up with different corner radii on two screens that sit one click apart.
 */

/** A cover's aspect ratio. Near-universal for manga, and the grid depends on it to lay out. */
const val COVER_ASPECT_RATIO = 2f / 3f

/**
 * One manga, as a cover with its title beneath.
 *
 * @param badgeCount unread chapters. Zero draws nothing.
 * @param progress 0..1 reading progress, or null if never opened.
 */
@Composable
fun MangaCard(
	manga: AgehaManga,
	imageHeaders: Map<String, String>,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	badgeCount: Int = 0,
	progress: Float? = null,
	isSelected: Boolean = false,
	/** Mono line under the title, left: how far in you are. Null draws no row at all. */
	positionLabel: String? = null,
	/** Mono line under the title, right: `read`, `reading`, `started`. */
	stateLabel: String? = null,
	/** Every chapter read. Draws the accent tick, and turns [stateLabel] accent-coloured. */
	isComplete: Boolean = false,
) {
	Column(
		modifier = modifier
			.testTag(MANGA_CARD_TAG)
			.clip(MaterialTheme.shapes.small)
			.clickable(onClick = onClick)
			.padding(AgehaSpacing.xs),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
	) {
		Box(
			Modifier
				.fillMaxWidth()
				.aspectRatio(COVER_ASPECT_RATIO)
				.clip(CoverShape)
				.background(MaterialTheme.colorScheme.surfaceContainerHigh)
				.then(
					// Selection is a border rather than a tint. A tint over cover art is the same
					// mistake the reader rule exists to prevent, one screen earlier.
					if (isSelected) {
						Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CoverShape)
					} else {
						Modifier
					},
				),
		) {
			CoverImage(manga, imageHeaders)
			if (progress != null && progress > 0f) ReadingProgressBar(progress)
			if (badgeCount > 0) {
				CoverBadge(
					if (badgeCount == 1) "NEW" else "NEW $badgeCount",
					Modifier.align(Alignment.TopStart).padding(AgehaSpacing.xs),
				)
			}
			// Top *right*, opposite the badge, and only when finished. The two never compete for
			// the same corner: a title with unread chapters is by definition not complete.
			if (isComplete) {
				CompletionTick(Modifier.align(Alignment.TopEnd).padding(AgehaSpacing.xs))
			}
		}
		Text(
			text = manga.title,
			style = AgehaTextStyles.mangaTitle,
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			minLines = 2,
		)
		// Drawn only when there is something to say. A row of empty mono baselines under every
		// never-opened cover would add a line of height to each card in the grid to report
		// nothing, which on a shelf of new titles is most of them.
		if (positionLabel != null || stateLabel != null) {
			Row(
				Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(
					positionLabel.orEmpty(),
					style = AgehaTextStyles.monoMeta,
					color = AgehaTheme.skin.inkFaint,
					maxLines = 1,
				)
				Text(
					stateLabel.orEmpty(),
					style = AgehaTextStyles.monoMeta,
					// The one word on the card that earns the accent. "read" is the end state a
					// shelf is scanned for, and colouring it is what lets someone find the
					// finished titles without reading a single title.
					color = if (isComplete) AgehaTheme.skin.accent else AgehaTheme.skin.inkFaint,
					maxLines = 1,
				)
			}
		}
	}
}

/**
 * The mono cap in the corner of a cover: `NEW`, `NEW 3`, `DONE`.
 *
 * Its own near-black plate rather than the theme's surface, and that is deliberate: this sits on
 * arbitrary cover art, which can be any colour at any brightness, so a translucent theme fill has
 * no contrast guarantee at all. A fixed dark plate does, in every skin, over every cover.
 */
@Composable
private fun CoverBadge(label: String, modifier: Modifier = Modifier) {
	Box(
		modifier
			.clip(AgehaTheme.skin.chip)
			.background(Color(0xB30C0A0E))
			.padding(horizontal = 5.dp, vertical = 2.dp),
	) {
		Text(label, style = AgehaTextStyles.monoEyebrow, color = Color.White)
	}
}

/**
 * The accent disc with a white tick: every chapter read.
 *
 * Drawn rather than set as a glyph, for the reason the window buttons are: a check mark is not in
 * Archivo, and a text implementation would fall through to whatever face Skia found next.
 */
@Composable
private fun CompletionTick(modifier: Modifier = Modifier) {
	Box(
		modifier
			.size(19.dp)
			.clip(CircleShape)
			.background(AgehaTheme.skin.accent)
			.semantics { contentDescription = "Finished" },
		contentAlignment = Alignment.Center,
	) {
		Canvas(Modifier.size(9.dp)) {
			val stroke = 2.dp.toPx()
			drawLine(
				Color.White,
				Offset(0f, size.height * 0.55f),
				Offset(size.width * 0.38f, size.height),
				stroke,
				StrokeCap.Round,
			)
			drawLine(
				Color.White,
				Offset(size.width * 0.38f, size.height),
				Offset(size.width, 0f),
				stroke,
				StrokeCap.Round,
			)
		}
	}
}

/*
 * `CoverBanner` used to live here: a cover with no aspect ratio of its own, cropped to whatever
 * shape the caller asked for. Its only caller was the Continue Reading hero, and cropping a 2:3
 * cover into a wide band is exactly the mistake that hero was rebuilt to stop making. Nothing
 * else ever wanted a cover at an arbitrary shape, so it is gone rather than left as a trap.
 */

@Composable
private fun CoverImage(manga: AgehaManga, imageHeaders: Map<String, String>) {
	val url = manga.coverUrl
	if (url.isNullOrEmpty()) {
		CoverFallback(manga.title)
		return
	}
	AsyncImage(
		model = AgehaImages.request(url, imageHeaders),
		contentDescription = manga.title,
		contentScale = ContentScale.Crop,
		modifier = Modifier.fillMaxSize(),
		// A broken cover falls back to the title rather than to a broken-image glyph. Sources
		// serve dead cover URLs constantly; a grid of error icons says "Ageha is broken" when the
		// truth is "this one image 404s", and the title is still useful.
		error = null,
	)
}

/**
 * What a cover shows when there is no image: the title, set in the manga title face.
 *
 * Deliberately not the Ageha seal. The seal is the *app's* mark; stamping it across every missing
 * cover would turn a branding element into visual noise and make a broken grid look intentional.
 */
@Composable
private fun CoverFallback(title: String) {
	Box(
		Modifier.fillMaxSize().coverPlaceholder(),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = title,
			style = AgehaTextStyles.mangaTitle,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
			maxLines = 4,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(AgehaSpacing.sm),
		)
	}
}

/**
 * A cover on its own, at whatever size the caller wants.
 *
 * For list rows rather than grids -- Continue Reading is a list of things you were part-way
 * through, where a full [MangaCard] would give a cover the same weight as the chapter you stopped
 * on. It shares [MangaCard]'s image path, fallback and progress hairline, which is the point of it
 * living here: a second cover implementation is how two screens end up with different corner radii.
 */
@Composable
fun MangaThumbnail(
	manga: AgehaManga,
	imageHeaders: Map<String, String>,
	modifier: Modifier = Modifier,
	progress: Float? = null,
) {
	Box(
		modifier
			.aspectRatio(COVER_ASPECT_RATIO)
			.clip(CoverShape)
			.background(MaterialTheme.colorScheme.surfaceContainerHigh),
	) {
		CoverImage(manga, imageHeaders)
		if (progress != null && progress > 0f) ReadingProgressBar(progress)
	}
}

/**
 * The placeholder a cover shows before -- or instead of -- its artwork.
 *
 * Two layers, both from the handoff: the skin's own `--cover` gradient at 160 degrees, and a
 * repeating 135-degree stripe of white at 5%. The stripe is what stops it reading as a broken
 * image. A flat rectangle in a grid of real covers looks like a failure; the same rectangle with a
 * texture across it looks like something that has not arrived yet, which is what it is.
 *
 * `TileMode.Repeated` over a short gradient is how a repeating stripe is expressed here -- Compose
 * has no repeating-linear-gradient, and drawing the bars by hand would mean a `Canvas` that has to
 * be told its own size.
 */
@Composable
fun Modifier.coverPlaceholder(): Modifier {
	val skin = AgehaTheme.skin
	val stripe = Color.White.copy(alpha = 0.05f)
	return this
		.background(
			Brush.linearGradient(
				listOf(skin.coverHigh, skin.coverLow),
				start = Offset.Zero,
				end = Offset(STRIPE_SPAN * 6, STRIPE_SPAN * 18),
			),
		)
		.background(
			Brush.linearGradient(
				0f to stripe,
				0.5f to stripe,
				0.5f to Color.Transparent,
				1f to Color.Transparent,
				start = Offset.Zero,
				end = Offset(STRIPE_SPAN, STRIPE_SPAN),
				tileMode = TileMode.Repeated,
			),
		)
}

/** The handoff's 9px band inside an 18px repeat, as one diagonal step. */
private const val STRIPE_SPAN = 18f

/**
 * Progress across the bottom of a cover.
 *
 * Accent rather than `primary`, which is the one place the reader's neutrality rule does *not*
 * reach: this is 3dp of library chrome pinned to the edge of the art, not a wash over it, and it
 * is the same bar the handoff draws in the same colour. The dark track underneath is what keeps it
 * legible over a pale cover.
 */
@Composable
private fun BoxScope.ReadingProgressBar(progress: Float) {
	Box(
		Modifier
			.align(Alignment.BottomStart)
			.fillMaxWidth()
			.height(3.dp)
			.background(Color.Black.copy(alpha = 0.6f)),
	) {
		Box(
			Modifier
				.fillMaxWidth(progress.coerceIn(0f, 1f))
				.height(3.dp)
				.background(AgehaTheme.skin.accent),
		)
	}
}

/**
 * A grid of covers that reflows with the window.
 *
 * `GridCells.Adaptive` rather than a fixed column count: this is a desktop app, the window is
 * resizable by definition, and a fixed count either wastes half an ultrawide or crushes the
 * covers on a narrow pane. The minimum width comes from the spacing scale so the reflow point is
 * a design token rather than a number buried in a screen.
 */
@Composable
fun MangaGrid(
	manga: List<MangaGridItem>,
	onClick: (AgehaManga) -> Unit,
	modifier: Modifier = Modifier,
	state: LazyGridState = rememberLazyGridState(),
	minCoverWidth: androidx.compose.ui.unit.Dp = AgehaSpacing.minCoverWidth,
	contentPadding: androidx.compose.foundation.layout.PaddingValues =
		androidx.compose.foundation.layout.PaddingValues(AgehaSpacing.md),
	footer: @Composable (() -> Unit)? = null,
) {
	LazyVerticalGrid(
		columns = GridCells.Adaptive(minSize = minCoverWidth),
		state = state,
		modifier = modifier,
		contentPadding = contentPadding,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.gridGutter),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.gridGutter),
	) {
		items(manga, key = { it.key }) { item ->
			MangaCard(
				manga = item.manga,
				imageHeaders = item.imageHeaders,
				onClick = { onClick(item.manga) },
				badgeCount = item.badgeCount,
				progress = item.progress,
				isSelected = item.isSelected,
				positionLabel = item.positionLabel,
				stateLabel = item.stateLabel,
				isComplete = item.isComplete,
			)
		}
		if (footer != null) {
			item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
				footer()
			}
		}
	}
}

/**
 * One cell's worth of state.
 *
 * [key] is composed from the source name and the manga id rather than the id alone. Ids are only
 * unique *within* a source, and a grid mixing sources -- global search does exactly that -- would
 * otherwise reuse a composition slot for two unrelated manga and show the wrong cover.
 */
data class MangaGridItem(
	val manga: AgehaManga,
	val imageHeaders: Map<String, String> = emptyMap(),
	val badgeCount: Int = 0,
	val progress: Float? = null,
	val isSelected: Boolean = false,
	/** See `MangaCard`. Explore leaves all three unset: a catalogue row has no reading state. */
	val positionLabel: String? = null,
	val stateLabel: String? = null,
	val isComplete: Boolean = false,
) {
	val key: String get() = "${manga.sourceName}:${manga.id}"
}

/**
 * The empty state.
 *
 * This is one of the few places the stamped motif is allowed out -- the brief permits it on empty
 * states, the About screen and the splash, and forbids it in lists and the reader.
 */
@Composable
fun EmptyState(
	title: String,
	detail: String? = null,
	modifier: Modifier = Modifier,
	action: @Composable (() -> Unit)? = null,
) {
	Column(
		modifier = modifier.fillMaxSize().padding(AgehaSpacing.xxl),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.md, Alignment.CenterVertically),
	) {
		androidx.compose.foundation.Image(
			painter = BrandAssets.painter("icon-128.png"),
			contentDescription = null,
			modifier = Modifier.height(72.dp),
			alpha = 0.22f,
		)
		Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
		if (detail != null) {
			Text(
				detail,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)
		}
		if (action != null) {
			Row(Modifier.padding(top = AgehaSpacing.sm)) { action() }
		}
	}
}

/**
 * Test tag for the end-to-end journey driver.
 *
 * Every grid of manga in Ageha is built from [MangaCard], so one tag finds a search result, a
 * library entry and a source listing alike -- and the driver does not need to know which titles a
 * live source will return today.
 */
const val MANGA_CARD_TAG = "manga-card"
