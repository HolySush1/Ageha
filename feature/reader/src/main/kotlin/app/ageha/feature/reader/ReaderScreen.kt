package app.ageha.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.ReaderBackground
import app.ageha.core.designsystem.ReaderChrome
import app.ageha.core.designsystem.SourceFailureNotice
import app.ageha.core.image.AgehaImages
import app.ageha.core.model.PageScale
import app.ageha.core.model.ReaderMode
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The reader.
 *
 * **No brand colour reaches this screen.** The background is one of four user-selected neutrals,
 * the chrome derives from that background rather than from the app theme, and nothing indigo or
 * vermillion is drawn anywhere. That is `docs/DESIGN.md` 6 and `ReaderNeutralityTest` enforces the
 * palette side of it; this file is the other half of the promise.
 */
@Composable
fun ReaderScreen(
	state: ReaderUiState,
	background: ReaderBackground,
	doublePage: Boolean,
	coverOffset: Boolean,
	onPageChange: (Int) -> Unit,
	onScroll: (page: Int, fraction: Float) -> Unit,
	onNextPage: () -> Unit,
	onPreviousPage: () -> Unit,
	onSetMode: (ReaderMode) -> Unit,
	onSetScale: (PageScale) -> Unit,
	onSetBackground: (ReaderBackground) -> Unit,
	onToggleDoublePage: () -> Unit,
	onToggleCoverOffset: () -> Unit,
	onToggleChrome: () -> Unit,
	onRetry: () -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
	webtoonZoom: Float = 1f,
	onSetWebtoonZoom: (Float) -> Unit = {},
	onHideChrome: () -> Unit = {},
	/** Settings' "Preload next pages". Zero means the whole chapter. */
	preloadPages: Int = DEFAULT_PRELOAD_PAGES,
	/**
	 * The application's image loader, for warming pages ahead of the reader.
	 *
	 * Passed in rather than taken from `SingletonImageLoader`, and that is not a style preference.
	 * `SingletonImageLoader.get` *creates* Coil's default loader when none has been installed, and
	 * the application installs its own with `setSafe`, which refuses to replace one that already
	 * exists. A single composition of this screen before the graph was built would therefore have
	 * pinned a loader with no OkHttp fetcher, no archive fetcher and no disk cache for the life of
	 * the process. `ImageLoaderWiringTest` caught exactly that.
	 *
	 * Null disables preloading, which is what the headless render and the tests want.
	 */
	imageLoader: ImageLoader? = null,
) {
	val chrome = remember(background) { ReaderChrome.forBackground(background) }
	PreloadPages(state, preloadPages, imageLoader)

	// Chrome auto-hide, and the two signals it needs.
	//
	// The rule the old timer got wrong: it counted only page turns, so a bar someone had just
	// clicked to reveal vanished 2.5s later while they were still reaching for it with the mouse.
	// The countdown now does not start until the pointer has actually moved, and is suspended
	// entirely while the pointer is over the chrome -- so a bar you are aiming at, or reading,
	// stays put.
	var pointerMoves by remember { mutableIntStateOf(0) }
	var movesWhenShown by remember { mutableIntStateOf(0) }
	val chromeHover = remember { MutableInteractionSource() }
	val isChromeHovered by chromeHover.collectIsHoveredAsState()
	LaunchedEffect(state.isChromeVisible) {
		if (state.isChromeVisible) movesWhenShown = pointerMoves
	}
	AutoHideChrome(
		activity = pointerMoves,
		isVisible = state.isChromeVisible,
		suppressed = isChromeHovered || pointerMoves == movesWhenShown,
		onHide = onHideChrome,
	)

	Box(
		modifier
			.fillMaxSize()
			.background(background.color)
			// Watches the pointer without ever taking an event: the Initial pass sees everything
			// before any child does, and nothing here consumes, so page turns, zoom and taps all
			// still work exactly as they did.
			.pointerInput(Unit) {
				awaitPointerEventScope {
					while (true) {
						val event = awaitPointerEvent(PointerEventPass.Initial)
						if (event.type == PointerEventType.Move) pointerMoves++
					}
				}
			},
	) {
		when {
			state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
				CircularProgressIndicator(color = chrome.content)
			}

			state.failure != null -> Box(Modifier.fillMaxSize().padding(AgehaSpacing.xxl), Alignment.Center) {
				SourceFailureNotice(state.failure, onRetry = onRetry)
			}

			state.pages.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
				Text("This chapter has no pages.", color = chrome.content)
			}

			state.mode == ReaderMode.WEBTOON -> WebtoonReader(
				state = state,
				chrome = chrome,
				background = background,
				zoom = webtoonZoom,
				onScroll = onScroll,
				onZoom = onSetWebtoonZoom,
				onToggleChrome = onToggleChrome,
			)

			else -> PagedReader(
				state = state,
				chrome = chrome,
				doublePage = doublePage,
				coverOffset = coverOffset,
				onPageChange = onPageChange,
				onNextPage = onNextPage,
				onPreviousPage = onPreviousPage,
				onToggleChrome = onToggleChrome,
			)
		}

		// Chrome fades rather than slides. A bar sliding in over artwork draws the eye to the
		// movement; a fade at 260ms is noticed only if you look for it, which is the point.
		AnimatedVisibility(
			visible = state.isChromeVisible,
			enter = fadeIn(tween(app.ageha.core.designsystem.AgehaMotion.CHROME_FADE_MS)),
			exit = fadeOut(tween(app.ageha.core.designsystem.AgehaMotion.CHROME_FADE_MS)),
			modifier = Modifier.align(Alignment.TopCenter),
		) {
			ReaderTopBar(state, chrome, background, doublePage, coverOffset, webtoonZoom, onSetMode,
				onSetScale, onSetBackground, onToggleDoublePage, onToggleCoverOffset,
				onSetWebtoonZoom, onClose, Modifier.hoverable(chromeHover))
		}
		// The status bar waits for pages; the top bar above does not.
		//
		// Everything it says is derived from a page list, so before that list arrives it reads
		// "1 / 0" and "Chapter 1 of 0" -- confidently, and wrongly, for however long a source
		// takes to answer. The top bar stays up because Close and the mode controls are exactly
		// what someone wants while a slow chapter is still loading.
		AnimatedVisibility(
			visible = state.isChromeVisible && state.pages.isNotEmpty(),
			enter = fadeIn(tween(app.ageha.core.designsystem.AgehaMotion.CHROME_FADE_MS)),
			exit = fadeOut(tween(app.ageha.core.designsystem.AgehaMotion.CHROME_FADE_MS)),
			modifier = Modifier.align(Alignment.BottomCenter),
		) {
			ReaderStatusBar(state, chrome, onPageChange, Modifier.hoverable(chromeHover))
		}
	}
}

