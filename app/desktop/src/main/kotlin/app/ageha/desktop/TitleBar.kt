package app.ageha.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * The cost is that everything a native caption did for free now has to be written: dragging,
 * double-click to maximise, the three window buttons, and eight resize edges. Those live in
 * `WindowResize.kt`; this file is the strip itself.
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
			.padding(start = AgehaSpacing.md, end = AgehaSpacing.xs),
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
			// Allowed to shrink, not to grow. A long context line truncates instead of pushing
			// the skin switcher and the window buttons off the edge of a narrow window.
			modifier = Modifier.weight(1f, fill = false),
		)
		Spacer(Modifier.weight(1f))
		SkinSwitcher(theme = theme, onTheme = onTheme)
		Spacer(Modifier.width(AgehaSpacing.sm))
		WindowButtons(onMinimize, onToggleMaximize, onClose)
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
 * Minimise, maximise, close.
 *
 * Drawn rather than typed. The handoff writes them as `-`, `▢` and `✕`, and two of those are
 * characters Archivo does not carry -- a text implementation would fall through to whatever face
 * Skia found next and put three mismatched glyphs in the corner of every window. A 1.5dp stroke on
 * a `Canvas` is the same weight as every other icon in the interface and cannot go missing.
 */
@Composable
private fun WindowButtons(onMinimize: () -> Unit, onToggleMaximize: () -> Unit, onClose: () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		WindowButton("Minimise", onMinimize) { color ->
			drawLine(
				color,
				Offset(0f, size.height / 2),
				Offset(size.width, size.height / 2),
				1.5.dp.toPx(),
				StrokeCap.Round,
			)
		}
		WindowButton("Maximise", onToggleMaximize) { color ->
			drawRect(color, style = Stroke(1.5.dp.toPx()))
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
	// Close is the one button whose mistake cannot be undone, so it is the one that is coloured.
	// Everything else in this row is `--ink2` and stays there.
	val tint = if (isDestructive) AgehaTheme.skin.accent else MaterialTheme.colorScheme.onSurfaceVariant
	Box(
		Modifier
			.size(width = 34.dp, height = TITLE_BAR_HEIGHT)
			.clickable(onClick = onClick)
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
