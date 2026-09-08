package app.ageha.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.ageha.core.designsystem.AgehaMotion
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.motionTween
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Something the application needs to tell the user that is not tied to a screen. */
data class Notice(
	val id: Long,
	val title: String,
	/** The full account. May be several lines; rendered monospaced and scrollable. */
	val detail: String? = null,
	val isError: Boolean = false,
)

/**
 * Application-level messages.
 *
 * Backup import and opening a local archive both produce an outcome worth reading -- what was
 * restored, what was skipped and why, or that a `.cbr` is a RAR and repackaging works. Until now
 * both printed that to stdout, which in a windowed application means nowhere: the process is
 * launched from a desktop shortcut and nobody sees its console.
 *
 * Deliberately not a transient snackbar. A backup import's report is a dozen lines the user may
 * want to read twice and copy out of; a message that disappears after four seconds is the wrong
 * shape for it. These stay until dismissed.
 */
class NoticeCenter {

	private val nextId = AtomicLong(1)
	private val _notices = MutableStateFlow<List<Notice>>(emptyList())
	val notices: StateFlow<List<Notice>> = _notices.asStateFlow()

	fun post(title: String, detail: String? = null, isError: Boolean = false) {
		_notices.update { it + Notice(nextId.getAndIncrement(), title, detail, isError) }
	}

	fun dismiss(id: Long) {
		_notices.update { list -> list.filterNot { it.id == id } }
	}
}

/**
 * The notice stack, bottom-right.
 *
 * Bottom-right rather than centred, and not modal: none of these block anything. A user who has
 * just imported a backup should be able to start browsing their library while the report is still
 * on screen beside it.
 */
@Composable
fun NoticeOverlay(
	notices: List<Notice>,
	onDismiss: (Long) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (notices.isEmpty()) return
	Column(
		modifier = modifier
			.padding(AgehaSpacing.lg)
			// The stack closes up rather than jumping when one of three notices is dismissed.
			// These are stacked bottom-right, so dismissing the top one moves the two below it --
			// and a user who has just clicked "Dismiss" is looking straight at the buttons that
			// are about to teleport under their pointer.
			.animateContentSize(animationSpec = motionTween(AgehaMotion.QUICK_MS)),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		horizontalAlignment = Alignment.End,
	) {
		for (notice in notices) {
			key(notice.id) {
				// Slides in from the right edge it is anchored to, so it reads as arriving from
				// off-screen rather than materialising over the library. Started false and flipped
				// on the first composition, which is what makes `AnimatedVisibility` animate an
				// item that is present from the moment it exists.
				val entrance = remember { MutableTransitionState(false) }
				entrance.targetState = true
				AnimatedVisibility(
					visibleState = entrance,
					enter = fadeIn(motionTween(AgehaMotion.QUICK_MS)) +
						slideInHorizontally(motionTween(AgehaMotion.QUICK_MS)) { it / 2 },
					exit = fadeOut(motionTween(AgehaMotion.INSTANT_MS, easing = AgehaMotion.exit)) +
						slideOutHorizontally(motionTween(AgehaMotion.INSTANT_MS)) { it / 2 },
				) {
					NoticeCard(notice, onDismiss = { onDismiss(notice.id) })
				}
			}
		}
	}
}

@Composable
private fun NoticeCard(notice: Notice, onDismiss: () -> Unit) {
	val container = if (notice.isError) {
		MaterialTheme.colorScheme.errorContainer
	} else {
		MaterialTheme.colorScheme.surfaceContainerHigh
	}
	val content = if (notice.isError) {
		MaterialTheme.colorScheme.onErrorContainer
	} else {
		MaterialTheme.colorScheme.onSurface
	}
	Column(
		Modifier
			.widthIn(max = 460.dp)
			.clip(MaterialTheme.shapes.medium)
			.background(container)
			.padding(AgehaSpacing.md),
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				notice.title,
				style = MaterialTheme.typography.titleSmall,
				color = content,
				modifier = Modifier.weight(1f),
			)
			TextButton(onClick = onDismiss) { Text("Dismiss", color = content) }
		}
		notice.detail?.takeIf { it.isNotBlank() }?.let { detail ->
			// Monospaced and scrollable: the backup importer's report is a tabulated list, and a
			// proportional font turns its alignment into noise. Capped in height so a long report
			// cannot push the dismiss button off screen.
			Text(
				text = detail.trimEnd(),
				style = AgehaTextStyles.metadata.copy(fontFamily = FontFamily.Monospace),
				color = content,
				modifier = Modifier
					.fillMaxWidth()
					// Enough room for a full import report, not so much that it fills the window
					// or pushes the dismiss button out of reach.
					.heightIn(max = 260.dp)
					.verticalScroll(rememberScrollState()),
			)
		}
	}
}