/**
 * Paged mode: one spread at a time, LTR or RTL, optionally two pages side by side.
 *
 * Not a `LazyRow` with snapping. A pager would keep neighbouring pages composed and decoded, which
 * for two 4000px scans either side of the current one is tens of megabytes of bitmap for pages
 * nobody is looking at. Rendering exactly the current spread and prefetching *urls* rather than
 * decoded images is what keeps memory flat across a long chapter.
 */
@Composable
private fun PagedReader(
	state: ReaderUiState,
	chrome: ReaderChrome,
	doublePage: Boolean,
	coverOffset: Boolean,
	onPageChange: (Int) -> Unit,
	onNextPage: () -> Unit,
	onPreviousPage: () -> Unit,
	onToggleChrome: () -> Unit,
) {
	val spreads = remember(state.pageCount, doublePage, coverOffset) {
		PageLayout.spreads(state.pageCount, doublePage, coverOffset)
	}
	val spreadIndex = remember(spreads, state.currentPage) {
		PageLayout.spreadOf(spreads, state.currentPage).coerceAtLeast(0)
	}
	val spread = spreads.getOrNull(spreadIndex) ?: return
	val zoom = rememberZoomPanState(spreadIndex, state.chapter?.id)
	val ordered = remember(spread, state.mode) {
		PageLayout.orderedPages(spread, state.mode.isRightToLeft)
	}

	Row(
		Modifier
			.fillMaxSize()
			.zoomPan(
				state = zoom,
				scale = state.scale,
				onScrollFallthrough = { delta -> if (delta > 0) onNextPage() else onPreviousPage() },
				onTap = onToggleChrome,
			),
		horizontalArrangement = Arrangement.Center,
		verticalAlignment = Alignment.CenterVertically,
	) {
		for (pageIndex in ordered) {
			val page = state.pages.getOrNull(pageIndex) ?: continue
			ReaderPageImage(
				page = page,
				headers = state.imageHeaders,
				contentScale = state.scale.toContentScale(),
				chrome = chrome,
				modifier = Modifier.weight(1f).fillMaxSize(),
			)
		}
	}

	// Keep the view model's notion of the current page in step with the spread, so a double-page
	// turn advances by two and the saved position is the page actually being read.
	LaunchedEffect(spreadIndex) {
		if (spread.first != state.currentPage) onPageChange(spread.first)
	}
}

