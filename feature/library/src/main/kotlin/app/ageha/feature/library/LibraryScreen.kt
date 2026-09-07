package app.ageha.feature.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ageha.core.data.ContinueEntry
import app.ageha.core.data.LibraryCategory
import app.ageha.core.data.LibraryEntry
import app.ageha.core.designsystem.AgehaGlass
import app.ageha.core.designsystem.AgehaMotion
import app.ageha.core.designsystem.AgehaSearchField
import app.ageha.core.designsystem.AgehaChip
import app.ageha.core.designsystem.SectionHeader
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.EmptyState
import app.ageha.core.designsystem.GlassTone
import app.ageha.core.designsystem.MangaGrid
import app.ageha.core.designsystem.MangaGridItem
import app.ageha.core.designsystem.glassSurface
import app.ageha.core.model.AgehaManga

/**
 * The library: the user's own collection.
 *
 * Laid out for a desktop window rather than scaled up from a phone. Categories live in a
 * persistent rail down the left instead of behind a tab bar or a drawer, because there is room
 * for them and because switching shelves is the most common thing done here -- hiding it behind a
 * tap would be a phone compromise imported for no reason.
 */
@Composable
fun LibraryScreen(
	state: LibraryUiState,
	imageHeaders: Map<String, Map<String, String>>,
	onOpenManga: (AgehaManga) -> Unit,
	onContinue: (ContinueEntry) -> Unit,
	onSeeAllContinue: () -> Unit,
	onSelectCategory: (Int) -> Unit,
	onSearch: (String) -> Unit,
	onSort: (LibrarySort) -> Unit,
	onNeedHeaders: (String) -> Unit,
	onBrowseSources: () -> Unit,
	modifier: Modifier = Modifier,
	searchFocus: FocusRequester = remember { FocusRequester() },
	/** Whether the shelf rail is folded to a strip. A preference, not screen state -- see below. */
	isRailCollapsed: Boolean = false,
	onToggleRail: () -> Unit = {},
) {
	Row(modifier.fillMaxSize()) {
		CategoryRail(
			categories = state.categories,
			sizes = state.sizes,
			selectedId = state.selectedCategoryId,
			isCollapsed = isRailCollapsed,
			onSelect = onSelectCategory,
			onToggleCollapsed = onToggleRail,
		)
		Column(Modifier.fillMaxSize()) {
			LibraryToolbar(
				query = state.query,
				sort = state.sort,
				onSearch = onSearch,
				onSort = onSort,
				searchFocus = searchFocus,
			)
			HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
			when {
				state.isEmpty -> EmptyState(
					title = "Nothing saved yet",
					detail = "Browse a source and add something to your library, or import a " +
						"backup from the Android app.",
					action = { TextButton(onClick = onBrowseSources) { Text("Browse sources") } },
				)

				state.entries.isEmpty() -> EmptyState(
					title = "No matches",
					detail = "Nothing in this shelf matches \"${state.query}\".",
				)

				else -> LibraryContent(
					state, imageHeaders, onOpenManga, onContinue, onSeeAllContinue, onNeedHeaders,
				)
			}
		}
	}
}

