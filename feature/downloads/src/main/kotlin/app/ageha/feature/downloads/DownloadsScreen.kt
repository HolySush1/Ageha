package app.ageha.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.ageha.core.data.DownloadedTitle
import app.ageha.core.data.StorageReport
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.GhostButton
import app.ageha.core.designsystem.GlassTone
import app.ageha.core.designsystem.SectionHeader
import app.ageha.core.designsystem.coverPlaceholder
import app.ageha.core.designsystem.glassSurface
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.EmptyState

/**
 * The download queue.
 *
 * A flat list in queue order rather than a tree grouped by manga. The question this screen answers
 * is "what is Ageha doing and when will it finish", and grouping buries the running item inside a
 * collapsed row.
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
) {
	Column(
		modifier.fillMaxSize().verticalScroll(rememberScrollState()),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.lg),
	) {
		StorageCard(storage, Modifier.padding(horizontal = AgehaSpacing.lg, vertical = AgehaSpacing.md))

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
				verticalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
			) {
				for (title in storage.titles) {
					DownloadedTitleRow(title, onDelete = { onDeleteTitle(title) })
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
				Text(
					"$active running · $queued queued",
					style = AgehaTextStyles.monoMeta,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.weight(1f),
				)
				if (active + queued > 0) {
					GhostButton(onClick = onCancelAll) { Text("Cancel all", style = MaterialTheme.typography.labelMedium) }
				}
				if (jobs.any { it.status.isFinished }) {
					GhostButton(onClick = onClearFinished) { Text("Clear finished", style = MaterialTheme.typography.labelMedium) }
				}
			}
			Column(Modifier.padding(bottom = AgehaSpacing.xl)) {
				for (job in jobs) DownloadRow(job, onCancel, onRetry)
			}
		} else {
			Box(Modifier.padding(bottom = AgehaSpacing.xl))
		}
	}
}

@Composable
private fun DownloadRow(job: DownloadJob, onCancel: (String) -> Unit, onRetry: (String) -> Unit) {
	Row(
		Modifier.fillMaxWidth().padding(horizontal = AgehaSpacing.lg, vertical = AgehaSpacing.sm),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
	) {
		Column(Modifier.weight(1f)) {
			Text(
				job.manga.title,
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				listOfNotNull(job.chapter.title ?: job.chapter.number?.let { "Chapter $it" }, job.detail)
					.joinToString(" - "),
				style = AgehaTextStyles.metadata,
				color = if (job.status == DownloadStatus.FAILED) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		Box(Modifier.width(120.dp), contentAlignment = Alignment.Center) {
			when (job.status) {
				DownloadStatus.RUNNING -> LinearProgressIndicator(
					progress = { job.progress.fraction },
					modifier = Modifier.fillMaxWidth(),
				)
				else -> Text(
					job.status.label,
					style = AgehaTextStyles.readerHud,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		when (job.status) {
			DownloadStatus.QUEUED, DownloadStatus.RUNNING ->
				TextButton(onClick = { onCancel(job.key) }) { Text("Cancel") }
			DownloadStatus.FAILED, DownloadStatus.CANCELLED ->
				TextButton(onClick = { onRetry(job.key) }) { Text("Retry") }
			else -> Box(Modifier.width(72.dp))
		}
	}
}

/** Finished means "will not change again on its own". */
val DownloadStatus.isFinished: Boolean
	get() = this == DownloadStatus.COMPLETE ||
		this == DownloadStatus.PARTIAL ||
		this == DownloadStatus.FAILED ||
		this == DownloadStatus.CANCELLED

private val DownloadStatus.label: String
	get() = when (this) {
		DownloadStatus.QUEUED -> "Queued"
		DownloadStatus.RUNNING -> "..."
		DownloadStatus.COMPLETE -> "Done"
		DownloadStatus.PARTIAL -> "Incomplete"
		DownloadStatus.FAILED -> "Failed"
		DownloadStatus.CANCELLED -> "Cancelled"
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
private fun StorageCard(storage: StorageReport, modifier: Modifier = Modifier) {
	val skin = AgehaTheme.skin
	Column(
		modifier
			.fillMaxWidth()
			.glassSurface(MaterialTheme.shapes.large, GlassTone.PANEL)
			.padding(horizontal = 20.dp, vertical = 18.dp),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
	) {
		Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm)) {
			Text(formatBytes(storage.used), style = MaterialTheme.typography.titleLarge)
			Text(
				"of " + formatBytes(storage.capacity) + " used - " +
					storage.chapterCount + " chapters - " + storage.titles.size + " titles",
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
			if (thumbs > 0f) Box(Modifier.fillMaxHeight().weight(thumbs).background(skin.accentLine))
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
private fun DownloadedTitleRow(title: DownloadedTitle, onDelete: () -> Unit) {
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
				.coverPlaceholder(),
		)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				title.title,
				style = MaterialTheme.typography.titleMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				title.chapterCount.toString() + " chapters - " + formatBytes(title.bytes) +
					" - " + title.sourceName,
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
			GhostButton(onClick = { confirming = true }) {
				Text("Delete", style = MaterialTheme.typography.labelMedium, color = skin.inkFaint)
			}
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
