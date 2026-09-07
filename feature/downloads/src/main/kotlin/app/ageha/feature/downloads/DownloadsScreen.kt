package app.ageha.feature.downloads

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ageha.core.data.DownloadedTitle
import app.ageha.core.data.StorageReport
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.GhostButton
import app.ageha.core.designsystem.GlassTone
import app.ageha.core.designsystem.SectionHeader
import app.ageha.core.designsystem.coverPlaceholder
import app.ageha.core.designsystem.glassSurface

/**
 * Downloads: what is on this device, and what is still arriving.
 *
 * The handoff draws one list of rows, each carrying a thumbnail, a title block, a progress column
 * and a state chip. Ageha has two populations rather than one -- chapters already written to disk,
 * which survive a restart, and queue jobs, which do not -- so it draws the same row twice under
 * two headings. Folding them into a single list would mean either forgetting the library between
 * launches or inventing queue rows for files that finished months ago.
 */
@Composable
fun DownloadsScreen(
	jobs: List<DownloadJob>,
	onCancel: (String) -> Unit,
	onRetry: (String) -> Unit,
	onCancelAll: () -> Unit,
	onClearFinished: () -> Unit,
	modifier: Modifier = Modifier,
	/** What is already on disk. See `DownloadInventory` for why this is not the queue. */
	storage: StorageReport = StorageReport.EMPTY,
	onDeleteTitle: (DownloadedTitle) -> Unit = {},
	onPause: (String) -> Unit = {},
	onResume: (String) -> Unit = {},
	/** Whether the one queue button reads "Pause all" or "Resume all". */
	hasRunningWork: Boolean = false,
	onPauseAll: () -> Unit = {},
	onResumeAll: () -> Unit = {},
	/** The row thumbnail opens the reader, as the handoff specifies. */
	onOpenTitle: (DownloadedTitle) -> Unit = {},
) {
	var reclaiming by remember { mutableStateOf(false) }
	Column(
		modifier.fillMaxSize().verticalScroll(rememberScrollState()),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.lg),
	) {
		StorageCard(
			storage = storage,
			hasRunningWork = hasRunningWork,
			hasQueue = jobs.any { it.status.isOutstanding },
			onPauseAll = onPauseAll,
			onResumeAll = onResumeAll,
			onFreeUpSpace = { reclaiming = true },
			modifier = Modifier.padding(horizontal = AgehaSpacing.lg, vertical = AgehaSpacing.md),
		)

		if (reclaiming) {
			ReclaimSheet(
				storage = storage,
				onDismiss = { reclaiming = false },
				onDelete = onDeleteTitle,
				modifier = Modifier.padding(horizontal = AgehaSpacing.lg),
			)
		}

		SectionHeader(
			label = "On this device",
			count = if (storage.titles.size == 1) "1 title" else "${storage.titles.size} titles",
			modifier = Modifier.padding(horizontal = AgehaSpacing.lg),
		)
		if (storage.titles.isEmpty()) {
			Text(
				"Nothing downloaded yet. Chapters you save for offline reading are written as " +
					"ordinary CBZ files, readable in any comic reader.",
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = AgehaSpacing.lg),
			)
		} else {
			// A Column rather than a LazyColumn, because this one is inside a scrolling parent
			// and nesting two scrollables on the same axis is a measurement error, not a layout.
			// A downloads list is tens of rows; the laziness would buy nothing.
			Column(
				Modifier.padding(horizontal = AgehaSpacing.lg),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				for (title in storage.titles) {
					DownloadedTitleRow(
						title = title,
						onOpen = { onOpenTitle(title) },
						onDelete = { onDeleteTitle(title) },
					)
				}
			}
		}

		// The live queue, below what is already here. It is the smaller half of this screen now
		// and usually empty -- what you *have* outlives what is arriving, so it goes second.
		if (jobs.isNotEmpty()) {
			SectionHeader(
				label = "Downloading now",
				count = "${jobs.size} chapters",
				modifier = Modifier.padding(horizontal = AgehaSpacing.lg),
			)
			Row(
				Modifier.fillMaxWidth().padding(horizontal = AgehaSpacing.lg),
				horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
				verticalAlignment = Alignment.CenterVertically,
			) {
				val active = jobs.count { it.status == DownloadStatus.RUNNING }
				val queued = jobs.count { it.status == DownloadStatus.QUEUED }
				val paused = jobs.count { it.status == DownloadStatus.PAUSED }
				Text(
					listOfNotNull(
						"$active running",
						"$queued queued",
						paused.takeIf { it > 0 }?.let { "$it paused" },
					).joinToString(" · "),
					style = AgehaTextStyles.monoMeta,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.weight(1f),
				)
				if (jobs.any { it.status.isOutstanding }) {
					GhostButton(onClick = onCancelAll) {
						Text("Cancel all", style = MaterialTheme.typography.labelMedium)
					}
				}
				if (jobs.any { !it.status.isOutstanding }) {
					GhostButton(onClick = onClearFinished) {
						Text("Clear finished", style = MaterialTheme.typography.labelMedium)
					}
				}
			}
			Column(
				Modifier.padding(horizontal = AgehaSpacing.lg, vertical = AgehaSpacing.xs),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				for (job in jobs) QueueRow(job, onCancel, onRetry, onPause, onResume)
			}
		}
		Box(Modifier.padding(bottom = AgehaSpacing.xl))
	}
}

