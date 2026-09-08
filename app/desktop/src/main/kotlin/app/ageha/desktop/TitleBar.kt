package app.ageha.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ageha.core.designsystem.AgehaSkin
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.BrandAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How tall the window's own title bar is. The handoff's 38px.
 *
 * Public because the shell has to leave room for it, and because the resize handles in
 * `WindowResize.kt` must not claim the strip the drag area owns.
 */
val TITLE_BAR_HEIGHT = 38.dp

/** Test tags, so the journey driver can find controls that are shapes rather than words. */
const val SKIN_EMBER_TAG = "titlebar-skin-ember"
const val SKIN_GLASS_TAG = "titlebar-skin-glass"
const val WINDOW_CLOSE_TAG = "titlebar-close"
const val WINDOW_MINIMIZE_TAG = "titlebar-minimize"
const val WINDOW_MAXIMIZE_TAG = "titlebar-maximize"

/**
 * Ageha's own title bar, replacing the operating system's.
 *
 * ## Why the window is undecorated at all
 *
 * Not for looks alone. The bar carries three things a native caption cannot: a **context line**
 * that says what you are looking at and how much of it there is, the **skin switcher**, and the
 * app's own mark at a size the OS would have downscaled from a 256px icon. The handoff builds its
 * whole top edge around those, and half of it cannot exist inside a Windows caption.
 *
 * The cost is that everything a native caption did for free now has to be written, and *all* of it
 * has to be written -- a caption that is nine tenths of the way there is a window that feels
 * broken in one specific way its owner cannot name. What that means concretely: the buttons run to
 * the frame's own edge in Windows' order, they light under the pointer, the middle one turns into
 * a restore mark once the window is maximised, double-clicking the bar maximises, dragging a
 * maximised window restores it under the cursor, and eight edges resize. The drag and the resize
 * edges live in `WindowResize.kt`; this file is the strip itself.
 *
 * **One thing does not come back.** Dragging this window to a screen edge will not trigger
 * Windows' Aero Snap, because snap is driven by the non-client hit-testing an undecorated window
 * has opted out of, and restoring it needs `WM_NCHITTEST` over JNI. `Win`+arrow still snaps,
 * because that path is handled by the shell rather than by the window. Said out loud rather than
 * left for someone to discover.
 *
 * ## Why this takes callbacks rather than the window
 *
 * So it can be rendered headless. `ShellRender` draws every screen into an `ImageComposeScene`
 * with no `Window` anywhere, and a title bar that reached for `WindowState` directly could not
 * appear in a single screenshot -- which would leave the one piece of chrome that differs most
 * between the two skins out of every review image.
 */
