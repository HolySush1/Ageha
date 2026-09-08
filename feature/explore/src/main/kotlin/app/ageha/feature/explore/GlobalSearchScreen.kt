package app.ageha.feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import app.ageha.core.designsystem.AgehaSearchField
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.EmptyState
import app.ageha.core.designsystem.InlineFailureLine
import app.ageha.core.designsystem.MangaCard
import app.ageha.core.model.AgehaManga
import androidx.compose.runtime.remember

/**
 * Results from every enabled source, grouped by source.
 *
 * Grouped rather than merged into one grid, and that is the whole design. The same manga is on a
 * dozen aggregator sites in a dozen different scan qualities; a single merged grid of near
 * duplicates gives the user no way to choose between them. Grouping by source turns the question
 * from "which of these forty covers" into "which site", which is the question they can answer.
 *
 * Sources arrive as they answer. A slow site does not hold up a fast one, and a dead one takes
 * its own row down and nothing else.
 */
@Composable
fun GlobalSearchScreen(
	state: GlobalSearchUiState,
	imageHeaders: Map<String, Map<String, String>>,
	onQuery: (String) -> Unit,
	onSubmit: () -> Unit,
	onOpenManga: (AgehaManga) -> Unit,
	onOpenSource: (String) -> Unit,
	onNeedHeaders: (String) -> Unit,
	modifier: Modifier = Modifier,
	searchFocus: FocusRequester = remember { FocusRequester() },
	/**
	 * Titles already in the user's library that match the query.
	 *
	 * Here because this screen is now the *only* search in Ageha, and a search that cannot find a
	 * book you already own is not a search. It was reachable through the command panel before, in
	 * a list capped at three library rows and two from every source combined; this screen replaced
	 * that panel, so it has to inherit the one thing the panel did better than it.
	 *
	 * A plain list of models rather than a dependency on `:feature:library` -- the shell filters
	 * the library it already has loaded and hands the matches down.
	 */
	libraryMatches: List<AgehaManga> = emptyList(),
) {
	Column(modifier.fillMaxSize()) {
		SearchBar(state, onQuery, onSubmit, searchFocus)
		// A determinate-looking bar would be a lie -- sources answer in any order and some never
		// do. This says "still going" and nothing more.
		if (state.isSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
		when {
			!state.hasSearched -> EmptyState(
				title = "Search every source you have enabled",
				detail = "One query, all your enabled sources at once. Sources that cannot take a " +
					"search term are skipped rather than asked.",
			)

			state.results.isEmpty() && libraryMatches.isEmpty() -> EmptyState(
				title = "No source can answer that",
				detail = "None of your enabled sources supports searching by title. Enable a few " +
					"more in Explore and try again.",
			)

			!state.isSearching && state.found == 0 && libraryMatches.isEmpty() -> EmptyState(
				title = "Nothing found",
				detail = "None of your ${state.searchedCount} enabled sources has anything " +
					"matching \"${state.query}\".",
			)

			else -> LazyColumn(Modifier.fillMaxSize()) {
				// Your own shelf first, always. It is the one group whose answer is already known
				// -- no request, no waiting -- and "you already have this" is the single most
				// useful thing a search can tell you before it starts listing places to get it.
				if (libraryMatches.isNotEmpty()) {
					item(key = LIBRARY_GROUP_KEY) {
						LibraryGroup(libraryMatches, imageHeaders, onOpenManga)
					}
				}
				items(state.ordered, key = { it.sourceName }) { group ->
					SourceGroup(group, imageHeaders, onOpenManga, onOpenSource, onNeedHeaders)
				}
			}
		}
	}
}

@Composable
private fun SearchBar(
	state: GlobalSearchUiState,
	onQuery: (String) -> Unit,
	onSubmit: () -> Unit,
	searchFocus: FocusRequester,
) {
	Column(Modifier.padding(AgehaSpacing.md), verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs)) {
		state.subject?.let { subject ->
			// Which entry sent the user here. By the time results land, the list they clicked in
			// is gone from the screen, and "searching for what, exactly" stops being obvious.
			Text(
				"Looking for another source for “$subject”",
				style = AgehaTextStyles.metadata,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Row(
			Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
			verticalAlignment = Alignment.CenterVertically,
		) {
			AgehaSearchField(
				value = state.query,
				onValueChange = onQuery,
				placeholder = "Search all enabled sources",
				// Enter submits. Every search here is a request to somebody else's server, so it
				// is never per-keystroke.
				onSubmit = onSubmit,
				modifier = Modifier.weight(1f).focusRequester(searchFocus),
			)
			TextButton(onClick = onSubmit) { Text("Search") }
		}
		if (state.hasSearched) {
			Text(
				if (state.isSearching) {
					"Searching ${state.searchedCount} sources… ${state.found} found so far"
				} else {
					"${state.found} results across ${state.searchedCount} sources"
				},
				style = AgehaTextStyles.metadata,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/**
 * Matches from the user's own library, drawn as the first group.
 *
 * Deliberately the same shape as a [SourceGroup] rather than a different-looking panel: it is one
 * more place the title was found, and the whole point of this screen is that every place is listed
 * the same way. What differs is the heading and the absence of an "Open source" button -- there is
 * no source to open, you are already there.
 */
@Composable
private fun LibraryGroup(
	matches: List<AgehaManga>,
	imageHeaders: Map<String, Map<String, String>>,
	onOpenManga: (AgehaManga) -> Unit,
) {
	Column(Modifier.padding(vertical = AgehaSpacing.sm)) {
		Row(
			Modifier.fillMaxWidth().padding(horizontal = AgehaSpacing.lg),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			Text(
				"In your library",
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			Text(
				"${matches.size}",
				style = AgehaTextStyles.metadata,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		LazyRow(
			contentPadding = androidx.compose.foundation.layout.PaddingValues(
				horizontal = AgehaSpacing.md,
			),
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			items(matches, key = { "library:${it.sourceName}:${it.id}" }) { manga ->
				MangaCard(
					manga = manga,
					imageHeaders = imageHeaders[manga.sourceName].orEmpty(),
					onClick = { onOpenManga(manga) },
					modifier = Modifier.width(132.dp),
				)
			}
		}
	}
}

/** Stable key for the library group, so it never collides with a source named the same. */
private const val LIBRARY_GROUP_KEY = "__ageha_library_group__"

@Composable
private fun SourceGroup(
	group: SourceResults,
	imageHeaders: Map<String, Map<String, String>>,
	onOpenManga: (AgehaManga) -> Unit,
	onOpenSource: (String) -> Unit,
	onNeedHeaders: (String) -> Unit,
) {
	// A source that answered with nothing is dropped once it has finished. Keeping twenty empty
	// headings on screen buries the three that found something.
	if (group.isEmpty) return
	Column(Modifier.padding(vertical = AgehaSpacing.sm)) {
		Row(
			Modifier.fillMaxWidth().padding(horizontal = AgehaSpacing.lg),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			Text(
				group.sourceTitle,
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			if (group.isLoading) {
				CircularProgressIndicator(Modifier.padding(AgehaSpacing.xs), strokeWidth = 2.dp)
			} else if (group.manga.isNotEmpty()) {
				Text(
					"${group.manga.size}",
					style = AgehaTextStyles.metadata,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				TextButton(onClick = { onOpenSource(group.sourceName) }) { Text("Open source") }
			}
		}
		when {
			// One line, not a panel. A cross-source search touches twenty sites and some of them
			// are always down; twenty error panels would bury the results that did arrive.
			group.failure != null -> InlineFailureLine(
				failure = group.failure,
				modifier = Modifier.padding(horizontal = AgehaSpacing.lg),
			)

			group.manga.isNotEmpty() -> {
				androidx.compose.runtime.LaunchedEffect(group.sourceName) { onNeedHeaders(group.sourceName) }
				LazyRow(
					contentPadding = androidx.compose.foundation.layout.PaddingValues(
						horizontal = AgehaSpacing.md,
					),
					horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
				) {
					items(group.manga, key = { "${it.sourceName}:${it.id}" }) { manga ->
						MangaCard(
							manga = manga,
							imageHeaders = imageHeaders[manga.sourceName].orEmpty(),
							onClick = { onOpenManga(manga) },
							modifier = Modifier.width(132.dp),
						)
					}
				}
			}
		}
	}
}
