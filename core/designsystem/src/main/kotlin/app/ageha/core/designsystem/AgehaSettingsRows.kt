package app.ageha.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The handoff's settings container: a `--panel2` card of rows divided by `--line`.
 *
 * ## Why the dividers are drawn by the container rather than by each row
 *
 * A row that draws its own bottom hairline leaves one hanging under the last row, against the
 * container's own border -- two lines 1dp apart, which is the single most visible way to get this
 * construction wrong. Interleaving here means the container knows which row is last and simply
 * does not draw one after it.
 *
 * The rows arrive as a list rather than as a `content` block for the same reason: a block would
 * have to be walked to find its children, which Compose does not let a caller do. A list is the
 * honest shape of "n things with dividers between them".
 */
@Composable
fun SettingsRows(
	rows: List<@Composable () -> Unit>,
	modifier: Modifier = Modifier,
) {
	val shape = MaterialTheme.shapes.large
	Column(
		modifier
			.fillMaxWidth()
			.clip(shape)
			// `--panel2`: one step *down* from `--panel`, not up. The handoff puts list containers
			// below cards, so a settings block reads as a recessed well the rows sit in rather
			// than as another raised card floating on the plane of the banner above it.
			.background(MaterialTheme.colorScheme.surfaceContainerLow)
			.border(1.dp, AgehaTheme.skin.line, shape),
	) {
		rows.forEachIndexed { index, row ->
			row()
			if (index != rows.lastIndex) {
				Box(Modifier.fillMaxWidth().height(1.dp).background(AgehaTheme.skin.line))
			}
		}
	}
}

/**
 * One row: a label and a mono hint on the left, one control on the right.
 *
 * The hint is not optional decoration. Every row on this screen changes something the user cannot
 * see from the screen itself -- what "Preload next pages" costs, what "Mark as read at" actually
 * completes -- and a settings screen whose labels are its only explanation is a screen people
 * change one setting on and then leave alone.
 *
 * @param hint set in mono, because it is *data about the setting* rather than prose: a threshold,
 *   a count, a path, a consequence stated in the fewest words that carry it.
 */
@Composable
fun SettingRow(
	label: String,
	hint: String,
	modifier: Modifier = Modifier,
	control: @Composable RowScope.() -> Unit,
) {
	Row(
		modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(22.dp),
	) {
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
			Text(
				label,
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(hint, style = AgehaTextStyles.monoMeta, color = AgehaTheme.skin.inkFaint)
		}
		control()
	}
}

/**
 * A panel's heading: the title, and a blurb narrow enough to read.
 *
 * The 560dp cap is the handoff's, and it is a measure rather than a preference -- a line of body
 * text stops being scannable somewhere past 75 characters, and this screen's right column is as
 * wide as the window. Without the cap the blurb runs the full width of an ultrawide monitor and
 * the eye loses the line return.
 */
@Composable
fun PanelHeading(title: String, blurb: String, modifier: Modifier = Modifier) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(AgehaSpacing.sm)) {
		Text(title, style = MaterialTheme.typography.headlineSmall)
		Text(
			blurb,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.widthIn(max = BLURB_MEASURE),
		)
	}
}

/** The handoff's 560px measure. */
private val BLURB_MEASURE = 560.dp
