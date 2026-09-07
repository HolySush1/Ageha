package app.ageha.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ageha.core.data.LibraryEntry
import app.ageha.core.designsystem.AgehaGlass
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.GlassTone
import app.ageha.core.designsystem.KeyCap
import app.ageha.core.designsystem.MangaThumbnail
import app.ageha.core.model.AgehaManga
import kotlin.math.roundToInt

/**
 * The handoff's command panel: one field, 74dp from the top, over whatever screen is current.
 *
 * ## Why this replaced both the library's search field and a click through to a search screen
 *
 * They were two controls for one question. The library's field filtered a list already in memory
 * and could not reach a source; the search screen reached every enabled source and could not see
 * the library. WIRING.md describes the panel doing both, in that order -- *"search local library
 * by title first (instant, in-memory), then fan out to enabled sources ... append remote results
 * under local ones as they arrive"* -- and that ordering is the whole design: the thing you are
 * most likely to want is the thing you already own, and it arrives with no latency at all.
 *
 * ## What the two halves cost
 *
 * The local half is a filter over a list already in memory and runs on every keystroke. The remote
 * half is one request per enabled source, so the caller debounces it and it only fires on a query
 * worth spending someone's bandwidth on. A panel that fanned out on every keystroke would send
 * twenty requests per letter typed, from the user's own address.
 */
@Composable
fun CommandPanel(
	query: String,
	onQuery: (String) -> Unit,
	/** Library titles matching the query. Already filtered; this composable does not search. */
	local: List<LibraryEntry>,
	/** Results from the enabled sources, flattened. */
	remote: List<AgehaManga>,
	/** Whether any source is still answering. Drives the one line of status under the rows. */
	isSearchingRemote: Boolean,
	imageHeaders: Map<String, Map<String, String>>,
	onOpen: (AgehaManga) -> Unit,
	onSeeAllResults: () -> Unit,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val focus = remember { FocusRequester() }
	// Straight into the field. A panel that opens and then asks for a second click before it will
	// take a query is a panel people stop opening.
	LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
	Box(
		modifier
			.fillMaxSize()
			// The handoff's full-bleed invisible backdrop. It is what makes "click anywhere else"
			// close the panel, and it has to *swallow* the click rather than let it through --
			// otherwise dismissing the panel also opens whatever was under the pointer.
			.clickable(
				indication = null,
				interactionSource = remember { MutableInteractionSource() },
				onClick = onDismiss,
			),
		contentAlignment = Alignment.TopCenter,
	) {
		val shape = MaterialTheme.shapes.large
		Column(
			Modifier
				.padding(top = PANEL_TOP)
				.width(PANEL_WIDTH)
				.shadow(PANEL_ELEVATION, shape, clip = false)
				.clip(shape)
				.background(AgehaGlass.fill(GlassTone.RAISED))
				.border(1.dp, AgehaTheme.skin.lineStrong, shape)
				// Clicks inside the panel must not reach the backdrop behind it.
				.clickable(
					indication = null,
					interactionSource = remember { MutableInteractionSource() },
					onClick = {},
				)
				.onPreviewKeyEvent { event ->
					if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
						onDismiss()
						true
					} else {
						false
					}
				},
		) {
			QueryField(query, onQuery, focus)
			Box(Modifier.fillMaxWidth().height(1.dp).background(AgehaTheme.skin.line))
			Results(
				query = query,
				local = local,
				remote = remote,
				isSearchingRemote = isSearchingRemote,
				imageHeaders = imageHeaders,
				onOpen = onOpen,
				onSeeAllResults = onSeeAllResults,
			)
		}
	}
}

@Composable
private fun QueryField(query: String, onQuery: (String) -> Unit, focus: FocusRequester) {
	val skin = AgehaTheme.skin
	Row(
		Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
	) {
		Icon(
			Icons.Default.Search,
			contentDescription = null,
			tint = skin.inkFaint,
			modifier = Modifier.size(17.dp),
		)
		Box(Modifier.weight(1f)) {
			if (query.isEmpty()) {
				Text(
					"Search your library, then every enabled source",
					style = AgehaTextStyles.monoControl,
					color = skin.inkFaint,
				)
			}
			BasicTextField(
				value = query,
				onValueChange = onQuery,
				singleLine = true,
				textStyle = AgehaTextStyles.monoControl.copy(
					color = MaterialTheme.colorScheme.onSurface,
				),
				cursorBrush = SolidColor(skin.accent),
				modifier = Modifier.fillMaxWidth().focusRequester(focus),
			)
		}
		KeyCap("ESC")
	}
}

