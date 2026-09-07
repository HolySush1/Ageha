package app.ageha.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/*
 * The handoff's shared control vocabulary: the parts every screen is built out of.
 *
 * These live here rather than in the screens that use them because the handoff's whole premise is
 * one vocabulary across five surfaces -- a chip on Explore's tab row and a chip on the Library's
 * shelf filter are the *same* chip, and the moment they are two implementations they begin to
 * drift. Every one reads its colour, radius and material from `AgehaTheme.skin`, so they all become
 * Ember or Glass together and no screen has to know which it is in.
 */

/**
 * A section's eyebrow: accent bar, mono label, a rule filling the row, and a count on the right.
 *
 * `MY LIBRARY --------------------------------- 12 TITLES`
 *
 * The rule is the part that does the work. Without it this is a small label floating above a grid;
 * with it, the label and the count are visibly the two ends of one band, and the eye reads a
 * divider that happens to be labelled rather than a heading that happens to be small.
 *
 * @param label pass it cased. This uppercases for display only, so assistive technology is handed
 *   a word rather than an initialism spelled out letter by letter.
 */
@Composable
fun SectionHeader(
	label: String,
	modifier: Modifier = Modifier,
	count: String? = null,
) {
	val skin = AgehaTheme.skin
	Row(
		modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
	) {
		Box(Modifier.width(2.dp).height(11.dp).background(skin.accent))
		Text(label.uppercase(), style = AgehaTextStyles.monoEyebrow, color = skin.accent)
		Box(Modifier.weight(1f).height(1.dp).background(skin.line))
		if (count != null) {
			Text(count.uppercase(), style = AgehaTextStyles.monoEyebrow, color = skin.inkFaint)
		}
	}
}

/**
 * A chip: a tab, a filter, a language, a state.
 *
 * One component for all four, because the handoff gives them one appearance and one pair of
 * states. Selected is `--accent-soft` over `--accent-line` with full-strength ink; unselected is
 * transparent over `--line` with muted ink. The border is present in both, which is what keeps a
 * row of unselected chips reading as controls rather than as words.
 *
 * @param count drawn after the label at 65% opacity, as the handoff's tab counts are.
 */
@Composable
fun AgehaChip(
	label: String,
	isSelected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	count: String? = null,
	enabled: Boolean = true,
) {
	val skin = AgehaTheme.skin
	val shape = skin.chip
	// The handoff allows 120ms on chips, switches and nav items, and nothing else. Animating the
	// fill and the border but not the label keeps the transition off the glyph rasteriser, which
	// is where a colour crossfade on text actually costs something.
	val fill by animateColorAsState(
		if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
		tween(CHIP_FADE_MS),
		label = "chipFill",
	)
	val edge by animateColorAsState(
		if (isSelected) skin.accentLine else skin.line,
		tween(CHIP_FADE_MS),
		label = "chipEdge",
	)
	val ink = if (isSelected) {
		MaterialTheme.colorScheme.onSurface
	} else {
		MaterialTheme.colorScheme.onSurfaceVariant
	}
	Row(
		modifier
			.clip(shape)
			.background(fill)
			.border(1.dp, edge, shape)
			.clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
			.padding(horizontal = AgehaSpacing.lg, vertical = AgehaSpacing.sm),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
	) {
		Text(label, style = MaterialTheme.typography.labelMedium, color = ink)
		if (count != null) {
			Text(count, style = AgehaTextStyles.monoMeta, color = ink.copy(alpha = 0.65f))
		}
	}
}

/** The handoff's 120ms, expressed against the motion scale it sits between. */
private const val CHIP_FADE_MS = 120

/**
 * The 38x21 switch, to the handoff's measurements.
 *
 * Material's own `Switch` is not used, and the reason is size rather than taste: it is drawn for a
 * finger, at 52x32 with a 20dp thumb and a ripple that bleeds past its bounds. Dropped into a
 * settings row built on a 13.5px label, it is the largest thing on the screen. This is the same
 * control at desktop density -- and it is the one the source list needs, where forty of them down
 * a page multiply any difference in height by forty.
 */
