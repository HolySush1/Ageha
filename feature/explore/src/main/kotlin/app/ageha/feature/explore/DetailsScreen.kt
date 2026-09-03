package app.ageha.feature.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.CoverShape
import app.ageha.core.designsystem.SourceFailureNotice
import app.ageha.core.image.AgehaImages
import app.ageha.core.model.AgehaChapter
import coil3.compose.AsyncImage

/**
 * One manga: metadata on the left, chapters on the right.
 *
 * Two panes rather than one long scroll. A desktop window is wide, a chapter list is long, and
 * putting them in one column would mean scrolling past the description every time you want
 * chapter 214. The description pane scrolls on its own so the chapter list keeps its position.
 */
@Composable
fun DetailsScreen(
	state: DetailsUiState,
	onOpenChapter: (AgehaChapter) -> Unit,
	onDownloadChapter: (AgehaChapter) -> Unit,
	onDownloadAll: () -> Unit,
	onToggleCategory: (Int) -> Unit,
	onAddToLibrary: () -> Unit,
	onRemoveFromLibrary: () -> Unit,
	onSelectBranch: (String?) -> Unit,
	onRetry: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val manga = state.manga
	if (manga == null) {
		Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
		return
	}
	Row(modifier.fillMaxSize()) {
		Column(
			Modifier
				.width(360.dp)
				.fillMaxSize()
				.background(MaterialTheme.colorScheme.surfaceContainerLow)
				.verticalScroll(rememberScrollState())
				.padding(AgehaSpacing.lg),
			verticalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
		) {
			val cover = manga.largeCoverUrl ?: manga.coverUrl
			if (!cover.isNullOrEmpty()) {
				AsyncImage(
					model = AgehaImages.request(cover, state.imageHeaders),
					contentDescription = manga.title,
					contentScale = ContentScale.Crop,
					modifier = Modifier.width(200.dp).aspectRatio(2f / 3f).clip(CoverShape),
				)
			}
			// Selectable: a user copying a title to search elsewhere is a normal thing to do, and
			// on a desktop the expectation is that any text can be selected.
			SelectionContainer {
				Column(verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs)) {
					Text(manga.title, style = MaterialTheme.typography.headlineSmall)
					if (manga.altTitles.isNotEmpty()) {
						Text(
							manga.altTitles.joinToString(" - "),
							style = AgehaTextStyles.metadata,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}
			Text(
				listOfNotNull(
					manga.authors.takeIf { it.isNotEmpty() }?.joinToString(", "),
					manga.state?.name?.lowercase()?.replaceFirstChar { it.uppercase() },
					manga.rating?.let { "%.0f%%".format(it * 100) },
					manga.sourceName,
				).joinToString(" - "),
				style = AgehaTextStyles.metadata,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			LibraryActions(state, onAddToLibrary, onRemoveFromLibrary, onToggleCategory)

			if (manga.tags.isNotEmpty()) {
				androidx.compose.foundation.layout.FlowRow(
					horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
					verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
				) {
					for (tag in manga.tags) {
						AssistChip(onClick = {}, label = { Text(tag.title) })
					}
				}
			}

			manga.description?.takeIf { it.isNotBlank() }?.let { description ->
				HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
				SelectionContainer {
					Text(
						// Descriptions arrive as HTML from many sources. Rendering it properly is
						// a milestone-7 job; stripping tags is honest in the meantime and beats
						// showing the user raw markup.
						text = description.stripHtml(),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
			}
			state.failure?.let { SourceFailureNotice(it, onRetry = onRetry) }
		}

		Column(Modifier.fillMaxSize()) {
			ChapterHeader(state, onSelectBranch, onDownloadAll)
			HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
			when {
				state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
					CircularProgressIndicator()
				}

				state.chapters.isEmpty() -> Box(
					Modifier.fillMaxSize().padding(AgehaSpacing.xl),
					Alignment.Center,
				) {
					Text(
						"No chapters listed.",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				else -> LazyColumn(
					Modifier.fillMaxSize(),
					contentPadding = PaddingValues(vertical = AgehaSpacing.xs),
				) {
					items(state.chapters, key = { it.id }) { chapter ->
						ChapterRow(
							chapter = chapter,
							onClick = { onOpenChapter(chapter) },
							onDownload = { onDownloadChapter(chapter) },
						)
					}
				}
			}
		}
	}
}

@Composable
private fun LibraryActions(
	state: DetailsUiState,
	onAdd: () -> Unit,
	onRemove: () -> Unit,
	onToggleCategory: (Int) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(AgehaSpacing.sm)) {
		Row(horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm)) {
			if (state.isInLibrary) {
				// Not the destructive vermillion, on purpose. docs/DESIGN.md 5 reserves the filled
				// accent for irreversible actions, and this is not one: the removal is a soft
				// delete, reading history is a separate table that survives it, and re-adding
				// restores the entry. Dressing a reversible action as destructive is how people
				// learn to ignore the colour when it does matter.
				OutlinedButton(onClick = onRemove) { Text("Remove from library") }
			} else {
				Button(onClick = onAdd) { Text("Add to library") }
			}
		}
		if (state.categories.isNotEmpty()) {
			androidx.compose.foundation.layout.FlowRow(
				horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
			) {
				for (category in state.categories) {
					FilterChip(
						selected = category.id in state.inCategories,
						onClick = { onToggleCategory(category.id) },
						label = { Text(category.title) },
					)
				}
			}
		}
	}
}

@Composable
private fun ChapterHeader(
	state: DetailsUiState,
	onSelectBranch: (String?) -> Unit,
	onDownloadAll: () -> Unit,
) {
	Row(
		Modifier.fillMaxWidth().padding(AgehaSpacing.md),
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			"${state.chapters.size} chapters",
			style = MaterialTheme.typography.titleMedium,
		)
		if (state.chapters.isNotEmpty()) {
			androidx.compose.material3.TextButton(onClick = onDownloadAll) { Text("Download all") }
		}
		// Branches are the source's scanlation groups or languages. Only offered when there is
		// more than one -- a single-branch manga does not need a control that does nothing.
		if (state.branches.size > 1) {
			androidx.compose.foundation.layout.FlowRow(
				horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
			) {
				for (branch in state.branches) {
					FilterChip(
						selected = branch == state.selectedBranch,
						onClick = { onSelectBranch(branch) },
						label = { Text(branch ?: "Default") },
					)
				}
			}
		}
	}
}

@Composable
private fun ChapterRow(chapter: AgehaChapter, onClick: () -> Unit, onDownload: () -> Unit) {
	Row(
		Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = AgehaSpacing.lg, vertical = AgehaSpacing.sm),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
	) {
		Column(Modifier.weight(1f)) {
			Text(
				text = chapter.displayName(),
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			val meta = listOfNotNull(
				chapter.scanlator,
				chapter.uploadDate?.let { formatDate(it) },
			).joinToString(" - ")
			if (meta.isNotEmpty()) {
				Text(
					meta,
					style = AgehaTextStyles.metadata,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		androidx.compose.material3.TextButton(onClick = onDownload) { Text("Download") }
	}
}

/**
 * What to call a chapter.
 *
 * Sources are wildly inconsistent: some give a number and no title, some a title and no number,
 * some a title that already contains the number. Falling back through them beats showing
 * "Chapter null" or a bare url, which is what a naive `chapter.title` does on a third of sources.
 */
private fun AgehaChapter.displayName(): String {
	// Bound locally: `title` and `number` are properties of a data class from another module, so
	// Kotlin will not smart-cast them across the null checks below.
	val name = title
	val chapterNumber = number?.let(::formatNumber)
	return when {
		name != null && chapterNumber != null && !name.contains(chapterNumber) -> "$chapterNumber. $name"
		name != null -> name
		chapterNumber != null -> "Chapter $chapterNumber"
		else -> "Chapter"
	}
}

private fun formatNumber(value: Float): String =
	if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

private fun formatDate(epochMillis: Long): String =
	java.time.Instant.ofEpochMilli(epochMillis)
		.atZone(java.time.ZoneId.systemDefault())
		.toLocalDate()
		.toString()

/**
 * Enough HTML stripping to make a description readable.
 *
 * Not a parser and not trying to be. Descriptions are the one field sources reliably serve as
 * loose HTML, and a real renderer is milestone 7's problem; until then, dropping tags and
 * decoding the handful of entities that actually appear is better than showing markup.
 */
private fun String.stripHtml(): String = this
	.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
	.replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
	.replace(Regex("<[^>]+>"), "")
	.replace("&amp;", "&")
	.replace("&lt;", "<")
	.replace("&gt;", ">")
	.replace("&quot;", "\"")
	.replace("&#39;", "'")
	.replace("&nbsp;", " ")
	.trim()
