package app.ageha.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * The handoff's select: a ghost button that opens a real popover.
 *
 * ## Why this exists rather than a radio group or a cycler
 *
 * Ageha's settings used radio groups, and for a screen with room they are the better control --
 * every option is visible without a click. The handoff's settings screen does not have that room:
 * its rows are 16dp of padding around a 13.5px label with the control on the *right*, which is a
 * layout a vertical stack of radios cannot enter. Matching the layout means matching the control.
 *
 * A cycler was the other option the mockup offers, and it is the wrong one in both places it
 * appears. Three options means up to two unwanted states rendered on the way to the one wanted,
 * and a cycler never shows what the alternatives are -- so the reader's chips open this too. That
 * is what WIRING.md asks for (`openSelect` = `reader.<key>`), against README.md's "click cycles".
 *
 * ## What the popover has to get right
 *
 * - **Below the anchor and aligned to its right edge, 6dp down.** The button sits at the right end
 *   of a settings row; a popover anchored left would open across the label it belongs to.
 * - **Escape and an outside click both close it.** `PopupProperties(focusable = true)` is what
 *   buys both -- the popup takes focus, so the key reaches it and a click elsewhere dismisses it.
 *   The handoff draws a full-bleed invisible backdrop for the same job; Compose has the primitive.
 * - **The caret rotates.** 180 degrees, animated, because it is the only part of the closed button
 *   that says the panel below belongs to it.
 */