/**
 * Webtoon mode: one continuous vertical strip, no gaps between pages.
 *
 * A `LazyColumn` rather than a custom layout, and **this has now been measured** rather than
 * argued. The risk flagged in `docs/ARCHITECTURE.md` 1.4 was that lazy lists cannot handle a strip
 * of 200 very tall images. `:app:desktop:webtoonProfile` scrolls exactly that -- 200 pages of
 * 800x2400, end to end, through this composable -- and reports p50 1.8ms, p95 4.6ms, no frame over
 * the 60Hz budget, and a heap that peaks at 25MB and returns to its starting size. Retaining all
 * 200 decoded would be well over a gigabyte, so the lazy list is doing the thing it was chosen
 * for: items that leave the viewport are disposed and Coil releases their bitmaps with them,
 * keeping the decoded set proportional to the window rather than to the chapter. A custom layout
 * is not needed.
 *
 * Those numbers are around five times better than the same profile before the strip was width-
 * capped, and the reason is worth keeping: drawing an 800px page into a 1000px window was an
 * upscale on every frame. Not enlarging it is both the sharper picture and the cheaper one.
 *
 * Three things about it are not the obvious choice:
 *
 *  - **The strip is a centred column of the source's own width, not the whole window.** A webtoon
 *    page is around 800px; filling a 1920px window with it is a 2.4x upscale of somebody's line
 *    art. So the *default* is never to enlarge past the source -- but Ctrl+wheel then goes as far
 *    past it as you like, and the strip scrolls sideways once it is wider than the window. An
 *    earlier version clamped the width to the window instead, which quietly broke the control it
 *    was meant to provide: with a source page already as wide as the window, several notches in
 *    either direction resolved to the same width and appeared to do nothing.
 *  - **Ctrl+wheel changes that width rather than scaling the strip.** A `graphicsLayer` scale on a
 *    lazy list scales its *viewport* too, so the edges get clipped and one notch of wheel travels
 *    a different distance at every zoom level. Re-measuring the column has neither problem.
 *  - **Every item reserves its height before its image arrives**, from an aspect ratio learned as
 *    the chapter decodes. Without it an unloaded page is zero pixels tall, the list composes a
 *    long run of them at once, and the strip lurches every time one resolves.
 */
@Composable
private fun WebtoonReader(
	state: ReaderUiState,
	chrome: ReaderChrome,
	background: ReaderBackground,
	zoom: Float,
	onScroll: (page: Int, fraction: Float) -> Unit,
	onZoom: (Float) -> Unit,
	onToggleChrome: () -> Unit,
) {
	val listState = rememberLazyListState()
	val across = rememberScrollState()
	val scope = rememberCoroutineScope()
	val smooth = rememberSmoothScroller(listState, scope)
	val density = LocalDensity.current

	// What the chapter has told us about itself so far. Keyed on the chapter, because the next one
	// may well come from a source that publishes at a different size.
	val ratios = remember(state.chapter?.id) { mutableStateMapOf<String, Float>() }
	var sourceWidth by remember(state.chapter?.id) { mutableStateOf<Dp?>(null) }
	var chapterRatio by remember(state.chapter?.id) { mutableStateOf(DEFAULT_PAGE_RATIO) }

	// Restore where the reader was, exactly. The brief calls this non-negotiable, and for a
	// webtoon the page index alone is not a position -- a page can be twelve thousand pixels tall.
	LaunchedEffect(state.chapter?.id, state.pageCount) {
		if (state.pageCount > 0 && state.currentPage > 0) {
			listState.scrollToItem(state.currentPage)
		}
	}

	LaunchedEffect(listState) {
		snapshotFlow {
			val info = listState.layoutInfo.visibleItemsInfo.firstOrNull()
			val fraction = if (info == null || info.size == 0) {
				0f
			} else {
				(-info.offset).toFloat() / info.size
			}
			listState.firstVisibleItemIndex to fraction.coerceIn(0f, 1f)
		}
			.distinctUntilChanged()
			.collect { (page, fraction) -> onScroll(page, fraction) }
	}

	// Read through `rememberUpdatedState` because the wheel handler below is installed once and
	// must not close over the zoom level it happened to see on first composition.
	val currentZoom by rememberUpdatedState(zoom)
	val currentOnZoom by rememberUpdatedState(onZoom)

	BoxWithConstraints(
		Modifier
			.fillMaxSize()
			.background(background.color)
			// Initial pass, on the parent of the list: this is the whole reason Ctrl+wheel now
			// zooms. Read on the Main pass -- where the old code read it -- the LazyColumn's own
			// scrollable has already consumed the event, so Ctrl+wheel scrolled instead.
			.pointerInput(density) {
				val notch = with(density) { WHEEL_NOTCH.toPx() }
				awaitPointerEventScope {
					while (true) {
						val event = awaitPointerEvent(PointerEventPass.Initial)
						if (event.type != PointerEventType.Scroll) continue
						val change = event.changes.firstOrNull() ?: continue
						val delta = change.scrollDelta.y
						val sideways = change.scrollDelta.x
						if (delta == 0f && sideways == 0f) continue
						when {
							event.keyboardModifiers.isCtrlPressed -> {
								val step = if (delta < 0) WEBTOON_ZOOM_STEP else 1f / WEBTOON_ZOOM_STEP
								currentOnZoom((currentZoom * step).coerceIn(MIN_WEBTOON_ZOOM, MAX_WEBTOON_ZOOM))
							}
							// Shift+wheel, and a genuine horizontal wheel, walk across a strip that
							// is wider than the window. Without this, zooming in far enough to read
							// small text would strand the edges of the page off screen.
							event.keyboardModifiers.isShiftPressed || sideways != 0f -> {
								val acrossDelta = if (sideways != 0f) sideways else delta
								scope.launch { across.scrollBy(acrossDelta * notch) }
							}
							else -> smooth.scrollBy(delta * notch)
						}
						change.consume()
					}
				}
			}
			.pointerInput(Unit) { detectTapGestures(onTap = { onToggleChrome() }) },
	) {
		val viewport = maxWidth
		val natural = sourceWidth ?: viewport.coerceAtMost(FALLBACK_STRIP_WIDTH)
		val stripWidth = webtoonStripWidth(natural, zoom)

		// A strip wider than the window scrolls sideways rather than being cropped to it. When it
		// is narrower, the frame stays exactly the width of the window so the column sits centred
		// and there is nothing to scroll across.
		Box(Modifier.fillMaxSize().horizontalScroll(across)) {
			LazyColumn(
				state = listState,
				modifier = Modifier.width(maxOf(stripWidth, viewport)).fillMaxHeight(),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(0.dp),
			) {
				items(state.pages, key = { it.key }) { page ->
					ReaderPageImage(
						page = page,
						headers = state.imageHeaders,
						// Width-filling is the only scale that makes sense for a continuous strip:
						// the whole point is that the reader scrolls rather than fits.
						contentScale = ContentScale.FillWidth,
						chrome = chrome,
						modifier = Modifier
							.width(stripWidth)
							.aspectRatio(ratios[page.key] ?: chapterRatio),
						onDecoded = { width, height ->
							if (height > 0) {
								ratios[page.key] = width.toFloat() / height
								// The first page to decode sets the strip's natural width, and the
								// placeholder shape for every page still on its way.
								if (sourceWidth == null) {
									sourceWidth = with(density) { width.toDp() }
									chapterRatio = width.toFloat() / height
								}
							}
						},
					)
				}
			}
		}
	}
}