/**
 * One queued chapter, in the handoff's row shape.
 *
 * Left to right: the title block, a flexible progress column with the state on the left and the
 * page count on the right, a state chip, and the destructive control at the end. The progress
 * column is where this differs most from a plain list -- a bar with nothing written beside it says
 * "something is happening" and nothing else, and the two mono lines above it are what turn it into
 * a position somebody can act on.
 */
@Composable
private fun QueueRow(
	job: DownloadJob,
	onCancel: (String) -> Unit,
	onRetry: (String) -> Unit,
	onPause: (String) -> Unit,
	onResume: (String) -> Unit,
) {
	val skin = AgehaTheme.skin
	Row(
		Modifier
			.fillMaxWidth()
			.glassSurface(MaterialTheme.shapes.large, GlassTone.CHROME)
			.padding(horizontal = 18.dp, vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(18.dp),
	) {
		Column(Modifier.width(290.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				job.manga.title,
				style = MaterialTheme.typography.titleMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				listOfNotNull(
					job.chapter.title ?: job.chapter.number?.let { "Chapter $it" },
					job.detail,
				).joinToString(" · "),
				style = AgehaTextStyles.monoMeta,
				color = if (job.status == DownloadStatus.FAILED) {
					MaterialTheme.colorScheme.error
				} else {
					skin.inkFaint
				},
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Row(Modifier.fillMaxWidth()) {
				Text(
					job.status.progressLabel(),
					style = AgehaTextStyles.monoMeta,
					color = skin.inkFaint,
					modifier = Modifier.weight(1f),
				)
				if (job.progress.total > 0) {
					Text(
						"${job.progress.completed} / ${job.progress.total}",
						style = AgehaTextStyles.monoMeta,
						color = skin.inkFaint,
					)
				}
			}
			ProgressTrack(
				fraction = job.progress.fraction,
				// The handoff's three bar colours: complete is the accent, downloading is amber,
				// paused is the faintest ink there is. Three states, three weights of attention.
				colour = when (job.status) {
					DownloadStatus.COMPLETE -> skin.accent
					DownloadStatus.RUNNING, DownloadStatus.QUEUED -> skin.warn
					else -> skin.inkFaint
				},
			)
		}
		when (job.status) {
			DownloadStatus.QUEUED, DownloadStatus.RUNNING -> StateChip(
				label = "Pause",
				border = skin.warn,
				ink = skin.warn,
				onClick = { onPause(job.key) },
			)

			DownloadStatus.PAUSED -> StateChip(
				label = "Resume",
				border = skin.lineStrong,
				ink = MaterialTheme.colorScheme.onSurfaceVariant,
				onClick = { onResume(job.key) },
			)

			DownloadStatus.FAILED, DownloadStatus.CANCELLED -> StateChip(
				label = "Retry",
				border = skin.lineStrong,
				ink = MaterialTheme.colorScheme.onSurfaceVariant,
				onClick = { onRetry(job.key) },
			)

			// Inert, as the handoff specifies: a chip that reports rather than acts. It still
			// draws, because a row whose chip disappears when it finishes changes width at the
			// exact moment the eye is drawn to it.
			DownloadStatus.COMPLETE, DownloadStatus.PARTIAL -> StateChip(
				label = if (job.status == DownloadStatus.COMPLETE) "Complete" else "Incomplete",
				border = skin.lineStrong,
				ink = MaterialTheme.colorScheme.onSurfaceVariant,
				onClick = null,
			)
		}
		TrashButton(
			label = "Cancel ${job.manga.title}",
			enabled = job.status.isOutstanding,
			onClick = { onCancel(job.key) },
		)
	}
}

/** The mono line above a queue row's bar. */
private fun DownloadStatus.progressLabel(): String = when (this) {
	DownloadStatus.QUEUED -> "Queued"
	DownloadStatus.RUNNING -> "Downloading"
	DownloadStatus.PAUSED -> "Paused"
	DownloadStatus.COMPLETE -> "Complete"
	DownloadStatus.PARTIAL -> "Incomplete"
	DownloadStatus.FAILED -> "Failed"
	DownloadStatus.CANCELLED -> "Cancelled"
}

/** The handoff's 3px bar on a `--glass` track. */
@Composable
private fun ProgressTrack(fraction: Float, colour: Color) {
	Box(
		Modifier
			.fillMaxWidth()
			.height(3.dp)
			.clip(MaterialTheme.shapes.extraSmall)
			.background(AgehaTheme.skin.inset),
	) {
		Box(
			Modifier
				.fillMaxWidth(fraction.coerceIn(0f, 1f))
				.fillMaxHeight()
				.clip(MaterialTheme.shapes.extraSmall)
				.background(colour),
		)
	}
}

/**
 * A row's state chip.
 *
 * @param onClick null makes it inert -- drawn, and not a target. That is a real state in this
 *   design rather than a disabled button: "Complete" is a label the handoff happens to draw in the
 *   shape of a chip so the column stays aligned.
 */
@Composable
private fun StateChip(label: String, border: Color, ink: Color, onClick: (() -> Unit)?) {
	val shape = AgehaTheme.skin.chip
	Box(
		Modifier
			.clip(shape)
			.border(1.dp, border, shape)
			.then(
				if (onClick != null) {
					Modifier.clickable(role = Role.Button, onClick = onClick)
				} else {
					Modifier
				},
			)
			.padding(horizontal = 14.dp, vertical = 6.dp),
	) {
		Text(label, style = MaterialTheme.typography.labelMedium, color = ink)
	}
}

/**
 * The handoff's 30px ghost delete, with a 1.5px-stroke trash glyph.
 *
 * Drawn rather than taken from the Material icon set, and this is one place that is worth the
 * lines: the set's `Delete` is a filled 24dp glyph on a 48dp touch target, which in a 14dp row of
 * hairlines reads as a solid blob. A stroked outline at the weight everything else on the screen
 * is drawn at is what makes it belong to the row rather than to Material.
 *
 * The name lives in `contentDescription` because the button has no text at all -- an icon-only
 * control with no accessible name is a control only the person who wrote it can use.
 */
@Composable
private fun TrashButton(label: String, enabled: Boolean, onClick: () -> Unit) {
	val skin = AgehaTheme.skin
	val shape = MaterialTheme.shapes.medium
	Box(
		Modifier
			.size(30.dp)
			.clip(shape)
			.border(1.dp, skin.lineStrong, shape)
			.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
			.semantics { contentDescription = label },
		contentAlignment = Alignment.Center,
	) {
		val ink = if (enabled) skin.inkFaint else skin.line
		Canvas(Modifier.size(13.dp)) {
			val stroke = 1.5.dp.toPx()
			val lidY = size.height * 0.22f
			// The lid, the handle above it, the body's two sides and its floor. Seven strokes is
			// the fewest that still reads as a bin rather than as a bracket.
			drawLine(ink, Offset(0f, lidY), Offset(size.width, lidY), stroke, StrokeCap.Round)
			drawLine(
				ink,
				Offset(size.width * 0.34f, lidY),
				Offset(size.width * 0.34f, 0f),
				stroke,
				StrokeCap.Round,
			)
			drawLine(
				ink,
				Offset(size.width * 0.34f, 0f),
				Offset(size.width * 0.66f, 0f),
				stroke,
				StrokeCap.Round,
			)
			drawLine(
				ink,
				Offset(size.width * 0.66f, 0f),
				Offset(size.width * 0.66f, lidY),
				stroke,
				StrokeCap.Round,
			)
			drawLine(
				ink,
				Offset(size.width * 0.16f, lidY),
				Offset(size.width * 0.22f, size.height),
				stroke,
				StrokeCap.Round,
			)
			drawLine(
				ink,
				Offset(size.width * 0.84f, lidY),
				Offset(size.width * 0.78f, size.height),
				stroke,
				StrokeCap.Round,
			)
			drawLine(
				ink,
				Offset(size.width * 0.22f, size.height),
				Offset(size.width * 0.78f, size.height),
				stroke,
				StrokeCap.Round,
			)
		}
	}
}

/**
 * How much room the library is taking, and what of.
 *
 * ## Why the capacity is the volume's, not a number in the design
 *
 * The handoff prints "of 64 GB used", which is a figure invented for a mockup. Reading it off the
 * filesystem costs one call and makes the bar mean something: the same library reads as nothing on
 * a 2TB desktop and as a problem on a 128GB laptop, and that difference is the entire reason
 * somebody opens this screen.
 *
 * ## Why pages and thumbnails are separate segments
 *
 * They behave differently. Pages are the user's library and only they can decide to lose them;
 * thumbnails are an evictable cache that rebuilds itself. Folding the two into one "used" figure
 * invites people to delete chapters to reclaim space the cache would have given back for free --
 * so the legend names both, and the cache is drawn in the accent's border tint rather than the
 * accent, to say it matters less.
 */
@Composable
private fun StorageCard(
	storage: StorageReport,
	hasRunningWork: Boolean,
	hasQueue: Boolean,
	onPauseAll: () -> Unit,
	onResumeAll: () -> Unit,
	onFreeUpSpace: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val skin = AgehaTheme.skin
	Row(
		modifier
			.fillMaxWidth()
			.glassSurface(MaterialTheme.shapes.large, GlassTone.PANEL)
			.padding(horizontal = 20.dp, vertical = 18.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xl),
	) {
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AgehaSpacing.md)) {
			Row(
				verticalAlignment = Alignment.Bottom,
				horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
			) {
				Text(formatBytes(storage.used), style = MaterialTheme.typography.titleLarge)
				Text(
					"of " + formatBytes(storage.capacity) + " used · " +
						storage.chapterCount + " chapters · " + storage.titles.size + " titles",
					style = AgehaTextStyles.monoMeta,
					color = skin.inkFaint,
					modifier = Modifier.padding(bottom = 2.dp),
				)
			}
			// A stacked bar rather than two. Both segments measure against the same capacity, so
			// putting them on one track is what lets the eye compare them at all -- and the empty
			// remainder is the number this screen is really about.
			Row(
				Modifier
					.fillMaxWidth()
					.height(6.dp)
					.clip(MaterialTheme.shapes.extraSmall)
					.background(skin.inset),
			) {
				val capacity = storage.capacity.coerceAtLeast(1L).toFloat()
				val pages = (storage.pages / capacity).coerceIn(0f, 1f)
				val thumbs = (storage.thumbnails / capacity).coerceIn(0f, 1f)
				if (pages > 0f) Box(Modifier.fillMaxHeight().weight(pages).background(skin.accent))
				if (thumbs > 0f) {
					Box(Modifier.fillMaxHeight().weight(thumbs).background(skin.accentLine))
				}
				// Never zero. A weight of exactly 0 makes the remainder vanish and the two filled
				// segments stretch to the full width, which would draw a full disk on an empty one.
				Box(Modifier.fillMaxHeight().weight((1f - pages - thumbs).coerceAtLeast(0.0001f)))
			}
			Row(horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.lg)) {
				LegendKey(skin.accent, "pages", formatBytes(storage.pages))
				LegendKey(skin.accentLine, "thumbnails", formatBytes(storage.thumbnails))
				Box(Modifier.weight(1f))
				Text(
					formatBytes(storage.free) + " free",
					style = AgehaTextStyles.monoMeta,
					color = skin.inkFaint,
				)
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm)) {
			// One button whose label follows the queue, as the handoff draws it. Two buttons would
			// leave one of them inert whichever state the queue was in.
			GhostButton(
				onClick = if (hasRunningWork) onPauseAll else onResumeAll,
				enabled = hasQueue,
			) {
				Text(
					if (hasRunningWork) "Pause all" else "Resume all",
					style = MaterialTheme.typography.labelMedium,
					color = if (hasQueue) {
						MaterialTheme.colorScheme.onSurface
					} else {
						skin.inkFaint
					},
				)
			}
			TintedButton(
				label = "Free up space",
				enabled = storage.titles.isNotEmpty(),
				onClick = onFreeUpSpace,
			)
		}
	}
}

