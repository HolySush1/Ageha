package app.ageha.core.designsystem

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
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
				AgehaAccent.UnreadBadge(
					badgeCount,
					Modifier.align(Alignment.TopEnd).padding(AgehaSpacing.xs),
				)
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
	}
}

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
		Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
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

/** A hairline of progress across the bottom of a cover. Neutral, so it does not tint the art. */
@Composable
private fun BoxScope.ReadingProgressBar(progress: Float) {
	Box(
		Modifier
			.align(Alignment.BottomStart)
			.fillMaxWidth()
			.height(3.dp)
			.background(Color.Black.copy(alpha = 0.45f)),
	) {
		Box(
			Modifier
				.fillMaxWidth(progress.coerceIn(0f, 1f))
				.height(3.dp)
				.background(MaterialTheme.colorScheme.primary),
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