@Composable
fun AgehaTitleBar(
	/** The mono line: "Library - 12 titles - 312 chapters cached". See [windowContextLine]. */
	context: String,
	theme: AgehaThemeMode,
	onTheme: (AgehaThemeMode) -> Unit,
	onMinimize: () -> Unit,
	onToggleMaximize: () -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
	/**
	 * Whether the window is maximised right now.
	 *
	 * Drives the middle button's glyph and its accessible name, because on Windows that button is
	 * two buttons wearing one slot: a square that maximises, and a pair of offset squares that
	 * restores. A button that keeps saying "maximise" while the window already fills the screen
	 * is the single most obvious tell that a caption was drawn rather than provided.
	 */
	isMaximized: Boolean = false,
) {
	val skin = AgehaTheme.skin
	Row(
		modifier
			.fillMaxWidth()
			.height(TITLE_BAR_HEIGHT)
			.background(MaterialTheme.colorScheme.surfaceContainerLow)
			// Double-click to maximise. The one caption behaviour people use without thinking
			// about it, and its absence reads as the window being broken rather than as a missing
			// feature. On the bar itself rather than on the drag area, because the drag detector
			// consumes the events it handles and would never see a second tap.
			.pointerInput(Unit) { detectTapGestures(onDoubleTap = { onToggleMaximize() }) }
			// No padding at the trailing end. The window buttons run to the frame's own edge,
			// the way every Windows caption does -- the corner is the largest target on the
			// screen precisely because there is nothing between it and the edge, and a few
			// millimetres of inset throws that away for symmetry nobody asked for.
			.padding(start = AgehaSpacing.md),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// The app tile. The handoff draws a monogram here because its prototype has no artwork;
		// Ageha has a real seal, and `:tools:brandkit` already renders a simplified silhouette for
		// exactly this size -- below 48px the wing veining turns to mud. Using it keeps the
		// handoff's 21dp tile and drops a letter that was never Ageha's.
		Image(
			painter = BrandAssets.painter("icon-64.png"),
			contentDescription = null,
			modifier = Modifier.size(21.dp).clip(MaterialTheme.shapes.medium),
		)
		Spacer(Modifier.width(AgehaSpacing.sm))
		Text(
			"Ageha",
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurface,
		)
		Spacer(Modifier.width(AgehaSpacing.md))
		// The 1x14 rule. Purely a separator, so it carries no accessible name.
		Box(Modifier.width(1.dp).height(14.dp).background(skin.lineStrong))
		Spacer(Modifier.width(AgehaSpacing.md))
		Text(
			context,
			style = AgehaTextStyles.monoMeta,
			color = skin.inkFaint,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			// This weight is what pins everything after it to the right edge, and it has to be a
			// *filling* one.
			//
			// It used to be `weight(1f, fill = false)` followed by a `Spacer(Modifier.weight(1f))`,
			// which looks like the same thing and is not: a Row splits its leftover space between
			// weighted children by weight, so the spacer only ever received half of it. The other
			// half was the share this text declined to fill, and with the default `Arrangement.Start`
			// that surplus collected *after* the last child -- leaving the skin switcher and the
			// window buttons floating in the middle of the bar with a dead gap between them and the
			// corner. Filling here gives the text every pixel that is going, so the controls sit
			// hard against the edge at any window width, and the line still truncates rather than
			// pushing them off it.
			modifier = Modifier.weight(1f),
		)
		SkinSwitcher(theme = theme, onTheme = onTheme)
		Spacer(Modifier.width(AgehaSpacing.sm))
		WindowButtons(onMinimize, onToggleMaximize, onClose, isMaximized)
	}
}

/**
 * The square-or-circle skin toggle.
 *
 * The shapes are the message. A 15dp square is Ember -- flat, small radii, rectangular pills -- and
 * a 13dp circle is Glass, whose every corner is a lozenge. Someone who has never read the handoff
 * can still see which one they are about to get, which is more than two labelled radio buttons
 * would manage in the same 60dp.
 *
 * Neither is lit when the theme is Light, AMOLED or System. That is honest rather than a gap: those
 * are not skins, the switcher does not claim they are, and clicking either still takes you to the
 * skin you clicked. Settings > Appearance is where all five live.
 */