@Composable
private fun ReaderPageImage(
	page: ReaderPage,
	headers: Map<String, String>,
	contentScale: ContentScale,
	/**
	 * The reader's own inks, rather than a fixed grey.
	 *
	 * These two placeholders used to be `Color(0xFF9A9A9A)` written in place -- the only literal
	 * hex left outside the design system, and wrong for a second reason besides rule 7: a mid grey
	 * chosen against a black background is barely legible on Paper and invisible on White, which
	 * are two of the four backgrounds this screen offers.
	 */
	chrome: ReaderChrome,
	modifier: Modifier = Modifier,
	onDecoded: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
	val failure = page.failure
	val url = page.resolvedUrl
	when {
		failure != null -> Box(modifier, Alignment.Center) {
			// One page failing is not the chapter failing. A placeholder keeps the page count and
			// the reading position honest rather than silently renumbering everything after it.
			Text(
				"Page ${page.index + 1} could not be loaded",
				style = AgehaTextStyles.metadata,
				color = chrome.subdued,
			)
		}

		url == null -> Box(modifier, Alignment.Center) {
			CircularProgressIndicator(Modifier.size(28.dp), color = chrome.subdued)
		}

		else -> AsyncImage(
			// `readerRequest`, not `request`: pages are decoded at the source's own resolution, so
			// that zooming reveals detail instead of magnifying a viewport-sized raster. See
			// `AgehaImages.readerRequest` for why the default does the wrong thing here.
			model = AgehaImages.readerRequest(url, headers),
			contentDescription = "Page ${page.index + 1}",
			contentScale = contentScale,
			// The other half of that fix, and `Medium` rather than `High` for a measured reason.
			//
			// The default `Low` is bilinear with *no mipmaps*, which aliases a full-resolution page
			// straight back into the mess the decode change was meant to avoid. `Medium` is
			// mipmapped bilinear: Skia builds the mip chain once per bitmap and caches it, which is
			// exactly what a downscale wants. `High` is a per-draw cubic resample with no caching,
			// and `:app:desktop:webtoonProfile` prices it at around 7ms of every frame -- it took
			// p50 from 1.8ms to 9ms and put 355 of 600 frames over the 60Hz budget, for a
			// difference nobody can see on a page that is already being drawn near 1:1.
			filterQuality = FilterQuality.Medium,
			onSuccess = { onDecoded(it.result.image.width, it.result.image.height) },
			modifier = modifier,
		)
	}
}