@Composable
private fun LibraryContent(
	state: LibraryUiState,
	imageHeaders: Map<String, Map<String, String>>,
	onOpenManga: (AgehaManga) -> Unit,
	onContinue: (ContinueEntry) -> Unit,
	onSeeAllContinue: () -> Unit,
	onNeedHeaders: (String) -> Unit,
) {
	// Resolving image headers is a per-source cost, not a per-cover one, so it is asked for once
	// per distinct source in the current view rather than from inside the grid's item scope.
	LaunchedEffect(state.entries, state.recent) {
		(state.entries.map { it.manga.sourceName } + state.recent.map { it.manga.sourceName })
			.distinct()
			.forEach(onNeedHeaders)
	}
	Column(Modifier.fillMaxSize()) {
		// Hidden while filtering: the shelf is ordered by when you last read, so leaving it above
		// a filtered grid shows results that do not match the query the user just typed.
		if (state.recent.isNotEmpty() && state.query.isEmpty()) {
			val newest = state.recent.first()
			ContinueHero(
				entry = newest,
				imageHeaders = imageHeaders[newest.manga.sourceName].orEmpty(),
				onOpen = { onContinue(newest) },
				modifier = Modifier.padding(
					horizontal = AgehaSpacing.md,
					vertical = AgehaSpacing.sm,
				),
			)
			// The hero already carries the newest entry, so the shelf starts at the second. Showing
			// it twice, at two sizes, one above the other, reads as a rendering fault.
			ContinueShelf(
				entries = state.recent.drop(1),
				imageHeaders = imageHeaders,
				onOpen = onContinue,
				onSeeAll = onSeeAllContinue,
			)
		}
		// The shelf's own band, between the Continue Reading rows above and the grid below. It
		// replaced a plain divider, which separated the two without saying what either was --
		// and on a screen where both halves are covers, "which of these is my library" is a
		// question the eye actually has to ask.
		SectionHeader(
			label = "My library",
			count = if (state.entries.size == 1) "1 title" else "${state.entries.size} titles",
			modifier = Modifier.padding(
				start = AgehaSpacing.md,
				end = AgehaSpacing.md,
				top = AgehaSpacing.sm,
				bottom = AgehaSpacing.sm,
			),
		)
		MangaGrid(
			manga = state.entries.map { it.toGridItem(imageHeaders) },
			onClick = onOpenManga,
			modifier = Modifier.fillMaxSize(),
		)
	}
}

/**
 * A library row as the grid draws it.
 *
 * ## Why the position reads as a percentage rather than "Ch 214 / 260"
 *
 * The handoff prints a chapter position on every card. Ageha cannot honestly produce one here.
 * A [LibraryEntry] carries how far through the *current chapter* the reader is and how many
 * chapters have appeared since they last opened it -- there is no total, because the Android
 * schema this database stays compatible with has no per-chapter read table, and the total only
 * exists after a source has been asked for a fresh chapter list.
 *
 * So the card says what is actually known. A percentage is the same information the progress bar
 * underneath it carries, stated in a form you can read at a glance across a grid, and it never
 * claims a chapter count that came from nowhere.
 */
private fun LibraryEntry.toGridItem(headers: Map<String, Map<String, String>>): MangaGridItem {
	val percent = progressPercent
	// 100% is the threshold rather than a mark-as-read flag, for the same reason: there is no
	// per-chapter table to ask. Reaching the end of the last chapter Ageha knows about is the
	// strongest completion signal this schema can give.
	val isComplete = percent != null && percent >= 1f
	return MangaGridItem(
		manga = manga,
		imageHeaders = headers[manga.sourceName].orEmpty(),
		badgeCount = newChapters,
		progress = percent,
		positionLabel = percent?.let { "${(it * 100).roundToInt()}%" },
		stateLabel = when {
			isComplete -> "read"
			percent != null && percent > 0f -> "reading"
			hasBeenRead -> "started"
			// Never opened. No state word at all rather than "unread", which would put a mono row
			// under every card on a freshly imported library to say nothing.
			else -> null
		},
		isComplete = isComplete,
	)
}

/**
 * The shelf rail.
 *
 * ## Why it is glass now, and why it collapses
 *
 * It used to be a 210dp slab of `surfaceContainerLow` welded to the left edge -- the last opaque
 * panel left in the window once the navigation became a floating pill over a live backdrop. Next
 * to that pill it read as a leftover from the previous design rather than as part of this one, and
 * it charged a fixed 210dp of a desktop window for a list most people change a few times a day, in
 * an app whose signature view is a grid of covers that wants every pixel of that width.
 *
 * So it is a glass card floating in the same layer as the rest of the chrome, and it collapses.
 * Collapsed it is a 60dp strip of initials and counts, which is enough to switch shelves and to
 * see which one has anything in it -- and the width it hands back is roughly one more column of
 * covers on a laptop. The state is a preference rather than screen state: a rail that unfolded
 * itself on every launch would be a control that does not stay where it was put.
 *
 * ## What is deliberate about a row
 *
 * - **Counts stay, in both states.** On a phone they would be clutter; on a desktop the space is
 *   already paid for, and "which shelf has the 200 things in it" is exactly the question a rail
 *   with counts answers without a click.
 * - **Selection is a filled pill *and* a bar down the leading edge.** Two signals rather than one,
 *   because the pill on its own is a low-contrast fill that a hover state can be mistaken for --
 *   and collapsed, where there is no label to read, the bar is the only thing saying which shelf
 *   you are on.
 * - **That bar is `primary`, not the vermillion accent.** Vermillion means unread, actively
 *   reading, or destructive; a fourth meaning is how an accent turns into a second brand colour.
 * - **Hovering is visible.** A desktop pointer expects an answer before it commits to a click.
 */