@Composable
private fun SkinSwitcher(theme: AgehaThemeMode, onTheme: (AgehaThemeMode) -> Unit) {
	val skin = AgehaTheme.skin
	Row(
		Modifier
			.clip(skin.chip)
			.border(1.dp, skin.line, skin.chip)
			.padding(horizontal = AgehaSpacing.sm, vertical = 3.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
	) {
		Text("SKIN", style = AgehaTextStyles.monoEyebrow, color = skin.inkFaint)
		Swatch(
			isActive = theme == AgehaThemeMode.EMBER,
			activeColor = AgehaSkin.EmberSwatch,
			shape = RoundedCornerShape(3.dp),
			size = 15.dp,
			label = "Ember skin",
			tag = SKIN_EMBER_TAG,
			onClick = { onTheme(AgehaThemeMode.EMBER) },
		)
		Swatch(
			isActive = theme == AgehaThemeMode.GLASS,
			activeColor = AgehaSkin.GlassSwatch,
			shape = CircleShape,
			size = 13.dp,
			label = "Glass skin",
			tag = SKIN_GLASS_TAG,
			onClick = { onTheme(AgehaThemeMode.GLASS) },
		)
	}
}

@Composable
private fun Swatch(
	isActive: Boolean,
	activeColor: Color,
	shape: Shape,
	size: Dp,
	label: String,
	tag: String,
	onClick: () -> Unit,
) {
	Box(
		Modifier
			.size(size)
			.clip(shape)
			// Inactive is the skin's own faint ink rather than a dimmed copy of the accent. A
			// desaturated vermillion still reads as "red, but broken"; a neutral grey reads as
			// "the other one", which is what the off state actually means here.
			.background(if (isActive) activeColor else AgehaTheme.skin.inkFaint.copy(alpha = 0.35f))
			.clickable(onClick = onClick)
			.testTag(tag)
			.semantics { contentDescription = label },
	)
}

/**
 * Minimise, maximise/restore, close.
 *
 * Drawn rather than typed. The handoff writes them as `-`, `▢` and `✕`, and two of those are
 * characters Archivo does not carry -- a text implementation would fall through to whatever face
 * Skia found next and put three mismatched glyphs in the corner of every window. A 1.5dp stroke on
 * a `Canvas` is the same weight as every other icon in the interface and cannot go missing.
 *
 * The order is Windows' own -- minimise, maximise, close, left to right -- and it is not a
 * preference. It is muscle memory: a user's hand goes to the far corner for close without looking,
 * and any other arrangement means they occasionally close a window they meant to minimise.
 */
@Composable
private fun WindowButtons(
	onMinimize: () -> Unit,
	onToggleMaximize: () -> Unit,
	onClose: () -> Unit,
	isMaximized: Boolean,
) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		WindowButton("Minimise", onMinimize, tag = WINDOW_MINIMIZE_TAG) { color ->
			drawLine(
				color,
				Offset(0f, size.height / 2),
				Offset(size.width, size.height / 2),
				1.5.dp.toPx(),
				StrokeCap.Round,
			)
		}
		// One slot, two buttons. See `isMaximized` on [AgehaTitleBar].
		WindowButton(
			label = if (isMaximized) "Restore" else "Maximise",
			onClick = onToggleMaximize,
			tag = WINDOW_MAXIMIZE_TAG,
		) { color ->
			val stroke = 1.5.dp.toPx()
			if (isMaximized) {
				// Windows' restore mark: a square with a second one peeking out behind its top
				// right corner. Both are drawn at three quarters of the glyph box so the pair
				// occupies the same optical area as the single square it replaces -- otherwise
				// the button visibly grows when you maximise the window.
				val side = size.width * 0.75f
				val offset = size.width - side
				translate(left = offset) {
					drawRect(color, size = Size(side, side), style = Stroke(stroke))
				}
				translate(top = offset) {
					drawRect(color, size = Size(side, side), style = Stroke(stroke))
				}
			} else {
				drawRect(color, style = Stroke(stroke))
			}
		}
		WindowButton("Close", onClose, tag = WINDOW_CLOSE_TAG, isDestructive = true) { color ->
			drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), 1.5.dp.toPx(), StrokeCap.Round)
			drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), 1.5.dp.toPx(), StrokeCap.Round)
		}
	}
}

