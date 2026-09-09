package app.ageha.feature.explore

import app.ageha.core.designsystem.CoverGridSkeleton
import app.ageha.core.designsystem.DelayedAppearance
import app.ageha.core.designsystem.RowSkeleton
import app.ageha.core.designsystem.interactive
import app.ageha.core.designsystem.rememberInteraction
import app.ageha.core.designsystem.rowHoverTint
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import app.ageha.core.designsystem.MangaCardAction
import app.ageha.core.designsystem.MangaGridItem
import app.ageha.core.designsystem.SourceFailureNotice
import app.ageha.core.designsystem.rememberSearchFieldState
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaSortOrder
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import app.ageha.core.designsystem.KeyCap

/**
 * The source picker.
 *
 * A list rather than a grid: sources have names and languages, not covers, and a grid of text
 * tiles is a grid for its own sake. Each row carries the switch that enables it, so turning
 * sources on is done from the same place they are found rather than in a separate settings screen.
 *
 * ## Why the filters are a rail and not a menu
 *
 * They used to be a dropdown behind a "Filters (2)" button. The handoff puts them in a 292dp panel
 * down the right-hand side, and that is the better shape for what they are: switches and a run of
 * language chips that mostly stay where they were put. A menu closes every time one is touched, so
 * narrowing a catalogue by a language and two content rules meant opening the same menu three
 * times -- and while it was open it covered the list it was filtering, which is the one thing a
 * filter control must never do.
 *
 * The rail is a toggle rather than always-on because it costs 292dp of a window whose main content
 * is a list of rows, and most sessions never touch a filter at all.
 *
 * Whatever the filters remove is counted out loud, because a filtered list that does not say it is
 * filtered is indistinguishable from a catalogue that is missing things.
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
	onEnableDefaults: () -> Unit,
	modifier: Modifier = Modifier,
	searchFocus: FocusRequester = remember { FocusRequester() },
) {
	var filtersOpen by remember { mutableStateOf(false) }
	val activeFilters = listOf(state.locale != null, state.hideBroken, state.showAdult).count { it }
	Row(modifier.fillMaxSize()) {
		Column(Modifier.weight(1f).fillMaxHeight()) {
			Column(
				Modifier
					.fillMaxWidth()
					.padding(horizontal = AgehaSpacing.lg, vertical = AgehaSpacing.md),
				verticalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
			) {
				SearchRow(
					state = state,
					onSearch = onSearch,
					searchFocus = searchFocus,
					filtersOpen = filtersOpen,
					activeFilters = activeFilters,
					onToggleFilters = { filtersOpen = !filtersOpen },
					onEnableDefaults = onEnableDefaults,
				)
				TabRow(state, onFilter)
			}
			SourceList(state, onOpenSource, onSetEnabled, onFilter)
		}
		if (filtersOpen) {
			FilterRail(
				state = state,
				activeFilters = activeFilters,
				onLocale = onLocale,
				onHideBroken = onHideBroken,
				onShowAdult = onShowAdult,
				onSearch = onSearch,
			)
		}
	}
}

/**
 * The filled button that turns the default English sources on.
 *
 * ## Why it is the one filled control on this screen
 *
 * Everything else here is a ghost: outlined chips, a glass field, an outlined Filters button.
 * That is deliberate for controls that *narrow* a list you are looking at. This one changes what
 * you have -- it switches sources on -- and a control with a different kind of consequence should
 * not look like the ones around it. The fill is what says "this does something", before the label
 * has been read.
 *
 * `colorScheme.primary`, not `skin.accent`. The accent is the handoff's colour for dots, rules and
 * borders, held to the 3:1 that non-text elements need; neither skin's accent clears 4.5:1 as a
 * fill behind a label, and this fill carries one. `primary` is the same hue a few tones deeper and
 * is the token that is contrast-checked for exactly this. See `AgehaSkin.accent`.
 *
 * ## Why it carries a count, and why it goes away
 *
 * "+37" is the difference between a button whose effect you can predict and one you have to press
 * to find out. At zero it is not drawn at all: every default is already on, so a button offering
 * to turn them on would be a control that does nothing -- and one that teaches, once, that this
 * button does nothing.
 */