/**
 * The image half of "Preload next pages".
 *
 * `ReaderViewModel` resolves page *urls* ahead of the reader; this turns those urls into warm
 * cache entries, which is the half a reader actually feels. Without it the next page starts
 * downloading at the moment it is asked for, and the depth setting would only shorten the time
 * spent waiting for a redirect rather than for the image.
 *
 * Three things keep it from being a download-the-internet button:
 *
 *  - **Only pages whose url is already resolved.** Resolution is bounded by the same setting, so
 *    this can never run ahead of it and start guessing.
 *  - **Forward only.** Going back a page is served by the memory cache, which still holds what was
 *    just read; spending a request to re-warm it would be work done to avoid work already done.
 *  - **Enqueued, not awaited.** `enqueue` hands the request to Coil's own dispatcher and returns,
 *    so a slow source cannot stall the page the reader is looking at.
 */
@Composable
private fun PreloadPages(state: ReaderUiState, preloadPages: Int, loader: ImageLoader?) {
	if (loader == null) return
	LaunchedEffect(state.currentPage, state.pages, preloadPages, state.imageHeaders, loader) {
		val depth = if (preloadPages <= 0) state.pages.size else preloadPages
		// Best-effort, and swallowing the failure is the point rather than a shortcut. Warming a
		// cache is not something the reader depends on: if a prefetch cannot be enqueued the page
		// still loads when it is asked for, a moment later. Letting that throw would take down the
		// screen someone is reading to save them a wait they would not have noticed.
		runCatching {
			for (offset in 1..depth) {
				val page = state.pages.getOrNull(state.currentPage + offset) ?: break
				val url = page.resolvedUrl ?: continue
				loader.enqueue(AgehaImages.readerRequest(url, state.imageHeaders))
			}
		}
	}
}

/**
 * The default preload depth, matched to `Preferences.preloadPages`.
 *
 * Stated here as well so the three callers that do not pass one -- the headless render, the
 * webtoon profile and the status-bar test -- behave like the running application rather than
 * silently preloading nothing.
 */
private const val DEFAULT_PRELOAD_PAGES = 6