@Composable
private fun Results(
	query: String,
	local: List<LibraryEntry>,
	remote: List<AgehaManga>,
	isSearchingRemote: Boolean,
	imageHeaders: Map<String, Map<String, String>>,
	onOpen: (AgehaManga) -> Unit,
	onSeeAllResults: () -> Unit,
) {
	Column(Modifier.padding(vertical = AgehaSpacing.sm)) {
		if (query.isBlank()) {
			Hint("Type to filter your library. Results from your sources follow.")
			return@Column
		}
		val shown = local.take(LOCAL_ROWS)
		for (entry in shown) {
			ResultRow(
				manga = entry.manga,
				meta = entry.commandPanelMeta(),
				imageHeaders = imageHeaders[entry.manga.sourceName].orEmpty(),
				onOpen = { onOpen(entry.manga) },
			)
		}
		val remoteShown = remote.take((TOTAL_ROWS - shown.size).coerceAtLeast(0))
		if (remoteShown.isNotEmpty()) {
			// Named, because a row from a source and a row from the library look identical and do
			// very different things -- one resumes, the other opens a title you do not have.
			Hint("From your sources")
			for (manga in remoteShown) {
				ResultRow(
					manga = manga,
					meta = manga.sourceName,
					imageHeaders = imageHeaders[manga.sourceName].orEmpty(),
					onOpen = { onOpen(manga) },
				)
			}
		}
		when {
			isSearchingRemote -> Hint("Searching your sources...")

			shown.isEmpty() && remoteShown.isEmpty() -> Hint("No titles match “$query”.")

			local.size + remote.size > shown.size + remoteShown.size -> Row(
				Modifier
					.fillMaxWidth()
					.clickable(role = Role.Button, onClick = onSeeAllResults)
					.padding(horizontal = 18.dp, vertical = AgehaSpacing.sm),
			) {
				Text(
					"${local.size + remote.size} matches · see them all",
					style = AgehaTextStyles.monoMeta,
					color = AgehaTheme.skin.accent,
				)
			}
		}
	}
}

@Composable
private fun Hint(text: String) {
	Text(
		text,
		style = AgehaTextStyles.monoMeta,
		color = AgehaTheme.skin.inkFaint,
		modifier = Modifier.padding(horizontal = 18.dp, vertical = AgehaSpacing.sm),
	)
}

/** The handoff's row: a 30x42 thumb, the title, its mono line, and a trailing `open`. */
@Composable
private fun ResultRow(
	manga: AgehaManga,
	meta: String,
	imageHeaders: Map<String, String>,
	onOpen: () -> Unit,
) {
	Row(
		Modifier
			.fillMaxWidth()
			.clickable(role = Role.Button, onClick = onOpen)
			.padding(horizontal = 18.dp, vertical = AgehaSpacing.sm),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md),
	) {
		MangaThumbnail(manga, imageHeaders, modifier = Modifier.width(30.dp))
		Column(Modifier.weight(1f)) {
			Text(
				manga.title,
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				meta,
				style = AgehaTextStyles.monoMeta,
				color = AgehaTheme.skin.inkFaint,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		Text("open →", style = AgehaTextStyles.monoMeta, color = AgehaTheme.skin.inkFaint)
	}
}

/** `64% · reading`, or the source name when the title has never been opened. */
private fun LibraryEntry.commandPanelMeta(): String {
	val percent = progressPercent ?: return manga.sourceName
	val state = when {
		percent >= 1f -> "read"
		percent > 0f -> "reading"
		else -> "started"
	}
	return "${(percent * 100).roundToInt()}% · $state"
}

/** The handoff's 620px panel, 74px from the top. */
private val PANEL_WIDTH = 620.dp
private val PANEL_TOP = 74.dp

/** `--shadow`, on Compose's elevation scale. This floats over content, not over the backdrop. */
private val PANEL_ELEVATION = 16.dp

/**
 * How many rows the panel shows.
 *
 * The handoff says five, and the split between them is deliberate: local results are capped lower
 * than the total, so a library match can never crowd the source results off the panel entirely. A
 * query matching eight things you own still leaves room to see that the sources found something.
 */
private const val LOCAL_ROWS = 3
private const val TOTAL_ROWS = 5
