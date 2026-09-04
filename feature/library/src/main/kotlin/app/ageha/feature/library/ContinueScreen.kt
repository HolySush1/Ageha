package app.ageha.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import app.ageha.core.data.ContinueEntry
import app.ageha.core.designsystem.AgehaSearchField
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.EmptyState
import app.ageha.core.designsystem.MangaThumbnail
import java.util.concurrent.TimeUnit

/**
 * Continue Reading: everything read, most recent first.
 *
 * A list rather than a grid, which is the one real layout decision here. A grid of covers answers
 * "what do I own"; this screen answers "where was I", and the answer is a chapter number and a
 * name -- text, which a grid has nowhere to put. Rows give it somewhere, and a desktop window is
 * wide enough that a row can carry cover, title, source, chapter and progress at once without any
 * of them being hidden behind a tap.
 *
 * This replaced the external tracking services outright (CLAUDE.md 9). Nothing on this screen
 * touches the network.
 */
@Composable
fun ContinueScreen(
	state: ContinueUiState,
	imageHeaders: Map<String, Map<String, String>>,
	onOpen: (ContinueEntry) -> Unit,
	onSearch: (String) -> Unit,
	onRemove: (Long) -> Unit,
	onFindElsewhere: (ContinueEntry) -> Unit,
	onNeedHeaders: (String) -> Unit,
	onBrowseSources: () -> Unit,
	modifier: Modifier = Modifier,
	searchFocus: FocusRequester = remember { FocusRequester() },
) {
	LaunchedEffect(state.entries) {
		state.entries.map { it.manga.sourceName }.distinct().forEach(onNeedHeaders)
	}
	Column(modifier.fillMaxSize()) {
		Toolbar(state, onSearch, searchFocus)
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
		when {
			state.isEmpty -> EmptyState(
				title = "Nothing read yet",
				detail = "Open something and it will appear here, with the chapter you stopped on " +
					"and a way straight back to it.",
				action = { TextButton(onClick = onBrowseSources) { Text("Browse sources") } },
			)

			state.hasNoMatches -> EmptyState(
				title = "No matches",
				detail = "Nothing you have read matches \"${state.query}\".",
			)

			else -> LazyColumn(Modifier.fillMaxSize()) {
				items(state.entries, key = { it.mangaId }) { entry ->
					ContinueRow(
						entry = entry,
						imageHeaders = imageHeaders[entry.manga.sourceName].orEmpty(),
						onOpen = { onOpen(entry) },
						onRemove = { onRemove(entry.mangaId) },
						onFindElsewhere = { onFindElsewhere(entry) },
					)
					HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
				}
			}
		}
	}
}