@Composable
private fun ReaderTopBar(
	state: ReaderUiState,
	chrome: ReaderChrome,
	background: ReaderBackground,
	doublePage: Boolean,
	coverOffset: Boolean,
	webtoonZoom: Float,
	onSetMode: (ReaderMode) -> Unit,
	onSetScale: (PageScale) -> Unit,
	onSetBackground: (ReaderBackground) -> Unit,
	onToggleDoublePage: () -> Unit,
	onToggleCoverOffset: () -> Unit,
	onSetWebtoonZoom: (Float) -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier
			.padding(top = 16.dp, start = AgehaSpacing.lg, end = AgehaSpacing.lg)
			.widthIn(max = READER_PILL_MAX_WIDTH)
			.shadow(12.dp, MaterialTheme.shapes.large, clip = false)
			.clip(MaterialTheme.shapes.large)
			.background(chrome.scrim)
			.border(1.dp, chrome.subdued.copy(alpha = 0.30f), MaterialTheme.shapes.large)
			.padding(start = 18.dp, end = AgehaSpacing.md, top = 11.dp, bottom = 11.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(15.dp),
	) {
		Column {
			Text(
				state.manga?.title.orEmpty(),
				style = MaterialTheme.typography.titleMedium,
				color = chrome.content,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				state.chapter?.title ?: "Chapter ${state.chapterIndex + 1}",
				style = AgehaTextStyles.monoMeta,
				color = chrome.subdued,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		// Only once there is a page list to describe.
		//
		// The handoff's pill always carries this block, because its prototype has no loading state.
		// Ageha does: a chapter opened from a live source has no pages for as long as the source
		// takes to answer, and a progress block drawn then reads "1 / 1 - 100% - 0 left" with total
		// confidence. `ReaderStatusBarTest` exists because that shipped once already. The rest of
		// the pill stays up, because Close and the mode chips are exactly what someone wants while
		// a slow chapter is still loading.
		if (state.pages.isNotEmpty()) {
			PillDivider(chrome)
			ReaderProgressBlock(state, chrome)
		}
		Spacer(Modifier.weight(1f))
		EnumMenu("Mode", ReaderMode.entries, state.mode, chrome, { it.name.lowercase() }, onSetMode)
		EnumMenu("Fit", PageScale.entries, state.scale, chrome, { it.label }, onSetScale)
		EnumMenu("Background", ReaderBackground.entries, background, chrome, { it.label }, onSetBackground)
		if (state.mode.isPaged) {
			EnumMenu("Pages", DOUBLE_PAGE_OPTIONS, doublePage, chrome, { if (it) "double" else "single" }) {
				onToggleDoublePage()
			}
			if (doublePage) {
				EnumMenu("Offset", DOUBLE_PAGE_OPTIONS, coverOffset, chrome, { if (it) "on" else "off" }) {
					onToggleCoverOffset()
				}
			}
		} else {
			// Both a readout and the way anyone finds out Ctrl+wheel does anything. Clicking it
			// returns the strip to the source's own width, which is the one width that needs no
			// explanation.
			ReaderChip(
				label = "Width",
				value = "${(webtoonZoom * 100).roundToInt()}%",
				chrome = chrome,
				onClick = { onSetWebtoonZoom(1f) },
			)
		}
		PillDivider(chrome)
		// Close is the handoff's tinted button, drained of its tint.
		//
		// The handoff fills it with `--accent-soft` over `--accent-line`. Both are brand colour and
		// this sits inside the reader, where CLAUDE.md rule 8 says nothing brand-coloured may go --
		// `ReaderNeutralityTest` measures every reader colour for hue and would fail the build. The
		// handoff loses, and is said to be losing rather than quietly bent. What survives is the
		// shape and the weight: a filled, bordered button at the right end, still the only filled
		// control in the pill, so it still reads as the way out.
		Row(
			Modifier
				.clip(MaterialTheme.shapes.medium)
				.background(chrome.content.copy(alpha = 0.12f))
				.border(1.dp, chrome.content.copy(alpha = 0.35f), MaterialTheme.shapes.medium)
				.clickable(onClick = onClose)
				.padding(horizontal = AgehaSpacing.md, vertical = AgehaSpacing.sm),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			Text("Close", style = MaterialTheme.typography.labelMedium, color = chrome.content)
			Canvas(Modifier.size(9.dp)) {
				val w = 1.5.dp.toPx()
				drawLine(chrome.content, Offset(0f, 0f), Offset(size.width, size.height), w, StrokeCap.Round)
				drawLine(chrome.content, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
			}
		}
	}
}

/**
 * The two-state options behind the Pages and Offset chips.
 *
 * A list of booleans rather than a bespoke toggle, so these read as the same control as Mode, Fit
 * and Background instead of as two odd buttons wearing different clothes in the same pill.
 */
private val DOUBLE_PAGE_OPTIONS = listOf(false, true)

/** The handoff's 1x34 rule between groups in the control pill. */
@Composable
private fun PillDivider(chrome: ReaderChrome) {
	Box(Modifier.width(1.dp).height(34.dp).background(chrome.subdued.copy(alpha = 0.35f)))
}

/**
 * Where you are in the chapter: the count, the proportion, what is left, and a bar.
 *
 * All four, in 192dp, and they are not redundant. The count is the position, the percentage is the
 * proportion, "8 left" is the *decision* -- finish this now or stop -- and the bar is the one you
 * read without reading. The handoff groups them exactly this way and is right to.
 *
 * Neutral throughout, where the handoff colours the percentage and the fill with the accent. Same
 * reason as the Close button above.
 */
@Composable
private fun ReaderProgressBlock(state: ReaderUiState, chrome: ReaderChrome) {
	val total = state.pageCount.coerceAtLeast(1)
	val page = (state.currentPage + 1).coerceIn(1, total)
	val fraction = page.toFloat() / total
	Column(Modifier.width(192.dp), verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs)) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			Text(
				"$page / $total",
				style = AgehaTextStyles.readerHud,
				color = chrome.content,
				modifier = Modifier.testTag(PAGE_COUNTER_TAG),
			)
			Text("${(fraction * 100).roundToInt()}%", style = AgehaTextStyles.monoMeta, color = chrome.content)
			Text("${total - page} left", style = AgehaTextStyles.monoMeta, color = chrome.subdued)
		}
		Box(
			Modifier
				.fillMaxWidth()
				.height(3.dp)
				.clip(MaterialTheme.shapes.extraSmall)
				.background(chrome.content.copy(alpha = 0.18f)),
		) {
			Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(chrome.content))
		}
	}
}