@Composable
private fun WindowButton(
	label: String,
	onClick: () -> Unit,
	tag: String? = null,
	isDestructive: Boolean = false,
	draw: DrawScope.(Color) -> Unit,
) {
	// Hover feedback, which on Windows is not decoration -- it is the only thing that tells you
	// which of three unlabelled 46dp glyphs your pointer is actually on. Without it these read as
	// three marks painted on the bar rather than as buttons, and people click the wrong one.
	//
	// Close gets the loud treatment for the same reason Windows gives it one: it fills, and the
	// glyph inverts onto the fill. The other two get a quiet raised step. `error`/`onError` rather
	// than the skin accent, because that is the one pair in the palette the contrast test already
	// guarantees is legible together in every theme.
	val interaction = remember { MutableInteractionSource() }
	val isHovered by interaction.collectIsHoveredAsState()
	val fill = when {
		!isHovered -> Color.Transparent
		isDestructive -> MaterialTheme.colorScheme.error
		else -> MaterialTheme.colorScheme.surfaceContainerHighest
	}
	val tint = when {
		isHovered && isDestructive -> MaterialTheme.colorScheme.onError
		// Close is the one button whose mistake cannot be undone, so it is the one that is
		// coloured at rest. Everything else in this row is `--ink2` and stays there.
		isDestructive -> AgehaTheme.skin.accent
		isHovered -> MaterialTheme.colorScheme.onSurface
		else -> MaterialTheme.colorScheme.onSurfaceVariant
	}
	Box(
		Modifier
			.size(width = 46.dp, height = TITLE_BAR_HEIGHT)
			.background(fill)
			.hoverable(interaction)
			// An arrow, not the text caret the title bar's context line would otherwise leak onto
			// its neighbours, and not a hand either -- native caption buttons do not use one.
			.pointerHoverIcon(PointerIcon.Default)
			.clickable(
				interactionSource = interaction,
				indication = null,
				onClick = onClick,
			)
			.then(if (tag != null) Modifier.testTag(tag) else Modifier)
			.semantics { contentDescription = label },
		contentAlignment = Alignment.Center,
	) {
		Canvas(Modifier.size(9.dp)) { draw(tint) }
	}
}

/**
 * The mono line in the middle of the title bar: where you are, and how much of it there is.
 *
 * The handoff specifies this per screen -- "Library - 12 titles - 312 chapters cached", "Explore -
 * 9 sources - 2 broken", "Settings - v0.9.4" -- and the counts are the point. A title bar that only
 * repeats the app's name is a title bar that says nothing the window does not already say; these
 * are the numbers a user would otherwise have to open a screen to find out.
 *
 * ## Why the storage scan is keyed on the section rather than polled
 *
 * Counting cached chapters means walking the download tree, which is cheap but not free. Recounting
 * it on every recomposition would put a filesystem walk behind every keystroke in the search field.
 * Keyed on the destination instead: it runs when you arrive somewhere, which is exactly when the
 * number can have changed and be worth reading.
 */
@Composable
fun windowContextLine(application: AgehaApplication, navigator: Navigator): String {
	val version = app.ageha.core.model.AgehaVersion.NAME

	// Both counts are wanted only by the two sections that show them, and each is behind IO. They
	// are still collected unconditionally, because a `remember` that appears and disappears with
	// the current screen would re-run its query on every navigation -- which is the cost this is
	// trying to avoid, paid more often.
	val librarySizes by application.library.observeCategorySizes()
		.collectAsState(initial = emptyMap())
	val libraryCount = librarySizes[application.library.allCategoryId] ?: 0

	val cachedChapters by produceState(0, navigator.section) {
		value = withContext(Dispatchers.IO) {
			runCatching { application.downloadInventory.scan().chapterCount }.getOrDefault(0)
		}
	}

	return when (val destination = navigator.current) {
		Destination.Library, Destination.Continue ->
			"Library · ${plural(libraryCount, "title")} · $cachedChapters chapters cached"

		Destination.Sources, is Destination.Browse, is Destination.SearchAll, is Destination.Details -> {
			val total = remember { runCatching { application.sources.allDescriptors().size }.getOrDefault(0) }
			"Explore · ${plural(total, "source")}"
		}

		Destination.Downloads -> "Downloads · ${plural(cachedChapters, "chapter")} on this device"
		Destination.Settings -> "Settings · v$version"
		// The one line that is not a count. While someone is reading, the useful thing the bar can
		// say is what they are reading -- and it is the only place the title appears at all once
		// the reader has hidden its own chrome.
		is Destination.Read -> "Reading · ${destination.manga.title}"
	}
}

/** "1 title", "12 titles". Small, and worth it: "1 titles" in the chrome looks like a bug. */
private fun plural(count: Int, noun: String): String =
	if (count == 1) "1 $noun" else "$count ${noun}s"