@Composable
fun <T> AgehaSelect(
	value: T,
	options: List<T>,
	onSelect: (T) -> Unit,
	label: (T) -> String,
	modifier: Modifier = Modifier,
	minWidth: Dp = SELECT_MIN_WIDTH,
	enabled: Boolean = true,
) {
	var open by remember { mutableStateOf(false) }
	val skin = AgehaTheme.skin
	val shape = MaterialTheme.shapes.medium
	val fill by animateColorAsState(
		if (open) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
		tween(AgehaMotion.INSTANT_MS),
		label = "selectFill",
	)
	val edge by animateColorAsState(
		if (open) skin.accentLine else skin.lineStrong,
		tween(AgehaMotion.INSTANT_MS),
		label = "selectEdge",
	)
	val caret by animateFloatAsState(
		if (open) CARET_OPEN_DEGREES else 0f,
		tween(AgehaMotion.QUICK_MS),
		label = "selectCaret",
	)
	Box(modifier) {
		Row(
			Modifier
				.widthIn(min = minWidth)
				.clip(shape)
				.background(fill)
				.border(1.dp, edge, shape)
				.clickable(enabled = enabled, role = Role.DropdownList) { open = !open }
				.padding(horizontal = 13.dp, vertical = 9.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			Text(
				label(value),
				style = AgehaTextStyles.monoControl,
				color = if (enabled) MaterialTheme.colorScheme.onSurface else skin.inkFaint,
			)
			Spacer(Modifier.weight(1f))
			Canvas(Modifier.size(9.dp).rotate(caret)) { drawCaret(skin.inkFaint) }
		}
		if (open) {
			Popup(
				popupPositionProvider = BelowRightAligned,
				onDismissRequest = { open = false },
				properties = PopupProperties(focusable = true),
			) {
				SelectPanel(value, options, label) {
					onSelect(it)
					open = false
				}
			}
		}
	}
}

/**
 * The panel itself.
 *
 * [GlassTone.RAISED] rather than the tone the chrome uses, and that is the handoff being right
 * about a detail: this floats over *content*, so it is drawn nearly opaque with a `--line2` edge
 * and a real shadow, not at a translucency tuned for sitting over the backdrop.
 */
@Composable
private fun <T> SelectPanel(
	value: T,
	options: List<T>,
	label: (T) -> String,
	onPick: (T) -> Unit,
) {
	val skin = AgehaTheme.skin
	val shape = MaterialTheme.shapes.large
	Column(
		Modifier
			.shadow(SELECT_ELEVATION, shape, clip = false)
			.clip(shape)
			.background(AgehaGlass.fill(GlassTone.RAISED))
			.border(1.dp, skin.lineStrong, shape)
			.padding(5.dp),
		verticalArrangement = Arrangement.spacedBy(1.dp),
	) {
		for (option in options) {
			val isSelected = option == value
			Row(
				Modifier
					.clip(MaterialTheme.shapes.medium)
					.background(
						if (isSelected) {
							MaterialTheme.colorScheme.primaryContainer
						} else {
							Color.Transparent
						},
					)
					.clickable(role = Role.RadioButton) { onPick(option) }
					.padding(horizontal = 10.dp, vertical = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
			) {
				Text(
					label(option),
					style = AgehaTextStyles.monoControl,
					color = if (isSelected) {
						MaterialTheme.colorScheme.onSurface
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
				)
				Spacer(Modifier.weight(1f))
				// Reserved whether or not it is drawn, so the rows do not change width as the
				// selection moves down the panel.
				Box(Modifier.size(CHECK_SIZE)) {
					if (isSelected) Canvas(Modifier.size(CHECK_SIZE)) { drawCheck(skin.accent) }
				}
			}
		}
	}
}

/**
 * Below the anchor and aligned to its right edge.
 *
 * Written out rather than using a `DropdownMenu`, whose position provider anchors to the *left*
 * and whose panel carries Material's own surface, elevation and shape -- three things this design
 * specifies differently. Flipping above the anchor when there is no room below is the one piece of
 * `DropdownMenu` behaviour worth keeping, and it is four lines.
 */
private object BelowRightAligned : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val x = (anchorBounds.right - popupContentSize.width)
			.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
		val below = anchorBounds.bottom + SELECT_GAP_PX
		val y = if (below + popupContentSize.height <= windowSize.height) {
			below
		} else {
			(anchorBounds.top - SELECT_GAP_PX - popupContentSize.height).coerceAtLeast(0)
		}
		return IntOffset(x, y)
	}
}

/** The handoff's 6px gap between the button and the panel it opens. */
private const val SELECT_GAP_PX = 6

/** `--shadow`, on Compose's elevation scale. The panel has to read as a separate layer. */
private val SELECT_ELEVATION = 12.dp

/** The handoff's `min-width: 132px`, so a column of selects lines its carets up. */
val SELECT_MIN_WIDTH = 132.dp

/** The trailing tick, at the handoff's 11px. */
private val CHECK_SIZE = 11.dp

private const val CARET_OPEN_DEGREES = 180f

/**
 * A downward chevron, drawn rather than typed.
 *
 * `▾` is not in Archivo or JetBrains Mono, so a text implementation falls through to whatever face
 * Skia finds next -- which is how a caret ends up a different weight and a different size from the
 * value beside it, on some machines only.
 */
private fun DrawScope.drawCaret(colour: Color) {
	val stroke = 1.5.dp.toPx()
	drawLine(
		colour,
		Offset(0f, size.height * 0.32f),
		Offset(size.width / 2f, size.height * 0.72f),
		stroke,
		StrokeCap.Round,
	)
	drawLine(
		colour,
		Offset(size.width / 2f, size.height * 0.72f),
		Offset(size.width, size.height * 0.32f),
		stroke,
		StrokeCap.Round,
	)
}

/** The tick on the chosen row. Same construction as the grid's completion mark. */
private fun DrawScope.drawCheck(colour: Color) {
	val stroke = 1.6.dp.toPx()
	drawLine(
		colour,
		Offset(0f, size.height * 0.55f),
		Offset(size.width * 0.36f, size.height * 0.92f),
		stroke,
		StrokeCap.Round,
	)
	drawLine(
		colour,
		Offset(size.width * 0.36f, size.height * 0.92f),
		Offset(size.width, size.height * 0.12f),
		stroke,
		StrokeCap.Round,
	)
}

/**
 * The handoff's segmented control: a `--glass` group with 4dp of padding and one option per slot.
 *
 * The other half of the settings vocabulary, and the division between the two is about *arity*
 * rather than taste. Two or three short options are a segment -- every choice visible, one click
 * to change, and the group reads as one control. Four or more, or options long enough to wrap,
 * are an [AgehaSelect]: a segmented control that spans half the row stops looking like a control.
 */
@Composable
fun <T> AgehaSegmented(
	value: T,
	options: List<T>,
	onSelect: (T) -> Unit,
	label: (T) -> String,
	modifier: Modifier = Modifier,
) {
	val skin = AgehaTheme.skin
	val shape = MaterialTheme.shapes.medium
	Row(
		modifier
			.clip(shape)
			.background(skin.inset)
			.border(1.dp, skin.line, shape)
			.padding(4.dp),
		horizontalArrangement = Arrangement.spacedBy(2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		for (option in options) {
			val isSelected = option == value
			val fill by animateColorAsState(
				if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
				tween(AgehaMotion.INSTANT_MS),
				label = "segmentFill",
			)
			Box(
				Modifier
					.clip(RoundedCornerShape(SEGMENT_RADIUS))
					.background(fill)
					.clickable(role = Role.RadioButton) { onSelect(option) }
					.padding(horizontal = 12.dp, vertical = 6.dp),
			) {
				Text(
					label(option),
					style = MaterialTheme.typography.labelMedium,
					color = if (isSelected) {
						MaterialTheme.colorScheme.onSurface
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
				)
			}
		}
	}
}

/**
 * The inner radius of a segment.
 *
 * One step inside the group's own, rather than the group's radius repeated. Nesting a shape at its
 * parent's radius inside 4dp of padding leaves a visible crescent of the parent showing at each
 * corner, which reads as a rendering fault rather than as a selected segment.
 */
private val SEGMENT_RADIUS = 4.dp
