package app.ageha.core.designsystem

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.blur
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
 * How dense the library grid is: the handoff's `opts.grid`.
 *
 * Three densities rather than three layouts. [COVER] and [COMPACT] are the same card at two
 * reflow widths, which is why the difference between them is one number -- a "compact" card that
 * dropped the title or the progress bar would be a second card implementation, and two cover
 * cards one click apart is exactly what putting them in the design system was meant to prevent.
 *
 * [LIST] is genuinely different, and it earns that: a row can carry a long title without clipping
 * it to two lines, which is the whole reason someone chooses it. It reuses [MangaThumbnail] rather
 * than a shrunken card, so the cover, the fallback and the progress hairline stay one thing.
 */
enum class CardStyle(val label: String) {
	COVER("Cover"),
	COMPACT("Compact"),
	LIST("List"),
	;

	/** The reflow width the grid adapts on. Ignored by [LIST], which is one column by definition. */
	val minCoverWidth: androidx.compose.ui.unit.Dp
		get() = when (this) {
			COVER -> AgehaSpacing.minCoverWidth
			COMPACT -> COMPACT_COVER_WIDTH
			LIST -> AgehaSpacing.minCoverWidth
		}
}

/**
 * Compact's reflow width.
 *
 * 104dp rather than something smaller. Below about this the two-line title under a cover stops
 * fitting a real manga name at all and every card ends in an ellipsis, at which point the grid is
 * denser and less useful -- which is not a trade the setting is offering.
 */
private val COMPACT_COVER_WIDTH = 104.dp

/**
 * How hard an 18+ cover is blurred before it is pointed at.
 *
 * Enough to defeat recognition at a glance across a room, not so much that the card becomes a
 * grey rectangle -- the point is that the shelf is still navigable, not that the artwork is gone.
 */