/** The handoff's `--accent-soft` over `--accent-line` action. Used for exactly one thing. */
@Composable
private fun TintedButton(label: String, enabled: Boolean, onClick: () -> Unit) {
	val shape = MaterialTheme.shapes.medium
	Box(
		Modifier
			.clip(shape)
			.background(MaterialTheme.colorScheme.primaryContainer)
			.border(1.dp, AgehaTheme.skin.accentLine, shape)
			.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
			.padding(horizontal = AgehaSpacing.lg, vertical = AgehaSpacing.md),
	) {
		Text(
			label,
			style = MaterialTheme.typography.labelMedium,
			color = if (enabled) {
				MaterialTheme.colorScheme.onSurface
			} else {
				AgehaTheme.skin.inkFaint
			},
		)
	}
}

/**
 * "Free up space", as a reclaim sheet.
 *
 * WIRING.md lists this as unbuilt and describes it as *"delete read chapters, oldest first,
 * per-title selection, and report the space it will free"*. Two of those three are honest here and
 * one is not: Ageha's inventory is the filesystem, which knows a title's size and when it was last
 * written but nothing about whether it was read -- read state lives per *manga*, and the download
 * directory is keyed by a sanitised title with no id to join on.
 *
 * So the sort is oldest-written first, which is the same intent with the data that exists: the
 * chapters least likely to be wanted back are the ones nothing has touched in months. Everything
 * else is as specified -- per-title selection, a running total of what it will free, and one
 * confirm rather than a click per row.
 */
