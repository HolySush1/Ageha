package app.ageha.feature.explore

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import app.ageha.core.data.ChapterReadState
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.AgehaTheme
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
	/**
	 * Resume reading, from the button beside the library one.
	 *
	 * Routed out to the shell rather than answered here, because "where does this resume" is a
	 * database question with three possible answers -- open the reader, fetch a chapter list
	 * first, or offer a cross-source search for a source that has gone away -- and all three are
	 * navigation. See `HistoryRepository.resume`.
	 */
	onContinueReading: () -> Unit = {},
	/** Mark this chapter and everything before it read. */
	onMarkReadThrough: (AgehaChapter) -> Unit = {},
	/** Mark this chapter and everything after it unread. */
	onMarkUnreadFrom: (AgehaChapter) -> Unit = {},
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

			LibraryActions(state, onAddToLibrary, onRemoveFromLibrary, onContinueReading, onToggleCategory)

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
			val chapterList = rememberLazyListState()
			// Open on the chapter you were last reading, not on chapter one.
			//
			// The guard is the whole subtlety. The position arrives a moment after the chapter
			// list does -- two different queries -- so the effect has to be allowed to re-run
			// until it has something to scroll to, and then must never run again for this
			// manga and branch. Without the second half, marking a chapter read would yank the
			// list back under the pointer that had just right-clicked something else.
			val scrollKey = manga.id to state.selectedBranch
			var scrolledFor by remember { mutableStateOf<Pair<Long, String?>?>(null) }
			val target = state.marker?.index ?: -1
			LaunchedEffect(scrollKey, target) {
				if (target >= 0 && scrolledFor != scrollKey) {
					// A couple of rows of lead-in, so the chapter lands *in* the list rather than
					// flush against its top edge with nothing above it to say where you are.
					chapterList.scrollToItem((target - CHAPTER_SCROLL_LEAD).coerceAtLeast(0))
					scrolledFor = scrollKey
				}
			}
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
					state = chapterList,
					contentPadding = PaddingValues(vertical = AgehaSpacing.xs),
				) {
					itemsIndexed(state.chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
						ChapterRow(
							chapter = chapter,
							readState = state.marker?.stateOf(index) ?: ChapterReadState.UNREAD,
							onClick = { onOpenChapter(chapter) },
							onDownload = { onDownloadChapter(chapter) },
							onMarkRead = { onMarkReadThrough(chapter) },
							onMarkUnread = { onMarkUnreadFrom(chapter) },
						)
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryActions(
	state: DetailsUiState,
	onAdd: () -> Unit,
	onRemove: () -> Unit,
	onContinueReading: () -> Unit,
	onToggleCategory: (Int) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(AgehaSpacing.sm)) {
		// Wrapping, because this pane is 360dp wide and now holds two buttons rather than one.
		// A Row would push "Continue reading" off the edge at the first long localisation of
		// "Remove from library"; here it drops to a second line instead.
		FlowRow(
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
			verticalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			// The filled button is whichever one is the point of the visit.
			//
			// For something already saved and already started, that is resuming it -- so the
			// emphasis goes here and "Remove from library" stays the outlined afterthought it was.
			// For anything else the primary action is still saving it.
			if (state.marker != null) {
				Button(onClick = onContinueReading) { Text("Continue reading") }
			}
			if (state.isInLibrary) {
				// Not the destructive vermillion, on purpose. docs/DESIGN.md 5 reserves the filled
				// accent for irreversible actions, and this is not one: the removal is a soft
				// delete, reading history is a separate table that survives it, and re-adding
				// restores the entry. Dressing a reversible action as destructive is how people
				// learn to ignore the colour when it does matter.
				OutlinedButton(onClick = onRemove) { Text("Remove from library") }
			} else if (state.marker != null) {
				OutlinedButton(onClick = onAdd) { Text("Add to library") }
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
		// "Chapter 214 · 45%", when there is a position to report.
		//
		// The chapter number is the half that was missing. A percentage on its own says how much
		// is left and nothing about where you are, which for a 900-chapter series is the less
		// useful of the two numbers; together they answer both without needing a total the source
		// has not necessarily published.
		state.positionLabel?.let { position ->
			Text(
				position,
				style = AgehaTextStyles.monoMeta,
				color = AgehaTheme.skin.inkFaint,
			)
		}
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

/**
 * One chapter, and whether it has been read.
 *
 * ## Why the read state is a colour and a word rather than a checkbox
 *
 * There is nothing to check. Ageha stores one reading *position* per manga rather than a flag per
 * chapter -- the Android schema it stays importable from has no per-chapter table -- so what this
 * row can honestly show is where a chapter sits relative to that position. A checkbox would invite
 * ticking chapter 40 and leaving 39 unticked, which this model cannot represent and which the
 * menu below therefore does not offer.
 *
 * ## Why the menu is right-click rather than a third button
 *
 * The row already carries a click and a Download button, and marking read is a rare action next
 * to both. On a desktop the secondary click is where rare per-item actions live, and putting it
 * there costs the row no width -- which matters, because a chapter title is the longest thing on
 * this screen and every pixel spent on chrome is a pixel of somebody's chapter name ellipsised.
 */
@Composable
private fun ChapterRow(
	chapter: AgehaChapter,
	readState: ChapterReadState,
	onClick: () -> Unit,
	onDownload: () -> Unit,
	onMarkRead: () -> Unit,
	onMarkUnread: () -> Unit,
) {
	ContextMenuArea(
		items = {
			listOf(
				// Both labels say how far the action reaches, because both reach past the row
				// that was clicked and a menu that did not say so would look like it had marked
				// the wrong forty chapters.
				ContextMenuItem("Mark read up to here", onMarkRead),
				ContextMenuItem("Mark unread from here", onMarkUnread),
			)
		},
	) {
		Row(
			Modifier
				.fillMaxWidth()
				.testTag(CHAPTER_ROW_TAG)
				.clickable(onClick = onClick)
				.padding(horizontal = AgehaSpacing.lg, vertical = AgehaSpacing.sm),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
		) {
			Column(Modifier.weight(1f)) {
				Text(
					text = chapter.displayName(),
					style = MaterialTheme.typography.bodyMedium,
					// A read chapter recedes rather than disappearing. The list is scanned for
					// the boundary between read and unread, and the fastest way to find it is a
					// change in weight of the text itself -- no marker to look for, no legend to
					// learn.
					color = if (readState == ChapterReadState.READ) {
						MaterialTheme.colorScheme.onSurfaceVariant
					} else {
						MaterialTheme.colorScheme.onSurface
					},
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
			// Only the two states worth a word get one. Labelling every other row "unread" would
			// put a mono cap on most of a 900-row list to say nothing, which is the same reasoning
			// the library card uses for its own state word.
			when (readState) {
				ChapterReadState.READING -> ChapterState("reading", AgehaTheme.skin.accent)
				ChapterReadState.READ -> ChapterState("read", AgehaTheme.skin.inkFaint)
				ChapterReadState.UNREAD -> Unit
			}
			androidx.compose.material3.TextButton(onClick = onDownload) { Text("Download") }
		}
	}
}

@Composable
private fun ChapterState(label: String, color: Color) {
	Text(label, style = AgehaTextStyles.monoMeta, color = color)
}

/** How many rows of lead-in to leave above the chapter the list opens on. */
private const val CHAPTER_SCROLL_LEAD = 2

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

/**
 * Test tag for the end-to-end journey driver.
 *
 * A chapter row is named by whatever the source calls it, so there is no fixed string to find it
 * by. See [SOURCE_ROW_TAG] for the reasoning.
 */
const val CHAPTER_ROW_TAG = "chapter-row"
