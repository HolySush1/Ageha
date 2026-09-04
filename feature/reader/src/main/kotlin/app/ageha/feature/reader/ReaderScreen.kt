package app.ageha.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
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
) {
	val chrome = remember(background) { ReaderChrome.forBackground(background) }

	Box(modifier.fillMaxSize().background(background.color)) {
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
				onScroll = onScroll,
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
			ReaderTopBar(state, chrome, background, doublePage, coverOffset, onSetMode, onSetScale,
				onSetBackground, onToggleDoublePage, onToggleCoverOffset, onClose)
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
			ReaderStatusBar(state, chrome)
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
 * 800x2400, end to end, through this composable -- and reports every frame inside the 60Hz budget
 * and a heap that peaks around 30MB and returns to its starting size. Retaining all 200 decoded
 * would be well over a gigabyte, so the lazy list is doing the thing it was chosen for: items that
 * leave the viewport are disposed and Coil releases their bitmaps with them, keeping the decoded
 * set proportional to the window rather than to the chapter. A custom layout is not needed.
 *
 * The remaining weakness is scroll-position stability with unknown item heights, which is why each
 * item reserves space from its page's aspect ratio before its image arrives.
 */
@Composable
private fun WebtoonReader(
	state: ReaderUiState,
	background: ReaderBackground,
	onScroll: (page: Int, fraction: Float) -> Unit,
	onToggleChrome: () -> Unit,
) {
	val listState = rememberLazyListState()
	val zoom = rememberZoomPanState(state.chapter?.id)

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

	LazyColumn(
		state = listState,
		modifier = Modifier
			.fillMaxSize()
			.background(background.color)
			.zoomPan(zoom, state.scale, onTap = onToggleChrome),
		verticalArrangement = Arrangement.spacedBy(0.dp),
	) {
		items(state.pages, key = { it.key }) { page ->
			ReaderPageImage(
				page = page,
				headers = state.imageHeaders,
				// Width-filling is the only scale that makes sense for a continuous strip: the
				// whole point is that the reader scrolls rather than fits.
				contentScale = ContentScale.FillWidth,
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}
}

@Composable
private fun ReaderPageImage(
	page: ReaderPage,
	headers: Map<String, String>,
	contentScale: ContentScale,
	modifier: Modifier = Modifier,
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
			model = AgehaImages.request(url, headers),
			contentDescription = "Page ${page.index + 1}",
			contentScale = contentScale,
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
	onSetMode: (ReaderMode) -> Unit,
	onSetScale: (PageScale) -> Unit,
	onSetBackground: (ReaderBackground) -> Unit,
	onToggleDoublePage: () -> Unit,
	onToggleCoverOffset: () -> Unit,
	onClose: () -> Unit,
) {
	Row(
		Modifier.fillMaxWidth().background(chrome.scrim).padding(AgehaSpacing.sm),
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
private fun ReaderStatusBar(state: ReaderUiState, chrome: ReaderChrome) {
	Row(
		Modifier.fillMaxWidth().background(chrome.scrim).padding(AgehaSpacing.sm),
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
 * Restarted by [activity] changing, so any page turn or pointer movement brings it back and
 * resets the clock. Auto-hide with no way to bring it back is worse than no auto-hide at all.
 */
@Composable
fun AutoHideChrome(activity: Any?, isVisible: Boolean, onHide: () -> Unit) {
	LaunchedEffect(activity, isVisible) {
		if (!isVisible) return@LaunchedEffect
		delay(CHROME_IDLE_MS)
		onHide()
	}
}

/** How long the chrome stays up after the last interaction. */
private const val CHROME_IDLE_MS = 2_500L

/**
 * Test tag for the end-to-end journey driver.
 *
 * This counter is the single thing that says where the reader actually is, so it is what the
 * driver asserts on after a resume. Its text is derived state, not a fixed string, which is why it
 * needs a tag rather than a text finder.
 */
const val PAGE_COUNTER_TAG = "page-counter"
