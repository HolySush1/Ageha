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
) {
	Column(modifier.fillMaxSize()) {
		Row(
			Modifier.fillMaxWidth().padding(AgehaSpacing.md),
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text("Downloads", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
			val active = jobs.count { it.status == DownloadStatus.RUNNING }
			val queued = jobs.count { it.status == DownloadStatus.QUEUED }
			if (active + queued > 0) {
				Text(
					"$active running, $queued queued",
					style = AgehaTextStyles.metadata,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				TextButton(onClick = onCancelAll) { Text("Cancel all") }
			}
			if (jobs.any { it.status.isFinished }) {
				TextButton(onClick = onClearFinished) { Text("Clear finished") }
			}
		}
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
		if (jobs.isEmpty()) {
			EmptyState(
				title = "Nothing downloading",
				detail = "Chapters you download for offline reading appear here. " +
					"They are saved as ordinary CBZ files, readable in any comic reader.",
			)
		} else {
			LazyColumn(Modifier.fillMaxSize()) {
				items(jobs, key = { it.key }) { job -> DownloadRow(job, onCancel, onRetry) }
			}
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