@Composable
private fun Toolbar(state: ContinueUiState, onSearch: (String) -> Unit, searchFocus: FocusRequester) {
	Row(
		Modifier.fillMaxWidth().padding(AgehaSpacing.md),
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
		verticalAlignment = Alignment.CenterVertically,
	) {
		AgehaSearchField(
			value = state.query,
			onValueChange = onSearch,
			// Named for what it is. Every other search field in Ageha queries a website; this one
			// filters a list already in memory, and saying so is the difference between a user
			// waiting for it and a user trusting it.
			placeholder = "Quick search — titles you have read",
			modifier = Modifier.weight(1f).focusRequester(searchFocus),
		)
		Text(
			if (state.query.isEmpty()) {
				"${state.totalCount} read"
			} else {
				"${state.entries.size} of ${state.totalCount}"
			},
			style = AgehaTextStyles.metadata,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/**
 * One entry.
 *
 * Row actions appear on hover. This is a pointer-driven desktop window, not a phone: a permanently
 * visible Remove button on every row is forty buttons competing with the forty titles that are the
 * actual content, and a swipe gesture -- the phone answer -- has no meaning with a mouse.
 * "Find another source" is the exception and stays visible, because for an unavailable entry it is
 * the only thing that row can do.
 */
@Composable
private fun ContinueRow(
	entry: ContinueEntry,
	imageHeaders: Map<String, String>,
	onOpen: () -> Unit,
	onRemove: () -> Unit,
	onFindElsewhere: () -> Unit,
) {
	val interaction = remember { MutableInteractionSource() }
	val isHovered by interaction.collectIsHoveredAsState()
	Row(
		Modifier
			.fillMaxWidth()
			.testTag(CONTINUE_ROW_TAG)
			.hoverable(interaction)
			.background(
				if (isHovered) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent,
			)
			// An unavailable entry still opens -- into a cross-source search rather than a reader,
			// but clicking it must never be a no-op. A row that does nothing when clicked reads as
			// broken, which is exactly the impression this whole state exists to avoid.
			.clickable(onClick = if (entry.isSourceAvailable) onOpen else onFindElsewhere)
			.padding(horizontal = AgehaSpacing.lg, vertical = AgehaSpacing.sm),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
	) {
		MangaThumbnail(
			manga = entry.manga,
			imageHeaders = imageHeaders,
			progress = entry.progressPercent,
			modifier = Modifier.width(44.dp),
		)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				entry.manga.title,
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				// The chapter is the point of the row, so it shares the line with the source
				// rather than being pushed to a third one.
				listOfNotNull(entry.sourceTitle, entry.chapterLabel).joinToString("  ·  "),
				style = AgehaTextStyles.metadata,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Row(horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xs)) {
				if (!entry.isSourceAvailable) {
					Chip(
						"Source unavailable",
						MaterialTheme.colorScheme.errorContainer,
						MaterialTheme.colorScheme.onErrorContainer,
					)
				} else if (entry.isCaughtUp) {
					Chip(
						"Caught up",
						MaterialTheme.colorScheme.secondaryContainer,
						MaterialTheme.colorScheme.onSecondaryContainer,
					)
				}
			}
		}
		Text(
			relativeTime(entry.lastReadAt),
			style = AgehaTextStyles.metadata,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (!entry.isSourceAvailable) {
			TextButton(onClick = onFindElsewhere) { Text("Find another source") }
		}
		// Reserved whether or not it is drawn, so hovering a row does not shuffle its contents
		// sideways under the pointer.
		Box(Modifier.width(88.dp), contentAlignment = Alignment.CenterEnd) {
			if (isHovered) TextButton(onClick = onRemove) { Text("Remove") }
		}
	}
}

@Composable
private fun Chip(label: String, container: Color, content: Color) {
	Text(
		label,
		style = AgehaTextStyles.metadata,
		color = content,
		modifier = Modifier
			.clip(MaterialTheme.shapes.small)
			.background(container)
			.padding(horizontal = AgehaSpacing.sm, vertical = 2.dp),
	)
}

/**
 * "3 days ago", roughly.
 *
 * Rough on purpose. The exact minute someone stopped reading is never the question; "was this
 * yesterday or last spring" is, and a coarse answer is read at a glance where a timestamp has to
 * be decoded.
 */
internal fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
	if (epochMillis <= 0L) return ""
	val elapsed = (now - epochMillis).coerceAtLeast(0L)
	val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
	val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
	val days = TimeUnit.MILLISECONDS.toDays(elapsed)
	return when {
		minutes < 1 -> "just now"
		minutes < 60 -> "${minutes}m ago"
		hours < 24 -> "${hours}h ago"
		days < 7 -> "${days}d ago"
		days < 365 -> "${days / 7}w ago"
		else -> "${days / 365}y ago"
	}
}

/**
 * The Continue Reading shelf, for the library screen.
 *
 * Same data as the full screen, laid out as a row of the most recent few. It is here rather than
 * in `LibraryScreen` so that the shelf and the screen cannot drift apart -- they are two views of
 * one list, and the shelf's job is to make the screen unnecessary most of the time.
 */
@Composable
fun ContinueShelf(
	entries: List<ContinueEntry>,
	imageHeaders: Map<String, Map<String, String>>,
	onOpen: (ContinueEntry) -> Unit,
	onSeeAll: () -> Unit,
	modifier: Modifier = Modifier,
) {
	if (entries.isEmpty()) return
	Column(modifier.padding(vertical = AgehaSpacing.sm)) {
		Row(
			Modifier.fillMaxWidth().padding(horizontal = AgehaSpacing.lg),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				"Continue reading",
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.weight(1f),
			)
			TextButton(onClick = onSeeAll) { Text("See all") }
		}
		androidx.compose.foundation.lazy.LazyRow(
			contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AgehaSpacing.md),
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			items(entries, key = { it.mangaId }) { entry ->
				ShelfCard(entry, imageHeaders[entry.manga.sourceName].orEmpty()) { onOpen(entry) }
			}
		}
	}
}

@Composable
private fun ShelfCard(entry: ContinueEntry, imageHeaders: Map<String, String>, onOpen: () -> Unit) {
	Column(
		Modifier
			.width(112.dp)
			.clip(MaterialTheme.shapes.small)
			.clickable(onClick = onOpen)
			.padding(AgehaSpacing.xs),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
	) {
		Box {
			MangaThumbnail(
				manga = entry.manga,
				imageHeaders = imageHeaders,
				progress = entry.progressPercent,
				modifier = Modifier.fillMaxWidth(),
			)
			if (!entry.isSourceAvailable) {
				Chip(
					"Unavailable",
					MaterialTheme.colorScheme.errorContainer,
					MaterialTheme.colorScheme.onErrorContainer,
				)
			}
		}
		Text(
			entry.manga.title,
			style = AgehaTextStyles.mangaTitle,
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		// The chapter, not the source: on a shelf whose whole purpose is resuming, "Chapter 34" is
		// what tells someone whether this is the one they meant.
		Text(
			entry.chapterLabel ?: entry.sourceTitle,
			style = AgehaTextStyles.metadata,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Spacer(Modifier.height(1.dp))
	}
}

/**
 * Test tag for the end-to-end journey driver.
 *
 * The row is a manga title, a source name and a relative timestamp, none of which the driver can
 * predict before it has read something.
 */
const val CONTINUE_ROW_TAG = "continue-row"
