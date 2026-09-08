package app.ageha.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.ageha.core.data.ContinueEntry
import app.ageha.core.data.LibraryEntry
import app.ageha.core.designsystem.AgehaChip
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.CardStyle
import app.ageha.core.designsystem.EmptyState
import app.ageha.core.designsystem.MangaCardAction
import app.ageha.core.designsystem.MangaGrid
import app.ageha.core.designsystem.MangaGridItem
import app.ageha.core.designsystem.SectionHeader
import app.ageha.core.model.AgehaManga
import kotlin.math.roundToInt

/**
 * The library: the user's own collection.
 *
 * ## One column, not a rail beside a grid
 *
 * This screen used to be a 224dp shelf rail welded to the left edge, with a search field and a
 * sort row above the grid. The handoff draws it as a single scrolling body -- banner, shelf
 * header, chips, grid -- and that is the better shape for a reason beyond fidelity: the rail spent
 * a fixed slice of a desktop window on a list most people change a few times a day, in the one
 * view whose whole job is to fit as many covers across as possible.
 *
 * The shelves did not go anywhere. They are chips in the header block now, in the same vocabulary
 * as the sort chips beside them, which is what this design's component set says a small exclusive
 * choice looks like.
 *
 * ## Where the search field went
 *
 * Into the nav pill's command panel, which is where the handoff puts library search and what
 * WIRING.md describes it doing -- filter the local library instantly, then fan out to the enabled
 * sources. A per-screen field that did the first half and a separate screen that did the second
 * was two controls for one question.
 */
