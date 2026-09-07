package app.ageha.feature.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import app.ageha.core.data.SourceListing
import app.ageha.core.designsystem.AgehaAccent
import app.ageha.core.designsystem.AgehaGlass
import app.ageha.core.designsystem.GlassTone
import app.ageha.core.designsystem.AgehaSearchField
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import app.ageha.core.designsystem.AgehaChip
import app.ageha.core.designsystem.NoteBox
import app.ageha.core.designsystem.AgehaSwitch
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.StatusDot
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.EmptyState
import app.ageha.core.designsystem.glassSurface
import app.ageha.core.designsystem.MangaGrid
import app.ageha.core.designsystem.MangaGridItem
import app.ageha.core.designsystem.SourceFailureNotice
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaSortOrder

/**
 * The source picker.
 *
 * A list rather than a grid: sources have names and languages, not covers, and a grid of text
 * tiles is a grid for its own sake. Each row carries the switch that enables it, so turning
 * sources on is done from the same place they are found rather than in a separate settings screen.
 *
 * The header is where the catalogue becomes usable. 1360 sources in every language, some of them
 * broken and some of them pornographic, is not a list anyone can browse -- so the view chips pick
 * a slice, and the filter menu beside them narrows it by language, by whether upstream has flagged
 * the source broken, and by whether adult sources are shown at all. Whatever those filters remove
 * is counted out loud underneath, because a filtered list that does not say it is filtered is
 * indistinguishable from a catalogue that is missing things.
 */
@Composable
fun SourcePickerScreen(
	state: ExploreUiState,
	onOpenSource: (String) -> Unit,
	onSearch: (String) -> Unit,
	onFilter: (SourceFilter) -> Unit,
	onLocale: (String?) -> Unit,
	onHideBroken: (Boolean) -> Unit,
	onShowAdult: (Boolean) -> Unit,
	onSetEnabled: (String, Boolean) -> Unit,
	modifier: Modifier = Modifier,
	searchFocus: FocusRequester = remember { FocusRequester() },
) {
	Column(modifier.fillMaxSize()) {
		Column(
			Modifier
				.fillMaxWidth()
				.glassSurface(RoundedCornerShape(0.dp), GlassTone.CHROME)
				.padding(horizontal = AgehaSpacing.md, vertical = AgehaSpacing.sm),
			verticalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			Row(
				Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
				verticalAlignment = Alignment.CenterVertically,
			) {
				AgehaSearchField(
					value = state.query,
					onValueChange = onSearch,
					placeholder = "Search ${state.totalCount} sources",
					modifier = Modifier.weight(1f).focusRequester(searchFocus),
				)
				FilterMenu(state, onLocale, onHideBroken, onShowAdult)
			}
			Row(
				horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
				verticalAlignment = Alignment.CenterVertically,
			) {
				for (option in SourceFilter.entries) {
					AgehaChip(
						label = option.label,
						isSelected = state.filter == option,
						onClick = { onFilter(option) },
						// Counts come from the *unfiltered* set, as the handoff specifies. A tab
						// whose count already had the current filter applied would read zero on
						// the tab you are not looking at and tell you nothing about whether
						// switching to it is worth the click.
						count = when (option) {
							SourceFilter.ENABLED -> state.enabledCount.toString()
							SourceFilter.ALL -> state.totalCount.toString()
							SourceFilter.BROKEN -> null
						},
					)
				}
				Box(Modifier.weight(1f))
				// The handoff's `N SHOWN`, at the right end of the tab row: how many rows survived
				// the tab and the filters together. It is the one number that answers "why is this
				// list shorter than I expected", and it sits where the eye lands after reading the
				// tabs left to right.
				Text(
					"${state.sources.size} shown",
					style = AgehaTextStyles.monoEyebrow,
					color = AgehaTheme.skin.inkFaint,
				)
			}
			// Said out loud, every time. See ExploreUiState.hiddenAdultCount for why.
			//
			// A tinted note rather than another grey line, which is the handoff's treatment for
			// exactly this: a sentence about the screen's own state, where a plain paragraph gets
			// skipped and a warning colour would overstate it. Nothing has gone wrong; rows are
			// merely not being shown, and the user is the one who asked for that.
			if (state.hasHiddenSources) {
				NoteBox(
					listOfNotNull(
						state.hiddenAdultCount.takeIf { it > 0 }?.let { "$it 18+ hidden" },
						state.hiddenBrokenCount.takeIf { it > 0 }?.let { "$it known broken hidden" },
					).joinToString(" · ") + " by the current filters.",
					modifier = Modifier.testTag(HIDDEN_COUNT_TAG),
				)
			}
		}
		when {
			state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

			state.sources.isEmpty() && state.filter == SourceFilter.ENABLED && state.query.isEmpty() ->
				EmptyState(
					title = "No sources enabled",
					detail = "Ageha ships ${state.totalCount} sources and starts with all of them " +
						"off, so it only ever talks to sites you chose. Turn some on to begin.",
					action = { TextButton(onClick = { onFilter(SourceFilter.ALL) }) { Text("Show all sources") } },
				)

			// Found nothing here, but the full catalogue has it. Always the case on a fresh
			// installation, where nothing is enabled yet and so *every* search of the enabled
			// sources comes back empty -- and "no sources match" sends someone looking for a
			// source that is sitting right there, switched off.
			state.sources.isEmpty() && state.matchesInAllSources > 0 -> EmptyState(
				title = if (state.matchesInAllSources == 1) {
					"1 source matches, but it is not enabled"
				} else {
					"${state.matchesInAllSources} sources match, but none are enabled"
				},
				detail = "Ageha starts with every source off, so it only ever talks to sites you " +
					"chose. Show the full list to turn this one on.",
				action = { TextButton(onClick = { onFilter(SourceFilter.ALL) }) { Text("Show all sources") } },
			)

			// Nothing matches *here*, but a filter is withholding rows. Offering the search term
			// back without mentioning the filter would be the app hiding its own doing.
			state.sources.isEmpty() && state.hasHiddenSources -> EmptyState(
				title = "No sources match",
				detail = "Nothing here matches that search, and " +
					listOfNotNull(
						state.hiddenAdultCount.takeIf { it > 0 }?.let { "$it 18+ source(s)" },
						state.hiddenBrokenCount.takeIf { it > 0 }?.let { "$it known broken source(s)" },
					).joinToString(" and ") +
					" are hidden by your filters.",
			)

			state.sources.isEmpty() -> EmptyState(
				title = "No sources match",
				detail = "Nothing here matches that search and filter.",
			)

			else -> LazyColumn(Modifier.fillMaxSize()) {
				items(state.sources, key = { it.name }) { listing ->
					SourceRow(listing, onOpenSource, onSetEnabled)
				}
			}
		}
	}
}