/** A MODE / FIT / BACKGROUND chip: a mono label, the value, and a caret. */
@Composable
private fun ReaderChip(
	label: String,
	value: String,
	chrome: ReaderChrome,
	isOpen: Boolean = false,
	onClick: () -> Unit,
) {
	val shape = MaterialTheme.shapes.medium
	Row(
		Modifier
			.clip(shape)
			.background(chrome.content.copy(alpha = if (isOpen) 0.16f else 0.07f))
			.border(1.dp, chrome.content.copy(alpha = if (isOpen) 0.35f else 0.16f), shape)
			.clickable(onClick = onClick)
			.padding(horizontal = AgehaSpacing.md, vertical = AgehaSpacing.sm),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Text(label.uppercase(), style = AgehaTextStyles.monoControl, color = chrome.subdued)
		Text(value, style = MaterialTheme.typography.labelMedium, color = chrome.content)
		// The caret flips when the menu is open, which is the only feedback saying which of three
		// identical-looking chips the list below belongs to.
		Canvas(Modifier.size(7.dp)) {
			val w = 1.4.dp.toPx()
			val from = if (isOpen) size.height else 0f
			val to = if (isOpen) 0f else size.height
			drawLine(chrome.subdued, Offset(0f, from), Offset(size.width / 2f, to), w, StrokeCap.Round)
			drawLine(chrome.subdued, Offset(size.width, from), Offset(size.width / 2f, to), w, StrokeCap.Round)
		}
	}
}

/** The handoff's 1200px control pill, as a ceiling rather than a fixed width. */
private val READER_PILL_MAX_WIDTH = 1200.dp

@Composable
private fun <T> EnumMenu(
	label: String,
	options: List<T>,
	selected: T,
	chrome: ReaderChrome,
	name: (T) -> String,
	onSelect: (T) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	Box {
		ReaderChip(
			label = label,
			value = name(selected),
			chrome = chrome,
			isOpen = expanded,
			onClick = { expanded = !expanded },
		)
		// A real menu, not a cycler.
		//
		// The handoff says these chips cycle on click, which is fine for three options in a
		// prototype and wrong here: Mode has three, Fit has three, Background has three, and
		// cycling means up to two wrong states are rendered -- full-page redraws of somebody's
		// artwork -- on the way to the one that was wanted. A list also *shows* the options, which
		// a cycler never does.
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			for (option in options) {
				DropdownMenuItem(
					text = { Text(name(option)) },
					onClick = {
						onSelect(option)
						expanded = false
					},
				)
			}
		}
	}
}

@Composable
private fun ReaderStatusBar(
	state: ReaderUiState,
	chrome: ReaderChrome,
	onSeek: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier
			.padding(bottom = 18.dp)
			.shadow(10.dp, AgehaTheme.skin.pill, clip = false)
			.clip(AgehaTheme.skin.pill)
			.background(chrome.scrim)
			.border(1.dp, chrome.subdued.copy(alpha = 0.25f), AgehaTheme.skin.pill)
			.padding(horizontal = AgehaSpacing.lg, vertical = 9.dp),
		horizontalArrangement = Arrangement.spacedBy(14.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			"Ch ${state.chapterIndex + 1} of ${state.chapterCount}",
			style = AgehaTextStyles.monoMeta,
			color = chrome.subdued,
		)
		PageTicks(state, chrome, onSeek)
		Text(
			"${state.pageCount} pages",
			style = AgehaTextStyles.monoMeta,
			color = chrome.subdued,
		)
	}
}

/**
 * A page as a tick, and the chapter as a row of them.
 *
 * ## Why this is worth the space a plain "14 / 22" would not need
 *
 * It answers a different question. The counter says where you are; the ticks say *how much is
 * left*, at a glance, without arithmetic -- and they are clickable, so the shape of the chapter is
 * also the way to move around inside it. That is the one navigation a reader wants that neither a
 * counter nor a scrollbar gives.
 *
 * Read, current and unread differ in **height** as well as tone, which is what keeps the row
 * readable in a reader whose whole palette is three greys. The current tick is nearly twice the
 * height of an unread one; on tone alone the position would be almost impossible to find.
 *
 * ## Why it stops at a cap
 *
 * A 60-page chapter would draw 60 ticks and a 200-page one would draw a smear. Past [MAX_TICKS] the
 * row samples evenly instead, so it stays a shape you can read rather than a texture -- and each
 * tick still seeks to a real page, just not to every page.
 */
@Composable
private fun PageTicks(state: ReaderUiState, chrome: ReaderChrome, onSeek: (Int) -> Unit) {
	val total = state.pageCount
	if (total <= 1) return
	val shown = minOf(total, MAX_TICKS)
	Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
		for (slot in 0 until shown) {
			// Which real page this tick stands for. Identity while under the cap; an even sample
			// above it.
			val page = if (shown == total) slot else (slot.toFloat() / (shown - 1) * (total - 1)).roundToInt()
			val isCurrent = page == state.currentPage
			val isRead = page < state.currentPage
			Box(
				Modifier
					.width(6.dp)
					.height(if (isCurrent) 16.dp else 9.dp)
					.clip(MaterialTheme.shapes.extraSmall)
					.background(
						when {
							isCurrent -> chrome.content
							isRead -> chrome.content.copy(alpha = 0.45f)
							else -> chrome.subdued.copy(alpha = 0.30f)
						},
					)
					.clickable { onSeek(page) },
			)
		}
	}
}