@Composable
private fun ReclaimSheet(
	storage: StorageReport,
	onDismiss: () -> Unit,
	onDelete: (DownloadedTitle) -> Unit,
	modifier: Modifier = Modifier,
) {
	val skin = AgehaTheme.skin
	val oldestFirst = remember(storage.titles) { storage.titles.sortedBy { it.lastModified } }
	var selected by remember(storage.titles) { mutableStateOf(emptySet<String>()) }
	val freeing = oldestFirst.filter { it.key in selected }.sumOf { it.bytes }
	Column(
		modifier
			.fillMaxWidth()
			.glassSurface(MaterialTheme.shapes.large, GlassTone.RAISED)
			.padding(20.dp),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
	) {
		Text("Free up space", style = MaterialTheme.typography.headlineSmall)
		Text(
			"Oldest downloads first. Choose what to remove; the titles stay in your library and " +
				"can be downloaded again. This cannot be undone from inside Ageha.",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		for (title in oldestFirst) {
			val isSelected = title.key in selected
			Row(
				Modifier
					.fillMaxWidth()
					.clip(MaterialTheme.shapes.medium)
					.background(
						if (isSelected) {
							MaterialTheme.colorScheme.primaryContainer
						} else {
							Color.Transparent
						},
					)
					.clickable(role = Role.Checkbox) {
						selected = if (isSelected) selected - title.key else selected + title.key
					}
					.padding(horizontal = AgehaSpacing.md, vertical = AgehaSpacing.sm),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
			) {
				Text(
					title.title,
					style = MaterialTheme.typography.bodyMedium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				Text(
					"${title.chapterCount} ch · ${formatBytes(title.bytes)}",
					style = AgehaTextStyles.monoMeta,
					color = skin.inkFaint,
				)
			}
		}
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			Text(
				if (freeing > 0) "Frees ${formatBytes(freeing)}" else "Nothing selected",
				style = AgehaTextStyles.monoMeta,
				color = skin.inkFaint,
				modifier = Modifier.weight(1f),
			)
			GhostButton(onClick = onDismiss) {
				Text("Cancel", style = MaterialTheme.typography.labelMedium)
			}
			GhostButton(
				enabled = freeing > 0,
				onClick = {
					oldestFirst.filter { it.key in selected }.forEach(onDelete)
					onDismiss()
				},
			) {
				Text(
					"Delete selected",
					style = MaterialTheme.typography.labelMedium,
					color = if (freeing > 0) skin.accent else skin.inkFaint,
				)
			}
		}
	}
}

@Composable
private fun LegendKey(colour: Color, label: String, value: String) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
	) {
		Box(Modifier.size(8.dp).clip(MaterialTheme.shapes.extraSmall).background(colour))
		Text(label + " " + value, style = AgehaTextStyles.monoMeta, color = AgehaTheme.skin.inkFaint)
	}
}

