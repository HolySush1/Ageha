package app.ageha.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import app.ageha.core.data.ContinueEntry
import app.ageha.core.data.LibraryCategory
import app.ageha.core.data.LibraryEntry
import app.ageha.core.designsystem.AgehaSearchField
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.EmptyState
import app.ageha.core.designsystem.MangaGrid
import app.ageha.core.designsystem.MangaGridItem
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
) {
	Row(modifier.fillMaxSize()) {
		CategoryRail(
			categories = state.categories,
			sizes = state.sizes,
			selectedId = state.selectedCategoryId,
			onSelect = onSelectCategory,
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
			HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
		}
		MangaGrid(
			manga = state.entries.map { it.toGridItem(imageHeaders) },
			onClick = onOpenManga,
			modifier = Modifier.fillMaxSize(),
		)
	}
}

private fun LibraryEntry.toGridItem(headers: Map<String, Map<String, String>>) = MangaGridItem(
	manga = manga,
	imageHeaders = headers[manga.sourceName].orEmpty(),
	badgeCount = newChapters,
	progress = progressPercent,
)

/**
 * The category rail.
 *
 * Counts are shown next to every shelf. On a phone that would be clutter; on a desktop the space
 * is already there, and "which shelf has the 200 things in it" is exactly the question a rail
 * with counts answers without a click.
 */
@Composable
private fun CategoryRail(
	categories: List<LibraryCategory>,
	sizes: Map<Int, Int>,
	selectedId: Int,
	onSelect: (Int) -> Unit,
) {
	Column(
		Modifier
			.width(210.dp)
			.fillMaxHeight()
			.background(MaterialTheme.colorScheme.surfaceContainerLow)
			.padding(vertical = AgehaSpacing.sm),
	) {
		LazyColumn {
			item {
				CategoryRow(
					title = "All",
					count = sizes[LibraryUiState.ALL_CATEGORY] ?: 0,
					isSelected = selectedId == LibraryUiState.ALL_CATEGORY,
					onClick = { onSelect(LibraryUiState.ALL_CATEGORY) },
				)
			}
			items(categories, key = { it.id }) { category ->
				CategoryRow(
					title = category.title,
					count = sizes[category.id] ?: 0,
					isSelected = selectedId == category.id,
					onClick = { onSelect(category.id) },
				)
			}
		}
	}
}

@Composable
private fun CategoryRow(title: String, count: Int, isSelected: Boolean, onClick: () -> Unit) {
	Row(
		Modifier
			.fillMaxWidth()
			.padding(horizontal = AgehaSpacing.sm, vertical = 1.dp)
			.clip(MaterialTheme.shapes.small)
			.background(
				if (isSelected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
			)
			.clickable(onClick = onClick)
			.padding(horizontal = AgehaSpacing.md, vertical = AgehaSpacing.sm),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			title,
			style = MaterialTheme.typography.bodyMedium,
			color = if (isSelected) {
				MaterialTheme.colorScheme.onSecondaryContainer
			} else {
				MaterialTheme.colorScheme.onSurface
			},
			maxLines = 1,
			overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Text(
			count.toString(),
			style = AgehaTextStyles.readerHud,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

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
	var expanded by remember { mutableStateOf(false) }
	Box {
		TextButton(onClick = { expanded = true }) { Text(sort.label) }
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			for (option in LibrarySort.entries) {
				DropdownMenuItem(
					text = { Text(option.label) },
					onClick = {
						onSort(option)
						expanded = false
					},
				)
			}
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