/**
 * The three filters that are not a view: language, broken, and adult.
 *
 * A menu rather than three more chips in the bar. Chips are for the slice being shown and read as
 * one exclusive choice; these are independent switches that mostly stay where the user put them,
 * and putting six controls in a row makes the two that matter harder to find rather than easier.
 *
 * The button says how many are active, so a filtered catalogue is legible without opening it.
 */
@Composable
private fun FilterMenu(
	state: ExploreUiState,
	onLocale: (String?) -> Unit,
	onHideBroken: (Boolean) -> Unit,
	onShowAdult: (Boolean) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	val active = listOf(state.locale != null, state.hideBroken, state.showAdult).count { it }
	Box {
		TextButton(onClick = { expanded = true }, modifier = Modifier.testTag(FILTER_MENU_TAG)) {
			Text(if (active == 0) "Filters" else "Filters ($active)")
		}
		DropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			modifier = Modifier.glassSurface(MaterialTheme.shapes.medium, GlassTone.RAISED),
		) {
			ToggleItem(
				label = "Hide known broken",
				detail = "Sources upstream has flagged as not currently working.",
				checked = state.hideBroken,
				onToggle = { onHideBroken(!state.hideBroken) },
			)
			ToggleItem(
				label = "Show 18+ sources",
				detail = "Adult sources are hidden until you ask for them.",
				checked = state.showAdult,
				onToggle = { onShowAdult(!state.showAdult) },
				testTag = ADULT_TOGGLE_TAG,
			)
			HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
			DropdownMenuItem(
				text = { Text("Any language") },
				trailingIcon = { if (state.locale == null) Text("*", style = AgehaTextStyles.metadata) },
				onClick = { onLocale(null); expanded = false },
			)
			for (option in state.availableLocales) {
				DropdownMenuItem(
					text = { Text("${option.displayName} (${option.count})") },
					trailingIcon = {
						if (state.locale == option.tag) Text("*", style = AgehaTextStyles.metadata)
					},
					onClick = { onLocale(option.tag); expanded = false },
				)
			}
		}
	}
}

/**
 * A switch inside the filter menu.
 *
 * The whole row toggles, and the switch itself is passed a null handler so it renders as state
 * rather than as a second, smaller target sitting inside the first one.
 */
