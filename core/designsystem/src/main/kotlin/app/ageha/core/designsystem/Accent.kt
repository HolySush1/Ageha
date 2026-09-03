package app.ageha.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The vermillion accent, and the *only* three places it is allowed to appear.
 *
 * Indigo and vermillion is a deliberate traditional pairing, and it stops working the instant
 * vermillion becomes a general-purpose highlight -- at which point it is just a second brand
 * colour and the seal stops meaning anything. So the accent is not exposed as a colour token that
 * any screen can reach for. It is exposed as these three components, and adding a fourth is a
 * design decision, not an implementation detail.
 *
 * Ageha also uses one red rather than two: the same vermillion serves as the error colour. Two
 * near-identical reds -- a brand accent and Material's generic error red, a few degrees of hue
 * apart -- look like a mistake rather than a distinction. The meanings are separated by **form**
 * instead, and the separation is easy to state:
 *
 *  - **A small mark** -- a dot, a short bar, a thin underline -- means *unread, new, or currently
 *    reading*. Something to notice.
 *  - **A filled surface** -- a solid button, a filled banner -- means *destructive or failed*.
 *    Something to be careful about.
 *
 * Nobody mistakes a 6dp dot for a delete button, and neither is ever the only signal: both carry
 * a label or a count, because colour alone is not an accessible signal in the first place.
 */
object AgehaAccent {

	/** The accent, resolved for the current theme. Internal so screens go through the components. */
	internal val color: Color
		@Composable get() = MaterialTheme.colorScheme.tertiary

	/**
	 * Unread-chapter count on a library cover.
	 *
	 * Always shows the number. The colour draws the eye; the number is the information.
	 */
	@Composable
	fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
		if (count <= 0) return
		Box(
			modifier = modifier
				.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
				.clip(RoundedCornerShape(9.dp))
				.background(MaterialTheme.colorScheme.tertiary)
				.padding(horizontal = 5.dp),
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = if (count > 99) "99+" else count.toString(),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onTertiary,
			)
		}
	}

	/**
	 * The "you are here" mark on the chapter currently being read.
	 *
	 * A bar rather than a highlighted row: a filled row would compete with selection, and in a
	 * long chapter list the reader is scanning for one item, not admiring the list.
	 */
	@Composable
	fun ActiveReadingIndicator(modifier: Modifier = Modifier) {
		Box(
			modifier = modifier
				.size(width = 3.dp, height = 20.dp)
				.clip(RoundedCornerShape(2.dp))
				.background(MaterialTheme.colorScheme.tertiary),
		)
	}

	/** A new-chapter dot. Same meaning as the badge, where there is no room for a number. */
	@Composable
	fun NewChapterDot(modifier: Modifier = Modifier) {
		Box(
			modifier = modifier
				.size(6.dp)
				.clip(CircleShape)
				.background(MaterialTheme.colorScheme.tertiary),
		)
	}

	/**
	 * Colours for a confirm button that deletes something.
	 *
	 * Filled, because this is the "be careful" form. Use it only where the action is genuinely
	 * irreversible -- removing a manga from the library with its downloads, clearing history,
	 * deleting a category. A destructive-looking button on a reversible action teaches people to
	 * ignore the colour, which costs exactly when it matters.
	 */
	@Composable
	fun destructiveButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
		containerColor = MaterialTheme.colorScheme.error,
		contentColor = MaterialTheme.colorScheme.onError,
	)
}