@Composable
private fun CategoryRail(
	categories: List<LibraryCategory>,
	sizes: Map<Int, Int>,
	selectedId: Int,
	isCollapsed: Boolean,
	onSelect: (Int) -> Unit,
	onToggleCollapsed: () -> Unit,
) {
	val width by animateDpAsState(
		targetValue = if (isCollapsed) RAIL_COLLAPSED_WIDTH else RAIL_WIDTH,
		animationSpec = tween(AgehaMotion.QUICK_MS, easing = AgehaMotion.standard),
		label = "library-rail-width",
	)
	Column(
		Modifier
			.width(width)
			.fillMaxHeight()
			.padding(
				start = AgehaSpacing.sm,
				end = AgehaSpacing.xs,
				top = AgehaSpacing.xs,
				bottom = AgehaSpacing.sm,
			)
			.glassSurface(MaterialTheme.shapes.large, GlassTone.PANEL)
			.padding(vertical = AgehaSpacing.sm),
	) {
		RailHeader(
			total = sizes[LibraryUiState.ALL_CATEGORY] ?: 0,
			isCollapsed = isCollapsed,
			onToggleCollapsed = onToggleCollapsed,
		)
		LazyColumn(Modifier.weight(1f)) {
			item {
				CategoryRow(
					title = "All",
					count = sizes[LibraryUiState.ALL_CATEGORY] ?: 0,
					isSelected = selectedId == LibraryUiState.ALL_CATEGORY,
					isCollapsed = isCollapsed,
					onClick = { onSelect(LibraryUiState.ALL_CATEGORY) },
				)
			}
			items(categories, key = { it.id }) { category ->
				CategoryRow(
					title = category.title,
					count = sizes[category.id] ?: 0,
					isSelected = selectedId == category.id,
					isCollapsed = isCollapsed,
					onClick = { onSelect(category.id) },
				)
			}
		}
	}
}

/**
 * The rail's own heading, and the control that folds it away.
 *
 * The chevron keeps its name in a tooltip rather than losing it. An icon on its own with no
 * accessible label is a control only the person who wrote it can use.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailHeader(total: Int, isCollapsed: Boolean, onToggleCollapsed: () -> Unit) {
	val label = if (isCollapsed) "Expand shelves" else "Collapse shelves"
	Row(
		Modifier
			.fillMaxWidth()
			.padding(start = AgehaSpacing.xs, end = AgehaSpacing.xs, bottom = AgehaSpacing.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (isCollapsed) {
			Box(Modifier.weight(1f))
		} else {
			Text(
				"Shelves",
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.weight(1f).padding(start = AgehaSpacing.md),
			)
			Text(
				total.toString(),
				style = AgehaTextStyles.readerHud,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		TooltipArea(tooltip = { RailTooltip(label) }) {
			IconButton(onClick = onToggleCollapsed, modifier = Modifier.size(RAIL_TOGGLE_SIZE)) {
				Icon(
					imageVector = if (isCollapsed) {
						Icons.Default.KeyboardArrowRight
					} else {
						Icons.Default.KeyboardArrowLeft
					},
					contentDescription = label,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryRow(
	title: String,
	count: Int,
	isSelected: Boolean,
	isCollapsed: Boolean,
	onClick: () -> Unit,
) {
	val interaction = remember { MutableInteractionSource() }
	val isHovered by interaction.collectIsHoveredAsState()
	val content = if (isSelected) {
		MaterialTheme.colorScheme.onSecondaryContainer
	} else {
		MaterialTheme.colorScheme.onSurface
	}
	val row = @Composable {
		Row(
			Modifier
				.fillMaxWidth()
				.padding(horizontal = AgehaSpacing.xs, vertical = 1.dp)
				.clip(AgehaGlass.PillShape)
				.background(
					when {
						isSelected -> MaterialTheme.colorScheme.secondaryContainer
						isHovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = RAIL_HOVER_ALPHA)
						else -> Color.Transparent
					},
				)
				.hoverable(interaction)
				// `selectable` rather than `clickable`, so the row announces itself as one option
				// out of a set and can say whether it is the current one.
				.selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
				.height(RAIL_ROW_HEIGHT)
				.padding(horizontal = AgehaSpacing.sm),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			// Reserved whether or not it is drawn, so selecting a shelf does not shunt its label
			// sideways under the pointer.
			Box(
				Modifier
					.width(RAIL_MARK_WIDTH)
					.height(RAIL_MARK_HEIGHT)
					.clip(MaterialTheme.shapes.extraSmall)
					.background(
						if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
					),
			)
			if (isCollapsed) {
				// An initial with the count under it. Two short lines fit where a name does not,
				// and the count is the half that still means something without the name.
				Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
					Text(
						title.take(1).uppercase(),
						style = MaterialTheme.typography.labelLarge,
						color = content,
						maxLines = 1,
					)
					Text(
						count.toString(),
						style = AgehaTextStyles.readerHud,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
					)
				}
			} else {
				Text(
					title,
					style = MaterialTheme.typography.bodyMedium,
					color = content,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				Text(
					count.toString(),
					style = AgehaTextStyles.readerHud,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
	// Collapsed, the shelf's name exists nowhere but the tooltip, so the tooltip is the row's only
	// label and is not optional. Expanded, the label is right there and repeating it is noise.
	if (isCollapsed) {
		TooltipArea(
			tooltip = { RailTooltip(if (count > 0) "$title  ·  $count" else title) },
			content = row,
		)
	} else {
		row()
	}
}

@Composable
private fun RailTooltip(text: String) {
	Box(
		Modifier
			.glassSurface(MaterialTheme.shapes.small, GlassTone.RAISED)
			.padding(horizontal = AgehaSpacing.sm, vertical = AgehaSpacing.xs),
	) {
		Text(text, style = AgehaTextStyles.metadata, color = MaterialTheme.colorScheme.onSurface)
	}
}

/** Wide enough for a shelf name, narrow enough that the grid still gets the window. */
private val RAIL_WIDTH = 224.dp