/**
 * How many ticks the chapter pill will draw before it starts sampling.
 *
 * 40 is roughly where 6dp ticks and 3dp gaps stop fitting beside the two labels on a 1280px
 * window. Chapters longer than this are common in webtoons, which is exactly where the row would
 * otherwise become a solid bar.
 */
private const val MAX_TICKS = 40

/**
 * Hides the chrome after a period with no input.
 *
 * Restarted by [activity] changing, so any pointer movement brings it back and resets the clock.
 * Auto-hide with no way to bring it back is worse than no auto-hide at all.
 *
 * [suppressed] holds the clock at zero rather than merely pausing it, and the caller uses it for
 * two things: "the pointer is over the bar, they are reading it", and "the bar has only just
 * appeared and the pointer has not moved since". The second is what the old version got wrong --
 * it hid a bar that a click had deliberately revealed, while its owner was still moving towards
 * it.
 */
@Composable
fun AutoHideChrome(
	activity: Any?,
	isVisible: Boolean,
	onHide: () -> Unit,
	suppressed: Boolean = false,
) {
	LaunchedEffect(activity, isVisible, suppressed) {
		if (!isVisible || suppressed) return@LaunchedEffect
		delay(CHROME_IDLE_MS)
		onHide()
	}
}

/** How long the chrome stays up after the last interaction. */
private const val CHROME_IDLE_MS = 2_500L

/**
 * Strip width before any page has decoded.
 *
 * Shown only for the moment before the first page arrives and replaces it with the source's real
 * width, so it is chosen to be an unremarkable reading width rather than to be right about any
 * particular source.
 */
private val FALLBACK_STRIP_WIDTH = 900.dp

/**
 * How wide the webtoon strip is drawn, for a source page [natural] wide at a given [zoom].
 *
 * **The window width is not a parameter, and that is the point.** Clamping this to the viewport was
 * the obvious thing to do and it broke the control it was meant to provide, in two ways. It made
 * Ctrl+wheel useless for what anyone actually reaches for it -- getting close enough to read small
 * text -- and, whenever the source page was already at least as wide as the window, it collapsed a
 * whole range of zoom levels onto the same width, so several notches in either direction visibly
 * did nothing. Zoom ran out before it started.
 *
 * Past the window width the strip scrolls sideways instead of stopping, so every step of the range
 * does something. [WebtoonStripWidthTest] pins that.
 */
fun webtoonStripWidth(natural: Dp, zoom: Float): Dp = (natural * zoom).coerceAtLeast(MIN_STRIP_WIDTH)

/** A floor on the absolute width, so zooming all the way out cannot leave a strip of nothing. */
private val MIN_STRIP_WIDTH = 160.dp

/** Aspect ratio (width / height) assumed for a page nothing is yet known about. */
private const val DEFAULT_PAGE_RATIO = 0.7f

/**
 * One Ctrl+wheel notch of strip width.
 *
 * 1.15 rather than something finer because this is a control someone reaches for when they cannot
 * read something -- it has to visibly respond to one notch. Going from the source's own width to
 * twice it takes five notches.
 */
private const val WEBTOON_ZOOM_STEP = 1.15f

/** Far enough out to see the shape of a page; there is no reason to go further. */
private const val MIN_WEBTOON_ZOOM = 0.25f

/**
 * Far enough in to read a translator's note set in six-point type.
 *
 * Real magnification, not a number that gets clamped away: past the window width the strip scrolls
 * sideways rather than stopping, so every step of this range does something.
 */
private const val MAX_WEBTOON_ZOOM = 6f

/**
 * How far one wheel notch scrolls the strip.
 *
 * Matched to what Compose Desktop's own scrollable does with a notch, deliberately: the smooth
 * scroller changes *when* those pixels arrive, and it must not quietly change how many. Calibrated
 * against `:app:desktop:webtoonProfile`, which scrolls a known 200-page strip end to end and fails
 * if the far end is never reached -- at 110.dp it stopped at page 165, which is exactly the sort of
 * "everything feels slower now" regression that is impossible to argue about without a number.
 */
private val WHEEL_NOTCH = 165.dp

/**
 * Test tag for the end-to-end journey driver.
 *
 * This counter is the single thing that says where the reader actually is, so it is what the
 * driver asserts on after a resume. Its text is derived state, not a fixed string, which is why it
 * needs a tag rather than a text finder.
 */
const val PAGE_COUNTER_TAG = "page-counter"
