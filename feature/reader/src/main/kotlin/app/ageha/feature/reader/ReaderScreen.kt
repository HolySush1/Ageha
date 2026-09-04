package app.ageha.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.ReaderBackground
import app.ageha.core.designsystem.ReaderChrome
import app.ageha.core.designsystem.SourceFailureNotice
import app.ageha.core.image.AgehaImages
import app.ageha.core.model.PageScale
import app.ageha.core.model.ReaderMode
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
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
) {
	val chrome = remember(background) { ReaderChrome.forBackground(background) }

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
				background = background,
				zoom = webtoonZoom,
				onScroll = onScroll,
				onZoom = onSetWebtoonZoom,
				onToggleChrome = onToggleChrome,
			)

			else -> PagedReader(
				state = state,
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
			ReaderStatusBar(state, chrome, Modifier.hoverable(chromeHover))
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
 *    art. So the default is never to enlarge past the source, and Ctrl+wheel is how you say
 *    otherwise.
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
	background: ReaderBackground,
	zoom: Float,
	onScroll: (page: Int, fraction: Float) -> Unit,
	onZoom: (Float) -> Unit,
	onToggleChrome: () -> Unit,
) {
	val listState = rememberLazyListState()
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
						if (delta == 0f) continue
						if (event.keyboardModifiers.isCtrlPressed) {
							val step = if (delta < 0) WEBTOON_ZOOM_STEP else 1f / WEBTOON_ZOOM_STEP
							currentOnZoom((currentZoom * step).coerceIn(MIN_WEBTOON_ZOOM, MAX_WEBTOON_ZOOM))
						} else {
							smooth.scrollBy(delta * notch)
						}
						change.consume()
					}
				}
			}
			.pointerInput(Unit) { detectTapGestures(onTap = { onToggleChrome() }) },
	) {
		val viewport = maxWidth
		// Never wider than the window: a strip you have to scroll sideways to read is not a
		// webtoon reader, so zooming past the window edge simply stops.
		val natural = sourceWidth ?: viewport.coerceAtMost(FALLBACK_STRIP_WIDTH)
		val stripWidth = (natural * zoom).coerceIn(MIN_STRIP_WIDTH.coerceAtMost(viewport), viewport)

		LazyColumn(
			state = listState,
			modifier = Modifier.fillMaxSize(),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(0.dp),
		) {
			items(state.pages, key = { it.key }) { page ->
				ReaderPageImage(
					page = page,
					headers = state.imageHeaders,
					// Width-filling is the only scale that makes sense for a continuous strip: the
					// whole point is that the reader scrolls rather than fits.
					contentScale = ContentScale.FillWidth,
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

@Composable
private fun ReaderPageImage(
	page: ReaderPage,
	headers: Map<String, String>,
	contentScale: ContentScale,
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
				color = androidx.compose.ui.graphics.Color(0xFF9A9A9A),
			)
		}

		url == null -> Box(modifier, Alignment.Center) {
			CircularProgressIndicator(Modifier.size(28.dp), color = androidx.compose.ui.graphics.Color(0xFF9A9A9A))
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
		modifier.fillMaxWidth().background(chrome.scrim).padding(AgehaSpacing.sm),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
	) {
		TextButton(onClick = onClose) { Text("Close", color = chrome.content) }
		Column(Modifier.weight(1f)) {
			Text(
				state.manga?.title.orEmpty(),
				style = AgehaTextStyles.readerHud,
				color = chrome.content,
				maxLines = 1,
			)
			Text(
				state.chapter?.title ?: "Chapter ${state.chapterIndex + 1}",
				style = AgehaTextStyles.metadata,
				color = chrome.subdued,
				maxLines = 1,
			)
		}
		EnumMenu("Mode", ReaderMode.entries, state.mode, chrome, { it.name.lowercase() }, onSetMode)
		EnumMenu("Fit", PageScale.entries, state.scale, chrome, { it.label }, onSetScale)
		EnumMenu("Background", ReaderBackground.entries, background, chrome, { it.label }, onSetBackground)
		if (state.mode.isPaged) {
			TextButton(onClick = onToggleDoublePage) {
				Text(if (doublePage) "Two pages" else "One page", color = chrome.content)
			}
			if (doublePage) {
				TextButton(onClick = onToggleCoverOffset) {
					Text(if (coverOffset) "Cover offset on" else "Cover offset off", color = chrome.content)
				}
			}
		} else {
			// Both a readout and the way anyone finds out Ctrl+wheel does anything. Clicking it
			// returns the strip to the source's own width, which is the one width that needs no
			// explanation.
			TextButton(onClick = { onSetWebtoonZoom(1f) }) {
				Text("Width: ${(webtoonZoom * 100).roundToInt()}%", color = chrome.content)
			}
		}
	}
}

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
		TextButton(onClick = { expanded = true }) {
			Text("$label: ${name(selected)}", color = chrome.content)
		}
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			for (option in options) {
				DropdownMenuItem(
					text = { Text(name(option)) },
					onClick = { onSelect(option); expanded = false },
				)
			}
		}
	}
}

@Composable
private fun ReaderStatusBar(
	state: ReaderUiState,
	chrome: ReaderChrome,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier.fillMaxWidth().background(chrome.scrim).padding(AgehaSpacing.sm),
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// Tabular figures, so the counter does not reflow as the page number ticks past 9.
		Text(
			"${state.currentPage + 1} / ${state.pageCount}",
			style = AgehaTextStyles.readerHud,
			color = chrome.content,
			modifier = Modifier.testTag(PAGE_COUNTER_TAG),
		)
		Text(
			"Chapter ${state.chapterIndex + 1} of ${state.chapterCount}",
			style = AgehaTextStyles.metadata,
			color = chrome.subdued,
		)
	}
}

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

/** Narrow enough to be a deliberate choice, wide enough to still be reading rather than squinting. */
private val MIN_STRIP_WIDTH = 240.dp

/** Aspect ratio (width / height) assumed for a page nothing is yet known about. */
private const val DEFAULT_PAGE_RATIO = 0.7f

/** One Ctrl+wheel notch of strip width. */
private const val WEBTOON_ZOOM_STEP = 1.1f

private const val MIN_WEBTOON_ZOOM = 0.4f

/**
 * Zoom runs well past "fills the window" on purpose.
 *
 * The width is clamped to the viewport anyway, so this only bounds the stored number. Letting it
 * reach 4x means someone who widened the strip on a large monitor still has it filling the window
 * on a laptop, rather than having quietly lost the setting to a clamp.
 */
private const val MAX_WEBTOON_ZOOM = 4f

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