/** Collapsed: an initial, a count, and the selection mark. Nothing else fits; nothing else is needed. */
private val RAIL_COLLAPSED_WIDTH = 60.dp

/** A pointer-sized row, well below the 48dp a touch target would need. This is not a touch UI. */
private val RAIL_ROW_HEIGHT = 36.dp

/** The "you are here" mark down a row's leading edge. */
private val RAIL_MARK_WIDTH = 3.dp
private val RAIL_MARK_HEIGHT = 18.dp

private val RAIL_TOGGLE_SIZE = 28.dp

/** Hover feedback. Enough to answer the pointer, not enough to compete with selection. */
private const val RAIL_HOVER_ALPHA = 0.07f

@Composable
private fun LibraryToolbar(
	query: String,
	sort: LibrarySort,
	onSearch: (String) -> Unit,
	onSort: (LibrarySort) -> Unit,
	searchFocus: FocusRequester,
) {
	Row(
		Modifier.fillMaxWidth().padding(AgehaSpacing.md),
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
		verticalAlignment = Alignment.CenterVertically,
	) {
		AgehaSearchField(
			value = query,
			onValueChange = onSearch,
			placeholder = "Filter this shelf",
			modifier = Modifier.weight(1f).focusRequester(searchFocus),
		)
		SortMenu(sort, onSort)
	}
}

@Composable
private fun SortMenu(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
	// Chips in a row, not a dropdown behind a word.
	//
	// The handoff puts the shelf's ordering on the surface -- `Recent / Unread first / Completed`
	// -- and it is right to. A dropdown hides the current sort behind its own label and costs two
	// clicks to change; five chips cost one, and the set of choices is readable without opening
	// anything. Ageha has five orderings rather than the mockup's three, which is a difference in
	// what the app can do rather than in how it should look.
	Row(horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xs)) {
		for (option in LibrarySort.entries) {
			AgehaChip(
				label = option.label,
				isSelected = option == sort,
				onClick = { onSort(option) },
			)
		}
	}
}

/** The library's title, selectable so a user can copy a manga name out of it. */
@Composable
fun SelectableTitle(text: String, modifier: Modifier = Modifier) {
	SelectionContainer(modifier) {
		Text(text, style = MaterialTheme.typography.headlineSmall)
	}
}