@Composable
fun AgehaSwitch(
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
) {
	val skin = AgehaTheme.skin
	val track by animateColorAsState(
		if (checked) skin.accent else skin.inset,
		tween(AgehaMotion.QUICK_MS),
		label = "switchTrack",
	)
	val edge by animateColorAsState(
		if (checked) skin.accent else skin.lineStrong,
		tween(AgehaMotion.QUICK_MS),
		label = "switchEdge",
	)
	// The knob slides rather than jumping. This is the one control whose *motion* carries the
	// meaning -- off and on are otherwise the same shape in nearly the same place -- so it earns
	// its 140ms even under a motion budget this tight.
	val offset by animateFloatAsState(
		if (checked) 1f else 0f,
		tween(AgehaMotion.QUICK_MS),
		label = "switchKnob",
	)
	Box(
		modifier
			.size(width = 38.dp, height = 21.dp)
			.clip(CircleShape)
			.background(track)
			.border(1.dp, edge, CircleShape)
			.clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
			.padding(2.dp),
	) {
		Box(
			Modifier
				// 38 wide, 2dp of padding each side, a 15dp knob: 19dp of travel.
				.padding(start = (offset * KNOB_TRAVEL).dp)
				.size(15.dp)
				.clip(CircleShape)
				.background(if (checked) Color.White else skin.inkFaint),
		)
	}
}

private const val KNOB_TRAVEL = 19f

/**
 * A keyboard cap: `CTRL K`, `ESC`.
 *
 * Square-ish corners in both skins -- a literal 4dp rather than `--chip-r` -- because this is the
 * one element imitating a physical object. A fully round `ESC` in Glass stops reading as a key.
 */
@Composable
fun KeyCap(text: String, modifier: Modifier = Modifier) {
	val skin = AgehaTheme.skin
	val shape = RoundedCornerShape(4.dp)
	Box(
		modifier
			.clip(shape)
			.border(1.dp, skin.line, shape)
			.padding(horizontal = 6.dp, vertical = 3.dp),
	) {
		Text(text, style = AgehaTextStyles.monoEyebrow, color = skin.inkFaint)
	}
}

/**
 * A tinted note: `--accent-soft` over `--accent-line`, with mono text.
 *
 * For the one sentence a screen needs to say about its own state -- "8 sources hidden by the
 * current tab and filters" -- where a plain paragraph would be missed and a warning colour would
 * overstate it. Nothing has gone wrong here; something is merely not being shown.
 */
@Composable
fun NoteBox(text: String, modifier: Modifier = Modifier) {
	val shape = MaterialTheme.shapes.medium
	Box(
		modifier
			.fillMaxWidth()
			.clip(shape)
			.background(MaterialTheme.colorScheme.primaryContainer)
			.border(1.dp, AgehaTheme.skin.accentLine, shape)
			.padding(AgehaSpacing.md),
	) {
		Text(
			text,
			style = AgehaTextStyles.monoMeta,
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}

/** Source health, as a 7dp dot. Green, amber or accent; see `AgehaSkin.ok` and `warn`. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
	Box(modifier.size(7.dp).clip(CircleShape).background(color))
}

/**
 * A ghost button: the handoff's `--line2` outline, with no fill.
 *
 * The secondary action beside a filled one -- "All 260 chapters" next to "Continue reading",
 * "Pause all" next to "Free up space". Material's `OutlinedButton` would do this, at 40dp tall
 * with its own shape scale and a ripple; this is the same idea at the density the rest of the
 * interface is drawn to.
 */
@Composable
fun GhostButton(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	content: @Composable RowScope.() -> Unit,
) {
	val shape = MaterialTheme.shapes.medium
	Row(
		modifier
			.clip(shape)
			.border(1.dp, AgehaTheme.skin.lineStrong, shape)
			.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
			.padding(horizontal = AgehaSpacing.lg, vertical = AgehaSpacing.md),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		content = content,
	)
}