@Composable
private fun ToggleItem(
	label: String,
	detail: String,
	checked: Boolean,
	onToggle: () -> Unit,
	testTag: String? = null,
) {
	DropdownMenuItem(
		modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
		text = {
			Column {
				Text(label, style = MaterialTheme.typography.bodyMedium)
				Text(
					detail,
					style = AgehaTextStyles.metadata,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		trailingIcon = { Switch(checked = checked, onCheckedChange = null) },
		onClick = onToggle,
	)
}

@Composable
private fun SourceRow(
	listing: SourceListing,
	onOpen: (String) -> Unit,
	onSetEnabled: (String, Boolean) -> Unit,
) {
	val skin = AgehaTheme.skin
	Row(
		Modifier
			.fillMaxWidth()
			.testTag(SOURCE_ROW_TAG)
			.clickable(enabled = listing.isEnabled) { onOpen(listing.name) }
			.padding(horizontal = 18.dp, vertical = 15.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(15.dp),
	) {
		// The initial tile. Twelve hundred sources have no icons and never will -- they are
		// generated from a parser list, not curated -- so the row needs something with the weight
		// of an icon that costs no assets. A letter on a tinted square scans down a long list far
		// better than a column of text starting at the same x.
		Box(
			Modifier
				.size(36.dp)
				.clip(MaterialTheme.shapes.medium)
				.background(skin.inset),
			contentAlignment = Alignment.Center,
		) {
			Text(
				listing.title.take(1).uppercase(),
				style = AgehaTextStyles.monoData,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Column(Modifier.weight(1f)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
			) {
				Text(
					listing.title,
					style = MaterialTheme.typography.titleMedium,
					color = MaterialTheme.colorScheme.onSurface,
				)
				// Only reachable with the 18+ filter switched on, and marked anyway. Someone who
				// turned adult sources on still has to be able to tell which ones they are.
				if (listing.descriptor.isAdult) {
					AdultBadge()
				}
			}
			Text(
				listOfNotNull(
					listing.descriptor.locale?.uppercase(),
					listing.descriptor.contentType.name.lowercase().replace('_', ' '),
				).joinToString(" · "),
				style = AgehaTextStyles.monoMeta,
				color = skin.inkFaint,
			)
		}
		// Health, as a dot and a word. Ageha has no latency probe, so there is no Slow state to
		// report and inventing one would be a number with nothing behind it -- upstream's own
		// broken flag is what is actually known, and it is what this says.
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			StatusDot(if (listing.descriptor.isBroken) skin.accent else skin.ok)
			Text(
				if (listing.descriptor.isBroken) "Broken" else "Healthy",
				style = AgehaTextStyles.monoMeta,
				color = skin.inkFaint,
			)
		}
		AgehaSwitch(
			checked = listing.isEnabled,
			onCheckedChange = { onSetEnabled(listing.name, it) },
			modifier = Modifier.testTag(SOURCE_TOGGLE_TAG),
		)
	}
}

/** The handoff's `18+` cap: accent-soft over accent-line, in the accent's own ink. */
@Composable
private fun AdultBadge() {
	val skin = AgehaTheme.skin
	Box(
		Modifier
			.clip(skin.chip)
			.background(MaterialTheme.colorScheme.primaryContainer)
			.border(1.dp, skin.accentLine, skin.chip)
			.padding(horizontal = 5.dp, vertical = 1.dp),
	) {
		Text("18+", style = AgehaTextStyles.monoEyebrow, color = skin.accent)
	}
}

/*
 * Test tags for the end-to-end journey driver.
 *
 * A handful of rows in Ageha carry no fixed string to find them by -- a source row is a title
 * nobody can predict plus a locale plus an optional "known broken", and a chapter row is whatever
 * the source decided to call chapter one. Finding them by text means the driver breaks when a
 * source renames itself, which is a false alarm about the app rather than a real one.
 *
 * Public because the driver lives in :app:desktop and these are the contract between them.
 */
const val SOURCE_ROW_TAG = "source-row"
const val SOURCE_TOGGLE_TAG = "source-toggle"
const val FILTER_MENU_TAG = "source-filter-menu"
const val ADULT_TOGGLE_TAG = "source-adult-toggle"
const val HIDDEN_COUNT_TAG = "source-hidden-count"

/**
 * Browsing one source.
 *
 * The grid pages as it approaches the end rather than on a "load more" button, but the threshold
 * is deliberately small: every page is a request to somebody else's server, and prefetching four
 * screens ahead of a user who is about to close the window is rude in a way that gets an app
 * blocked.
 */
@Composable
fun BrowseScreen(
	state: BrowseUiState,
	onOpenManga: (AgehaManga) -> Unit,
	onSort: (AgehaSortOrder) -> Unit,
	onSearch: (String) -> Unit,
	onSubmitSearch: () -> Unit,
	onLoadMore: () -> Unit,
	onRetry: () -> Unit,
	modifier: Modifier = Modifier,
	searchFocus: FocusRequester = remember { FocusRequester() },
) {
	val gridState = rememberLazyGridState()
	// The state and the callback, always current, so the effect below can read them without being
	// restarted. This is the whole fix for a listing that stopped dead after its first page.
	//
	// It used to hoist a `derivedStateOf` out of `remember(state.manga.size, state.hasMore)` and
	// collect it from a `LaunchedEffect` keyed only on the source. Recomposition duly built a new
	// derived state for every page -- and the coroutine went on observing the *first* one, which
	// had closed over `manga.size == 0` and `hasMore == true` and so evaluated `last >= -8`: true,
	// permanently. `snapshotFlow` emits only on change, so it emitted `true` exactly once, during
	// the first page, when `loadMore` was already in flight and returned early. It never emitted
	// again. Every source in the app was one page deep, which is what the report described as
	// "only 20 things load".
	//
	// Reading the state inside the flow instead of capturing it fixes that at the root: the
	// predicate is re-evaluated whenever the grid scrolls *or* the state changes, and the guards
	// the view model applies are mirrored here, so the flow goes false while a page is in flight
	// and can go true again for the next one.
	val current by rememberUpdatedState(state)
	val loadMore by rememberUpdatedState(onLoadMore)
	LaunchedEffect(gridState, state.sourceName) {
		snapshotFlow {
			val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
			val loaded = current.manga.size
			loaded > 0 &&
				current.hasMore &&
				!current.isLoadingMore &&
				!current.isLoadingFirstPage &&
				last >= loaded - PREFETCH_DISTANCE
		}.collect { if (it) loadMore() }
	}

	Column(modifier.fillMaxSize()) {
		Row(
			Modifier.fillMaxWidth().padding(AgehaSpacing.md),
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(state.sourceTitle, style = MaterialTheme.typography.titleLarge)
			if (state.isSearchSupported) {
				AgehaSearchField(
					value = state.query,
					onValueChange = onSearch,
					placeholder = "Search this source",
					// Enter submits. Searching on every keystroke would be a request per
					// character to somebody else's server.
					onSubmit = onSubmitSearch,
					modifier = Modifier.weight(1f).focusRequester(searchFocus),
				)
			} else {
				Box(Modifier.weight(1f))
			}
			if (state.sortOrders.isNotEmpty()) SortMenu(state.sort, state.sortOrders, onSort)
		}
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

		when {
			state.isLoadingFirstPage -> Box(Modifier.fillMaxSize(), Alignment.Center) {
				CircularProgressIndicator()
			}

			state.manga.isEmpty() && state.failure != null -> Box(Modifier.padding(AgehaSpacing.lg)) {
				SourceFailureNotice(state.failure, onRetry = onRetry)
			}

			state.manga.isEmpty() -> EmptyState(
				title = "Nothing here",
				detail = if (state.query.isEmpty()) {
					"This source returned no results."
				} else {
					"No results for \"${state.query}\"."
				},
			)

			else -> MangaGrid(
				manga = state.manga.map { MangaGridItem(manga = it, imageHeaders = state.imageHeaders) },
				onClick = onOpenManga,
				state = gridState,
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(AgehaSpacing.md),
				footer = {
					Box(
						Modifier.fillMaxWidth().padding(AgehaSpacing.lg),
						contentAlignment = Alignment.Center,
					) {
						when {
							state.failure != null -> SourceFailureNotice(state.failure, onRetry = onRetry)
							state.isLoadingMore -> CircularProgressIndicator(Modifier.size(24.dp))
							!state.hasMore -> Text(
								"End of listing",
								style = AgehaTextStyles.metadata,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				},
			)
		}
	}
}

@Composable
private fun SortMenu(
	selected: AgehaSortOrder?,
	options: List<AgehaSortOrder>,
	onSelect: (AgehaSortOrder) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	Box {
		TextButton(onClick = { expanded = true }) { Text(selected?.label() ?: "Sort") }
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			for (option in options) {
				DropdownMenuItem(
					text = { Text(option.label()) },
					onClick = { onSelect(option); expanded = false },
				)
			}
		}
	}
}

/** Enum constants are SCREAMING_SNAKE; this is what a person should see instead. */
private fun AgehaSortOrder.label(): String =
	name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

/**
 * How close to the end of the loaded results the grid gets before asking for more.
 *
 * One row's worth, not four screens'. Paging early hides latency; paging *far* early means
 * fetching pages a user who is about to stop scrolling will never see, at the source's expense.
 */
private const val PREFETCH_DISTANCE = 8