/**
 * One downloaded title, with the delete behind a confirm.
 *
 * The handoff's trash button deletes on the first click. WIRING.md flags that as wrong, and it is:
 * this removes files the user chose to keep for offline reading, it cannot be undone from inside
 * the app, and the button sits at the end of a row they may be pointing at for another reason. The
 * second click names the number of chapters, so the confirm carries information rather than being
 * friction for its own sake.
 */
@Composable
private fun DownloadedTitleRow(
	title: DownloadedTitle,
	onOpen: () -> Unit,
	onDelete: () -> Unit,
) {
	val skin = AgehaTheme.skin
	var confirming by remember { mutableStateOf(false) }
	Row(
		Modifier
			.fillMaxWidth()
			.glassSurface(MaterialTheme.shapes.large, GlassTone.CHROME)
			.padding(horizontal = 18.dp, vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(18.dp),
	) {
		Box(
			Modifier
				.width(44.dp)
				.height(62.dp)
				.clip(MaterialTheme.shapes.small)
				.coverPlaceholder()
				.clickable(role = Role.Button, onClick = onOpen)
				.semantics { contentDescription = "Open ${title.title}" },
		)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				title.title,
				style = MaterialTheme.typography.titleMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				title.chapterCount.toString() + " chapters · " + formatBytes(title.bytes) +
					" · " + title.sourceName,
				style = AgehaTextStyles.monoMeta,
				color = skin.inkFaint,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		if (confirming) {
			GhostButton(onClick = { confirming = false }) {
				Text("Cancel", style = MaterialTheme.typography.labelMedium)
			}
			GhostButton(
				onClick = {
					confirming = false
					onDelete()
				},
			) {
				Text(
					"Delete " + title.chapterCount + " chapters",
					style = MaterialTheme.typography.labelMedium,
					color = skin.accent,
				)
			}
		} else {
			StateChip(
				label = "Complete",
				border = skin.lineStrong,
				ink = MaterialTheme.colorScheme.onSurfaceVariant,
				onClick = null,
			)
			TrashButton(
				label = "Delete downloaded chapters for ${title.title}",
				enabled = true,
				onClick = { confirming = true },
			)
		}
	}
}

/**
 * Bytes as a person reads them.
 *
 * Binary units under decimal names, which is what every desktop file manager on Windows and Linux
 * does. Being right about SI here would mean this screen disagreeing with the number Explorer
 * shows for the same directory -- and the user would believe Explorer.
 */
private fun formatBytes(bytes: Long): String {
	if (bytes <= 0) return "0 MB"
	val units = listOf("B", "KB", "MB", "GB", "TB")
	var value = bytes.toDouble()
	var unit = 0
	while (value >= 1024 && unit < units.lastIndex) {
		value /= 1024
		unit++
	}
	// One decimal below 10 and none above: "1.4 GB" is useful, "847.3 MB" is false precision on a
	// figure that changes every time a chapter finishes.
	return if (value < 10 && unit > 1) {
		"%.1f %s".format(value, units[unit])
	} else {
		"%.0f %s".format(value, units[unit])
	}
}