@Composable
private fun DefaultSourcesButton(count: Int, onClick: () -> Unit) {
	val shape = MaterialTheme.shapes.large
	Row(
		Modifier
			.height(FIELD_HEIGHT)
			.clip(shape)
			.background(MaterialTheme.colorScheme.primary)
			.clickable(role = Role.Button, onClick = onClick)
			.testTag(DEFAULTS_BUTTON_TAG)
			.padding(horizontal = AgehaSpacing.lg),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
	) {
		Text(
			"Default English sources",
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onPrimary,
		)
		// How many sources the press would switch on. Reads as "+37", not "37", because the sign
		// is the part that says this adds rather than replaces.
		Text(
			"+" + count,
			style = AgehaTextStyles.monoEyebrow,
			color = MaterialTheme.colorScheme.onPrimary,
		)
	}
}

/**
 * The handoff's 50dp field, the button that turns on the default sources, and the one that opens
 * the rail.
 *
 * The CTRL K cap is a promise the application has to keep, so it is drawn only because the
 * shortcut exists. A key cap printed beside a field that does not answer to that key is worse than
 * no cap at all: it teaches someone a shortcut and then fails them with it.
 */
@Composable
private fun SearchRow(
	state: ExploreUiState,
	onSearch: (String) -> Unit,
	searchFocus: FocusRequester,
	filtersOpen: Boolean,
	activeFilters: Int,
	onToggleFilters: () -> Unit,
	onEnableDefaults: () -> Unit,
) {
	val skin = AgehaTheme.skin
	Row(
		Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			Modifier
				.weight(1f)
				.height(FIELD_HEIGHT)
				.glassSurface(MaterialTheme.shapes.large, GlassTone.PANEL)
				.padding(horizontal = AgehaSpacing.lg),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
		) {
			Icon(
				Icons.Default.Search,
				contentDescription = null,
				tint = skin.inkFaint,
				modifier = Modifier.size(17.dp),
			)
			// Not `BasicTextField(value = state.query, ...)`. The query is hoisted into a view
			// model and echoed back a frame later, which the `String` overload answers by
			// clamping the caret to zero on every keystroke -- typing "abc" leaves "cba".
			// Drawing this field's chrome here rather than using [AgehaSearchField] changes what
			// it looks like, not what it is; the state has to come from the same place either way.
			val field = rememberSearchFieldState(state.query, onSearch)
			Box(Modifier.weight(1f)) {
				// Driven by what the field holds rather than by the query upstream has got to, so
				// the placeholder does not flash back over the first character typed.
				if (field.value.text.isEmpty()) {
					Text(
						"Search " + state.totalCount + " sources",
						style = AgehaTextStyles.monoControl,
						color = skin.inkFaint,
					)
				}
				BasicTextField(
					value = field.value,
					onValueChange = field::onValueChange,
					singleLine = true,
					textStyle = AgehaTextStyles.monoControl.copy(
						color = MaterialTheme.colorScheme.onSurface,
					),
					cursorBrush = SolidColor(skin.accent),
					modifier = Modifier
						.fillMaxWidth()
						.focusRequester(searchFocus)
						.testTag(SOURCE_SEARCH_TAG),
				)
			}
			KeyCap("CTRL K")
		}
		if (state.defaultsOff > 0) {
			DefaultSourcesButton(state.defaultsOff, onEnableDefaults)
		}
		FiltersButton(filtersOpen, activeFilters, onToggleFilters)
	}
}