private val NSFW_BLUR_RADIUS = 14.dp

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
	/**
	 * Blur the artwork until the pointer is on it: the handoff's `opts.nsfwBlur`.
	 *
	 * Only the *cover* blurs. The title, the badge and the progress bar stay sharp, because the
	 * setting exists so a shelf can be scanned in a room with other people in it -- blurring the
	 * text as well would make it a shelf nobody can use, including the person who turned it on.
	 */
	blurCover: Boolean = false,
	/**
	 * The right-click menu.
	 *
	 * Empty installs no menu at all rather than an empty one. A secondary click that opens a
	 * blank popup is worse than one that does nothing, because it looks like the actions failed
	 * to load rather than like there are none.
	 */
	actions: List<MangaCardAction> = emptyList(),
) {
	val hover = rememberInteraction()
	val isHovered by hover.collectIsHoveredAsState()
	// The card lifts *and* casts a shadow, which is one effect rather than two: scale alone reads
	// as a zoom, and the shadow is what turns it into the card coming forward out of the shelf.
	// Applied to the cover box rather than the whole column so the shadow follows the artwork's
	// corner radius instead of boxing the title underneath it.
	val lift by animateDpAsState(
		targetValue = if (isHovered) CARD_HOVER_ELEVATION else 0.dp,
		animationSpec = snappySpring(Dp.VisibilityThreshold),
		label = "card-lift",
	)
	// Crossed rather than switched on. Un-blurring an adult cover the instant the pointer lands on
	// it is a hard cut to artwork the user has asked to keep out of the corner of their eye; a
	// short reveal gives them the fraction of a second to look away that the setting is for.
	val blur by animateDpAsState(
		targetValue = if (blurCover && !isHovered) NSFW_BLUR_RADIUS else 0.dp,
		animationSpec = motionTween(AgehaMotion.QUICK_MS),
		label = "cover-blur",
	)
	WithContextMenu(actions) {
		Column(
			modifier = modifier
				.testTag(MANGA_CARD_TAG)
				.clip(MaterialTheme.shapes.small)
				.interactive(hover, hoverScale = HOVER_SCALE_CARD)
				.clickable(interactionSource = hover, indication = null, onClick = onClick)
				.padding(AgehaSpacing.xs),
			verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
		) {
			Box(
				Modifier
					.fillMaxWidth()
					.aspectRatio(COVER_ASPECT_RATIO)
					.shadow(lift, CoverShape)
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
				// The blur wraps only the image, so the badge, the tick and the progress bar drawn
				// after it stay legible. `Modifier.blur` blurs its own content, which is exactly the
				// primitive wanted here -- and the one place in Ageha where that is true; see
				// AgehaGlass for why it is useless for the chrome.
				Box(
					// `blur` of zero is a no-op rather than a blur of nothing, so an ordinary
					// cover pays for none of this.
					Modifier.fillMaxSize().blur(blur),
				) {
					CoverImage(manga, imageHeaders)
				}
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
}

/**
 * [content], with a secondary-click menu over it when there is one to show.
 *
 * `ContextMenuArea` is Compose Desktop's own, so the popup is the same one text selection uses
 * and it dismisses, keyboard-navigates and positions itself the way the platform's do. Writing a
 * `DropdownMenu` on a right-click `pointerInput` would have been a menu that only looked like the
 * others.
 */
@Composable
private fun WithContextMenu(actions: List<MangaCardAction>, content: @Composable () -> Unit) {
	if (actions.isEmpty()) {
		content()
		return
	}
	ContextMenuArea(
		items = { actions.map { ContextMenuItem(it.label, it.onSelect) } },
		content = content,
	)
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
	return this
		.background(
			Brush.linearGradient(
				listOf(skin.coverHigh, skin.coverLow),
				start = Offset.Zero,
				end = Offset(STRIPE_SPAN * 6, STRIPE_SPAN * 18),
			),
		)
		.diagonalStripe()
}

/**
 * The handoff's diagonal stripe: a 9px band inside an 18px repeat, at white 4-5%.
 *
 * It appears on every placeholder surface in the design -- covers, the Continue banner, the
 * reader's page blocks -- and it is doing one job in all three: saying "this is a surface, not a
 * failure". A flat rectangle in a grid reads as something that did not load; a striped one reads
 * as a deliberate texture.
 *
 * Public because the banner needs it over artwork rather than over a gradient, and a second
 * implementation of a texture is how two surfaces end up with stripes at different angles.
 */
fun Modifier.diagonalStripe(alpha: Float = STRIPE_ALPHA): Modifier {
	val stripe = Color.White.copy(alpha = alpha)
	return this.background(
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

/**
 * How far a cover rises out of the shelf under the pointer.
 *
 * Six dp of shadow, not of movement -- the card stays in the plane of the grid and only its
 * shadow says otherwise, which is what keeps a hovered cover from overlapping the titles of the
 * two cards beneath it.
 */
private val CARD_HOVER_ELEVATION = 6.dp

/** The handoff's 9px band inside an 18px repeat, as one diagonal step. */
private const val STRIPE_SPAN = 18f

/** White at 5%. Visible as texture on a dark surface, invisible as a pattern. */
private const val STRIPE_ALPHA = 0.05f

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
	// Grown rather than redrawn. Coming back from the reader to a shelf where the bar under the
	// cover you were just reading is visibly further along is the one place the library reports
	// what you did while you were away, and a bar that simply appears at its new length says
	// nothing at all. Critically damped: a progress bar that overshot would, for a few frames,
	// report a percentage that is not true.
	val filled by animateFloatAsState(
		targetValue = progress.coerceIn(0f, 1f),
		animationSpec = settleSpring(),
		label = "reading-progress",
	)
	Box(
		Modifier
			.align(Alignment.BottomStart)
			.fillMaxWidth()
			.height(3.dp)
			.background(Color.Black.copy(alpha = 0.6f)),
	) {
		Box(
			Modifier
				.fillMaxWidth(filled)
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
	/** The handoff's card style. [CardStyle.LIST] switches this to a one-column row list. */
	style: CardStyle = CardStyle.COVER,
	/** Blur the covers of adult-rated titles until pointed at. See `MangaCard.blurCover`. */
	blurAdult: Boolean = false,
) {
	// Belt and braces over the deduplication `CatalogRepository` already does.
	//
	// A lazy list throws on a repeated key rather than rendering, so one duplicate anywhere in a
	// list takes the whole screen down -- and every list here is built from source output or from
	// a join over it. The repository is where a duplicate is *understood*; this is where it stops
	// being fatal, for callers that assemble a list some other way.
	@Suppress("NAME_SHADOWING")
	val manga = remember(manga) { manga.distinctBy { it.key } }
	if (style == CardStyle.LIST) {
		MangaList(manga, onClick, modifier, contentPadding, footer, blurAdult)
		return
	}
	val entrance = rememberStaggerGate()
	LazyVerticalGrid(
		columns = GridCells.Adaptive(
			// The style's own reflow width wins over the caller's, unless the caller asked for
			// something other than the default -- Explore's catalogue grid sets its own and is
			// not a library shelf, so the library's density setting has no business there.
			minSize = if (minCoverWidth == AgehaSpacing.minCoverWidth) {
				style.minCoverWidth
			} else {
				minCoverWidth
			},
		),
		state = state,
		modifier = modifier,
		contentPadding = contentPadding,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.gridGutter),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.gridGutter),
	) {
		itemsIndexed(manga, key = { _, item -> item.key }) { index, item ->
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
				blurCover = blurAdult && item.manga.isAdult,
				actions = item.actions,
				// Two different animations, doing two different jobs.
				//
				// `animateItem` handles a cover that is *already here* and has been given a new
				// place -- re-sorting the shelf, switching category, a title being marked read and
				// dropping down the list. Watching the covers travel is what says "the same shelf,
				// reordered"; without it the grid simply becomes a different grid, and the user has
				// to re-find the title they were looking at.
				//
				// `motionStagger` handles a cover that was *not here before*. See `StaggerGate` for
				// why it is timed rather than indexed.
				modifier = Modifier
					.animateItem(placementSpec = settleSpring(IntOffset.VisibilityThreshold))
					.motionStagger(index, entrance),
			)
		}
		if (footer != null) {
			item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
				footer()
			}
		}
	}
}

/** True when the source rated this title adult. Null means the source said nothing. */
private val AgehaManga.isAdult: Boolean
	get() = contentRating == app.ageha.core.model.AgehaContentRating.ADULT

/**
 * The same shelf as one column of rows: [CardStyle.LIST].
 *
 * The trade this style makes is horizontal room for vertical room -- a row gives a long title one
 * unclipped line and a place to put the position and the state beside it, and costs the ability to
 * see twenty covers at once. That is a real preference rather than a skin, which is why it is a
 * setting and not a breakpoint.
 */
@Composable
private fun MangaList(
	manga: List<MangaGridItem>,
	onClick: (AgehaManga) -> Unit,
	modifier: Modifier,
	contentPadding: androidx.compose.foundation.layout.PaddingValues,
	footer: @Composable (() -> Unit)?,
	blurAdult: Boolean,
) {
	val entrance = rememberStaggerGate()
	LazyColumn(
		modifier = modifier,
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
	) {
		itemsIndexed(manga, key = { _, item -> item.key }) { index, item ->
			val hover = rememberInteraction()
			WithContextMenu(item.actions) {
				Row(
					Modifier
						.animateItem(placementSpec = settleSpring(IntOffset.VisibilityThreshold))
						.motionStagger(index, entrance)
						.fillMaxWidth()
						.clip(MaterialTheme.shapes.medium)
						// A tint rather than a scale. A row that grew under the pointer would push
						// its neighbours' text sideways, and a list of titles that shuffles as you
						// read down it is harder to use than one with no hover state at all.
						.interactive(hover, hoverTint = rowHoverTint)
						.clickable(
							interactionSource = hover,
							indication = null,
						) { onClick(item.manga) }
						.padding(AgehaSpacing.sm),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
				) {
					val rowBlur by animateDpAsState(
						targetValue = if (blurAdult && item.manga.isAdult) NSFW_BLUR_RADIUS else 0.dp,
						animationSpec = motionTween(AgehaMotion.QUICK_MS),
						label = "row-cover-blur",
					)
					Box(Modifier.width(LIST_COVER_WIDTH).blur(rowBlur)) {
						MangaThumbnail(
							manga = item.manga,
							imageHeaders = item.imageHeaders,
							progress = item.progress,
						)
					}
					Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
						Text(
							item.manga.title,
							style = AgehaTextStyles.mangaTitle,
							color = MaterialTheme.colorScheme.onSurface,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						Text(
							listOfNotNull(item.positionLabel, item.stateLabel, item.manga.sourceName)
								.joinToString(" · "),
							style = AgehaTextStyles.monoMeta,
							color = AgehaTheme.skin.inkFaint,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
					if (item.badgeCount > 0) {
						CoverBadge(
							if (item.badgeCount == 1) "NEW" else "NEW ${item.badgeCount}",
						)
					}
			}
			}
		}
		if (footer != null) item { footer() }
	}
}

/** A list row's cover. Wide enough to recognise the artwork, narrow enough to stay a row. */
private val LIST_COVER_WIDTH = 44.dp

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
	/** The card's right-click menu. Empty means the card has no secondary click at all. */
	val actions: List<MangaCardAction> = emptyList(),
) {
	val key: String get() = "${manga.sourceName}:${manga.id}"
}

/**
 * One entry in a card's right-click menu.
 *
 * A label and a lambda, deliberately: the design system draws cards and has no business knowing
 * what a library does with one. Every screen that shows a grid passes its own set, and Explore
 * passes none -- a catalogue result has nothing to mark read.
 */
data class MangaCardAction(val label: String, val onSelect: () -> Unit)

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