@Composable
fun LibraryScreen(
	state: LibraryUiState,
	imageHeaders: Map<String, Map<String, String>>,
	onOpenManga: (AgehaManga) -> Unit,
	onContinue: (ContinueEntry) -> Unit,
	onSeeAllContinue: () -> Unit,
	onSelectCategory: (Int) -> Unit,
	onSort: (LibrarySort) -> Unit,
	onNeedHeaders: (String) -> Unit,
	onBrowseSources: () -> Unit,
	modifier: Modifier = Modifier,
	/** Settings' card style. See `CardStyle`. */
	cardStyle: CardStyle = CardStyle.COVER,
	/** Settings' "Blur 18+ covers". */
	blurAdultCovers: Boolean = false,
	/** The banner's "All N chapters". Opens that title's chapter list. */
	onOpenChapters: (ContinueEntry) -> Unit = {},
	/** Right-click a card: mark the whole title read. */
	onMarkRead: (AgehaManga) -> Unit = {},
	/** Right-click a card: mark the whole title unread, clearing its progress. */
	onMarkUnread: (AgehaManga) -> Unit = {},
) {
	// Resolving image headers is a per-source cost, not a per-cover one, so it is asked for once
	// per distinct source in the current view rather than from inside the grid's item scope.
	LaunchedEffect(state.entries, state.recent) {
		(state.entries.map { it.manga.sourceName } + state.recent.map { it.manga.sourceName })
			.distinct()
			.forEach(onNeedHeaders)
	}
	Column(modifier.fillMaxSize()) {
		Column(
			Modifier.padding(start = 24.dp, end = 24.dp, top = AgehaSpacing.lg),
			verticalArrangement = Arrangement.spacedBy(AgehaSpacing.lg),
		) {
			// Hidden while filtering: the banner is whatever was read last, so leaving it above a
			// filtered grid shows something that does not match the query just typed.
			if (state.recent.isNotEmpty() && state.query.isEmpty()) {
				val newest = state.recent.first()
				ContinueHero(
					entry = newest,
					imageHeaders = imageHeaders[newest.manga.sourceName].orEmpty(),
					onOpen = { onContinue(newest) },
					onOpenChapters = { onOpenChapters(newest) },
				)
			}
			ShelfHeader(state, onSeeAllContinue)
			ShelfControls(state, onSelectCategory, onSort)
		}
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

			else -> MangaGrid(
				manga = state.entries.map { it.toGridItem(imageHeaders, onMarkRead, onMarkUnread) },
				onClick = onOpenManga,
				style = cardStyle,
				blurAdult = blurAdultCovers,
				contentPadding = PaddingValues(
					start = 24.dp,
					end = 24.dp,
					top = AgehaSpacing.md,
					bottom = 28.dp,
				),
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}

/**
 * The handoff's band above the grid: accent bar, `MY LIBRARY`, a rule, and the count.
 *
 * The trailing link is Ageha's, and it is the one thing the handoff's Library has nowhere to put.
 * Continue Reading is a *screen* here as well as a banner -- it holds everything ever read, not
 * only the newest -- and with the old shelf row gone this link is the only way to reach it from
 * the library.
 */
@Composable
private fun ShelfHeader(state: LibraryUiState, onSeeAllContinue: () -> Unit) {
	Row(
		Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
	) {
		SectionHeader(
			label = "My library",
			count = if (state.entries.size == 1) "1 title" else "${state.entries.size} titles",
			modifier = Modifier.weight(1f),
		)
		if (state.recent.isNotEmpty()) {
			Text(
				"Recently read",
				style = AgehaTextStyles.monoEyebrow,
				color = AgehaTheme.skin.inkFaint,
				modifier = Modifier
					.clip(MaterialTheme.shapes.extraSmall)
					.clickable(onClick = onSeeAllContinue)
					.padding(horizontal = AgehaSpacing.xs, vertical = 2.dp),
			)
		}
	}
}

/**
 * The heading and the two chip rows under it.
 *
 * ## Why the shelves and the ordering are the same kind of control
 *
 * They are both "pick one of a small set and the grid changes", which in this design's vocabulary
 * is a chip. Giving the shelves a rail and the ordering chips said the two were different kinds of
 * thing, when the only real difference is that one filters and the other sorts.
 *
 * They stay on separate rows rather than in one wrapping run. Mixed together, "Favourites" and
 * "Unread first" read as alternatives to each other, and picking one would appear to un-pick the
 * other -- which is exactly what they do *not* do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShelfControls(
	state: LibraryUiState,
	onSelectCategory: (Int) -> Unit,
	onSort: (LibrarySort) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(AgehaSpacing.sm)) {
		Text("Reading & read", style = MaterialTheme.typography.headlineSmall)
		// Only when there is more than the one implicit shelf. A single chip reading "All" is a
		// control with no alternative, which is furniture rather than a choice.
		if (state.categories.isNotEmpty()) {
			FlowRow(
				horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
				verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
			) {
				AgehaChip(
					label = "All",
					isSelected = state.selectedCategoryId == LibraryUiState.ALL_CATEGORY,
					onClick = { onSelectCategory(LibraryUiState.ALL_CATEGORY) },
					count = (state.sizes[LibraryUiState.ALL_CATEGORY] ?: 0).toString(),
				)
				for (category in state.categories) {
					AgehaChip(
						label = category.title,
						isSelected = state.selectedCategoryId == category.id,
						onClick = { onSelectCategory(category.id) },
						count = (state.sizes[category.id] ?: 0).toString(),
					)
				}
			}
		}
		FlowRow(
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
			verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
		) {
			// Chips in a row, not a dropdown behind a word. The handoff puts the shelf's ordering
			// on the surface -- `Recent / Unread first / Completed` -- and it is right to: a
			// dropdown hides the current sort behind its own label and costs two clicks to change,
			// while chips cost one and the whole set is readable without opening anything. Ageha
			// has five orderings rather than the mockup's three, which is a difference in what the
			// app can do rather than in how it should look.
			for (option in LibrarySort.entries) {
				AgehaChip(
					label = option.label,
					isSelected = option == state.sort,
					onClick = { onSort(option) },
				)
			}
		}
	}
}

/**
 * A library row as the grid draws it.
 *
 * ## Why the position reads "Ch 214 · 45%" rather than "Ch 214 / 260"
 *
 * The handoff prints a chapter position on every card, as a fraction. Ageha can honestly produce
 * the left half of one and not the right: the history join carries the chapter last read, so the
 * number is a fact, but there is no total, because the Android schema this database stays
 * compatible with has no per-chapter read table and the chapter count only exists after a source
 * has been asked for a fresh list.
 *
 * So the card pairs the number with the percentage instead. Between them they answer both halves
 * of "where am I" -- which chapter, and how much of the whole thing that is -- without either one
 * claiming a total that came from nowhere.
 */
private fun LibraryEntry.toGridItem(
	headers: Map<String, Map<String, String>>,
	onMarkRead: (AgehaManga) -> Unit,
	onMarkUnread: (AgehaManga) -> Unit,
): MangaGridItem {
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
		positionLabel = positionLabel(),
		stateLabel = when {
			isComplete -> "read"
			percent != null && percent > 0f -> "reading"
			hasBeenRead -> "started"
			// Never opened. No state word at all rather than "unread", which would put a mono row
			// under every card on a freshly imported library to say nothing.
			else -> null
		},
		isComplete = isComplete,
		actions = listOf(
			MangaCardAction("Mark as read") { onMarkRead(manga) },
			MangaCardAction("Mark as unread") { onMarkUnread(manga) },
		),
	)
}

/**
 * The card's mono position line: `Ch 214 · 45%`.
 *
 * The chapter number comes from the history join, which already carries the row for the chapter
 * last read -- so this is the number of the chapter you stopped on, not a claim about how many
 * there are. That distinction is why it reads `Ch 214` and never `Ch 214 / 260`: the total only
 * exists once a source has been asked for a fresh chapter list, and a card in a grid has not
 * asked.
 *
 * Either half can be missing on its own. A backup-imported row has a percentage and no chapter
 * rows behind it; a source that numbers nothing has chapters and no numbers.
 */
private fun LibraryEntry.positionLabel(): String? {
	val chapter = lastChapterNumber?.let { value ->
		val whole = value.toInt()
		// 12.5 is a real chapter number; 12.0 must not print as "12.0".
		if (value == whole.toFloat()) "Ch $whole" else "Ch $value"
	}
	val percent = progressPercent?.let { "${(it * 100).roundToInt()}%" }
	return listOfNotNull(chapter, percent).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The library's title, selectable so a user can copy a manga name out of it. */
@Composable
fun SelectableTitle(text: String, modifier: Modifier = Modifier) {
	SelectionContainer(modifier) {
		Text(text, style = MaterialTheme.typography.headlineSmall)
	}
}