/** The 50dp button beside the field, with the handoff's accent pill counting active filters. */
@Composable
private fun FiltersButton(isOpen: Boolean, activeFilters: Int, onClick: () -> Unit) {
	val skin = AgehaTheme.skin
	val shape = MaterialTheme.shapes.large
	Row(
		Modifier
			.height(FIELD_HEIGHT)
			.clip(shape)
			.background(
				if (isOpen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
			)
			.border(1.dp, if (isOpen) skin.accentLine else skin.line, shape)
			.clickable(role = Role.Button, onClick = onClick)
			.testTag(FILTER_MENU_TAG)
			.padding(horizontal = AgehaSpacing.lg),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
	) {
		Text(
			"Filters",
			style = MaterialTheme.typography.labelMedium,
			color = if (isOpen) {
				MaterialTheme.colorScheme.onSurface
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
		)
		// Drawn only when something is actually filtering. A counter permanently reading zero is a
		// badge that has stopped meaning anything by the time it means something.
		if (activeFilters > 0) {
			Box(
				Modifier
					.clip(CircleShape)
					.background(skin.accent)
					.padding(horizontal = 6.dp, vertical = 1.dp),
			) {
				Text(
					activeFilters.toString(),
					style = AgehaTextStyles.monoEyebrow,
					// White rather than onPrimary: this fill is the raw accent, not the
					// contrast-corrected one. See AgehaSkin.accent.
					color = Color.White,
				)
			}
		}
	}
}

/** The three tabs, and N SHOWN at the right end of the row. */
@Composable
private fun TabRow(state: ExploreUiState, onFilter: (SourceFilter) -> Unit) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		verticalAlignment = Alignment.CenterVertically,
	) {
		for (option in SourceFilter.entries) {
			AgehaChip(
				label = option.label,
				isSelected = state.filter == option,
				onClick = { onFilter(option) },
				// Counts come from the *unfiltered* set, as the handoff specifies. A tab whose
				// count already had the current filter applied would read zero on the tab you are
				// not looking at and tell you nothing about whether switching to it is worth the
				// click.
				count = when (option) {
					SourceFilter.ENABLED -> state.enabledCount.toString()
					SourceFilter.ALL -> state.totalCount.toString()
					SourceFilter.BROKEN -> null
				},
			)
		}
		Box(Modifier.weight(1f))
		// The handoff's N SHOWN: how many rows survived the tab and the filters together. It is
		// the one number that answers "why is this list shorter than I expected", and it sits
		// where the eye lands after reading the tabs left to right.
		Text(
			state.sources.size.toString() + " shown",
			style = AgehaTextStyles.monoEyebrow,
			color = AgehaTheme.skin.inkFaint,
		)
	}
}

/**
 * The list itself: a --panel2 container whose rows are divided by --line.
 *
 * A container rather than rows floating on the backdrop. Forty hairline-separated rows inside one
 * bordered card read as a *list*; forty free-standing rows read as forty cards, and the eye has to
 * work out the grouping for itself every time the screen opens.
 */
@Composable
private fun SourceList(
	state: ExploreUiState,
	onOpenSource: (String) -> Unit,
	onSetEnabled: (String, Boolean) -> Unit,
	onFilter: (SourceFilter) -> Unit,
) {
	when {
		// The source registry is 1360 entries read off disk and sorted, which on a cold start is
		// long enough to see. A list-shaped skeleton says what is coming; a spinner in the middle
		// of a blank panel said only that something was happening somewhere.
		state.isLoading -> DelayedAppearance(visible = true) {
			RowSkeleton(Modifier.fillMaxSize())
		}

		state.sources.isEmpty() &&
			state.filter == SourceFilter.ENABLED &&
			state.query.isEmpty() -> EmptyState(
			title = "No sources enabled",
			detail = "Ageha ships " + state.totalCount + " sources and you have turned all of " +
				"them off. Enable some to begin -- \"Default English sources\" above turns on " +
				"the English ones that are not 18+ and are not known to be broken.",
			action = {
				TextButton(onClick = { onFilter(SourceFilter.ALL) }) { Text("Show all sources") }
			},
		)

		// Found nothing here, but the full catalogue has it. Always the case on a fresh
		// installation, where nothing is enabled yet and so *every* search of the enabled sources
		// comes back empty -- and "no sources match" sends someone looking for a source that is
		// sitting right there, switched off.
		state.sources.isEmpty() && state.matchesInAllSources > 0 -> EmptyState(
			title = if (state.matchesInAllSources == 1) {
				"1 source matches, but it is not enabled"
			} else {
				state.matchesInAllSources.toString() + " sources match, but none are enabled"
			},
			detail = "It is in the catalogue but switched off. Show the full list to turn it on.",
			action = {
				TextButton(onClick = { onFilter(SourceFilter.ALL) }) { Text("Show all sources") }
			},
		)

		// Nothing matches *here*, but a filter is withholding rows. Offering the search term back
		// without mentioning the filter would be the app hiding its own doing.
		state.sources.isEmpty() && state.hasHiddenSources -> EmptyState(
			title = "No sources match",
			detail = "Nothing here matches that search, and " +
				listOfNotNull(
					state.hiddenAdultCount.takeIf { it > 0 }?.let {
						it.toString() + " 18+ source(s)"
					},
					state.hiddenBrokenCount.takeIf { it > 0 }?.let {
						it.toString() + " known broken source(s)"
					},
				).joinToString(" and ") +
				" are hidden by your filters.",
		)

		state.sources.isEmpty() -> EmptyState(
			title = "No sources match",
			detail = "Nothing here matches that search and filter.",
		)

		else -> LazyColumn(
			Modifier
				.fillMaxSize()
				.padding(
					start = AgehaSpacing.lg,
					end = AgehaSpacing.lg,
					bottom = AgehaSpacing.lg,
				)
				.clip(MaterialTheme.shapes.large)
				.background(MaterialTheme.colorScheme.surfaceContainerLow)
				.border(1.dp, AgehaTheme.skin.line, MaterialTheme.shapes.large),
		) {
			itemsIndexed(state.sources, key = { _, listing -> listing.name }) { index, listing ->
				// Drawn by the list rather than by the row, so no hairline hangs under the last
				// one against the container's own border -- two lines 1dp apart, which is the
				// most visible way to get this construction wrong.
				if (index > 0) {
					Box(
						Modifier
							.fillMaxWidth()
							.height(1.dp)
							.background(AgehaTheme.skin.line),
					)
				}
				SourceRow(listing, onOpenSource, onSetEnabled)
			}
		}
	}
}

/**
 * The handoff's 292dp filter rail.
 *
 * Three toggle rows, a run of language chips, and the note box that says what is being withheld.
 * The handoff has four toggles; there is no unverified-mirror tier in the parsers library, so that
 * row is absent rather than present and inert.
 *
 * The language *chips* are here rather than in Settings for the reason the handoff puts them here:
 * the set of languages is derived from the catalogue currently loaded, so it belongs beside the
 * catalogue rather than in a preferences screen that would have to be told about it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRail(
	state: ExploreUiState,
	activeFilters: Int,
	onLocale: (String?) -> Unit,
	onHideBroken: (Boolean) -> Unit,
	onShowAdult: (Boolean) -> Unit,
	onSearch: (String) -> Unit,
) {
	val skin = AgehaTheme.skin
	Column(
		Modifier
			.width(RAIL_WIDTH)
			.fillMaxHeight()
			.padding(end = AgehaSpacing.lg, top = AgehaSpacing.md, bottom = AgehaSpacing.lg)
			.glassSurface(MaterialTheme.shapes.large, GlassTone.PANEL)
			.verticalScroll(rememberScrollState())
			.padding(18.dp),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
	) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text(
				"FILTERS",
				style = AgehaTextStyles.monoEyebrow,
				color = skin.accent,
				modifier = Modifier.weight(1f),
			)
			// Clears the filters and the query, and deliberately not the tab or the enabled
			// states: those are decisions, not a narrowing you might want to undo in one go.
			Text(
				"reset",
				style = AgehaTextStyles.monoMeta,
				color = if (activeFilters > 0 || state.query.isNotEmpty()) {
					skin.accent
				} else {
					skin.inkFaint
				},
				modifier = Modifier
					.clip(MaterialTheme.shapes.extraSmall)
					.clickable {
						onHideBroken(false)
						onShowAdult(false)
						onLocale(null)
						onSearch("")
					}
					.padding(horizontal = AgehaSpacing.xs, vertical = 2.dp),
			)
		}
		ToggleRow(
			label = "Hide 18+ content",
			hint = "adult catalogues stay out of the list",
			checked = !state.showAdult,
			onToggle = { onShowAdult(!it) },
			testTag = ADULT_TOGGLE_TAG,
		)
		ToggleRow(
			label = "Hide broken sources",
			hint = "upstream flagged these as not working",
			checked = state.hideBroken,
			onToggle = onHideBroken,
		)
		ToggleRow(
			label = "Only my language",
			hint = if (state.locale == null) "showing every language" else "one language at a time",
			checked = state.locale != null,
			// Turning it on with nothing chosen would be a switch that does nothing, so it takes
			// the largest catalogue's language -- which is what someone means by "my language" far
			// more often than not, and is one chip click from being corrected.
			onToggle = { on ->
				onLocale(if (on) state.availableLocales.firstOrNull()?.tag else null)
			},
		)
		Text("LANGUAGE", style = AgehaTextStyles.monoEyebrow, color = skin.inkFaint)
		FlowRow(
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
			verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
		) {
			AgehaChip(
				label = "Any",
				isSelected = state.locale == null,
				onClick = { onLocale(null) },
			)
			for (option in state.availableLocales) {
				AgehaChip(
					label = option.displayName,
					isSelected = state.locale == option.tag,
					onClick = { onLocale(option.tag) },
					count = option.count.toString(),
				)
			}
		}
		// Said out loud, every time. See ExploreUiState.hiddenAdultCount for why.
		//
		// A tinted note rather than another grey line, which is the handoff's treatment for
		// exactly this: a sentence about the screen's own state, where a plain paragraph gets
		// skipped and a warning colour would overstate it. Nothing has gone wrong; rows are merely
		// not being shown, and the user is the one who asked for that.
		if (state.hasHiddenSources) {
			NoteBox(
				listOfNotNull(
					state.hiddenAdultCount.takeIf { it > 0 }?.let { it.toString() + " 18+ hidden" },
					state.hiddenBrokenCount.takeIf { it > 0 }?.let {
						it.toString() + " known broken hidden"
					},
				).joinToString(" · ") + " by the current filters.",
				modifier = Modifier.testTag(HIDDEN_COUNT_TAG),
			)
		}
	}
}

/** One row of the rail: a label, a mono hint, and the switch, over a --line divider. */
@Composable
private fun ToggleRow(
	label: String,
	hint: String,
	checked: Boolean,
	onToggle: (Boolean) -> Unit,
	testTag: String? = null,
) {
	Column {
		Row(
			Modifier
				.fillMaxWidth()
				.then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
				.padding(vertical = AgehaSpacing.sm),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			Column(Modifier.weight(1f)) {
				Text(
					label,
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Text(hint, style = AgehaTextStyles.monoMeta, color = AgehaTheme.skin.inkFaint)
			}
			AgehaSwitch(checked = checked, onCheckedChange = onToggle)
		}
		Box(Modifier.fillMaxWidth().height(1.dp).background(AgehaTheme.skin.line))
	}
}

/** The handoff's 50px search field and Filters button. */
private val FIELD_HEIGHT = 50.dp

/** The handoff's 292px filter rail. */
private val RAIL_WIDTH = 292.dp

@Composable
private fun SourceRow(
	listing: SourceListing,
	onOpen: (String) -> Unit,
	onSetEnabled: (String, Boolean) -> Unit,
) {
	val skin = AgehaTheme.skin
	val hover = rememberInteraction()
	Row(
		Modifier
			.fillMaxWidth()
			.testTag(SOURCE_ROW_TAG)
			// Disabled sources are still listed -- that is how you turn one on -- but they do not
			// open, so they must not light up as though they would. `enabled` is threaded through
			// rather than assumed, which is the one case the shared modifier takes a flag for.
			.interactive(hover, hoverTint = rowHoverTint, enabled = listing.isEnabled)
			.clickable(
				enabled = listing.isEnabled,
				interactionSource = hover,
				indication = null,
			) { onOpen(listing.name) }
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
const val SOURCE_SEARCH_TAG = "source-search"
const val DEFAULTS_BUTTON_TAG = "source-defaults-button"

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
	/**
	 * Right-click a result: search every enabled source for the same title.
	 *
	 * Browse is one source at a time by construction, so the question "who else has this" cannot
	 * be answered by the screen the user is standing on. Reaching it from the card means the
	 * title does not have to be retyped into a second search -- which is what a user does
	 * otherwise, and gets wrong, because the name on the card is often a romanisation.
	 */
	onFindElsewhere: (AgehaManga) -> Unit = {},
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
			// A grid of cover-shaped blocks, at the same reflow width the real grid uses, so the
			// columns do not move when the covers land. This is the longest wait in Ageha -- one
			// HTTP round trip to a source that may be slow -- and the one most worth making feel
			// like something is arriving rather than like nothing is.
			state.isLoadingFirstPage -> DelayedAppearance(visible = true) {
				CoverGridSkeleton(Modifier.fillMaxSize())
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
				manga = state.manga.map { result ->
					MangaGridItem(
						manga = result,
						imageHeaders = state.imageHeaders,
						actions = listOf(
							MangaCardAction("Find another source") { onFindElsewhere(result) },
						),
					)
				},
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
