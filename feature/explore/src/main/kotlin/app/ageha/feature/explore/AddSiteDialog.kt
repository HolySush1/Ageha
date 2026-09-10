package app.ageha.feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.SourceDescriptor

/** The Add site dialog, as far as the rest of the screen is concerned. */
sealed interface AddSiteState {

	data object Closed : AddSiteState

	data class Open(
		/** Exactly what was typed or pasted, untouched -- normalising it is the resolver's job. */
		val input: String = "",
		val status: AddSiteStatus = AddSiteStatus.Idle,
	) : AddSiteState
}

/**
 * Where a lookup stands.
 *
 * Six states rather than a loading flag and a nullable result, because the dialog says something
 * different for each, and two of them are easy to conflate and must not be: [NotALink] is a typo
 * the person can fix in the field, while [NotFound] is a fact about Ageha's library that no amount
 * of retyping changes. Collapsing them into "nothing found" would send someone to re-paste a link
 * that was fine.
 */
sealed interface AddSiteStatus {

	data object Idle : AddSiteStatus

	data object NotALink : AddSiteStatus

	data class Resolving(val host: String) : AddSiteStatus

	data class Found(
		val host: String,
		val source: SourceDescriptor,
		/** Set when the link named a particular manga rather than the site as a whole. */
		val manga: AgehaManga?,
	) : AddSiteStatus

	data class NotFound(val host: String, val parsersVersion: String) : AddSiteStatus

	/** The lookup itself went wrong -- a timeout, a site that would not answer. Not "unsupported". */
	data class Failed(val host: String, val reason: String) : AddSiteStatus
}

/** The link field, for tests that need to type into it. */
const val ADD_SITE_FIELD_TAG = "add-site-field"

/**
 * Where a request for a new site goes.
 *
 * The parsers project's own tracker, because that is where the parser would be written: a site
 * added there reaches every Ageha installation with the next parser update, while a request filed
 * against Ageha could only ever be forwarded.
 */
const val PARSERS_UPSTREAM_ISSUES = "https://github.com/Kotatsu-Redo/kotatsu-parsers-redo/issues"

/**
 * Paste a link, find the source that reads it.
 *
 * ## What this does not do, said where someone will read it
 *
 * It does not teach Ageha a new site. Every source is a parser from the kotatsu-parsers-redo
 * library (CLAUDE.md rule 2), and a parser that could read an arbitrary site does not exist:
 * every site lays out its pages, hides its image addresses and guards itself against bots
 * differently. What a link *can* do is name the site, and with around 1,360 sources, several
 * mirror domains each, finding the right one by scrolling a list of names is mostly guesswork.
 * So the dialog answers the question people actually have -- "can I read this site in Ageha,
 * and where is it" -- and is plain about the case where the answer is no.
 *
 * ## Why Enter does the obvious thing in every state
 *
 * Enter runs whichever action the filled button shows: Find until something is found, then Open.
 * Paste, Enter, Enter is the whole interaction, and a dialog that needed the mouse for its second
 * step would be one the keyboard could only half use.
 */
@Composable
fun AddSiteDialog(
	state: AddSiteState.Open,
	onInput: (String) -> Unit,
	onFind: () -> Unit,
	onOpenSource: () -> Unit,
	onOpenManga: () -> Unit,
	onRequestUpstream: () -> Unit,
	onDismiss: () -> Unit,
) {
	val status = state.status
	val found = status as? AddSiteStatus.Found
	val primary: () -> Unit = when {
		found?.manga != null -> onOpenManga
		found != null -> onOpenSource
		else -> onFind
	}
	val canFind = state.input.isNotBlank() && status !is AddSiteStatus.Resolving

	val focus = remember { FocusRequester() }
	// Focused on open, so the dialog can be used straight from a paste. Guarded because a focus
	// request made before the node is attached throws, and a dialog that crashed on open because
	// of keyboard focus would be a strange way to lose the feature.
	LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

	AlertDialog(
		onDismissRequest = onDismiss,
		modifier = Modifier.widthIn(max = 520.dp),
		title = { Text("Add a site") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(AgehaSpacing.md)) {
				Text(
					"Paste a link to a manga site, or to a manga on one. Ageha finds which of its " +
						"sources reads that site, including its mirror addresses.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				OutlinedTextField(
					value = state.input,
					onValueChange = onInput,
					singleLine = true,
					placeholder = { Text("https://comix.to/") },
					isError = status is AddSiteStatus.NotALink,
					modifier = Modifier
						.fillMaxWidth()
						.focusRequester(focus)
						.testTag(ADD_SITE_FIELD_TAG)
						.onPreviewKeyEvent { event ->
							val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
							if (isEnter && event.type == KeyEventType.KeyDown) {
								if (found != null || canFind) primary()
								true
							} else {
								false
							}
						},
				)
				AddSiteResult(status, onRequestUpstream)
			}
		},
		confirmButton = {
			when {
				found?.manga != null -> Button(onClick = onOpenManga) { Text("Open manga") }
				found != null -> Button(onClick = onOpenSource) { Text("Open source") }
				status is AddSiteStatus.Resolving -> Button(onClick = {}, enabled = false) { Text("Finding…") }
				else -> Button(onClick = onFind, enabled = canFind) { Text("Find") }
			}
		},
		dismissButton = {
			Row(horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xs)) {
				// A manga link still offers the site as a whole. Someone who pasted one chapter's
				// address may well have meant "this site", and making them go and find the site's
				// front page to say so would be the dialog being obtuse.
				if (found?.manga != null) {
					TextButton(onClick = onOpenSource) { Text("Open source") }
				}
				TextButton(onClick = onDismiss) { Text("Cancel") }
			}
		},
	)
}

@Composable
private fun AddSiteResult(status: AddSiteStatus, onRequestUpstream: () -> Unit) {
	val muted = MaterialTheme.colorScheme.onSurfaceVariant
	// Stated outright rather than inherited. AlertDialog paints everything in its text slot with
	// onSurfaceVariant, which is right for the explanation and wrong for the answer: the first
	// render of this dialog showed "comix.to -> Comix" in the same grey as the fine print under it,
	// so the one line anyone opened the dialog to read looked like the least important one.
	val strong = MaterialTheme.colorScheme.onSurface
	val body = MaterialTheme.typography.bodyMedium
	when (status) {
		AddSiteStatus.Idle -> Unit

		AddSiteStatus.NotALink -> Text(
			"That doesn't look like a link. Paste the site's address -- comix.to on its own is fine.",
			style = body,
			color = MaterialTheme.colorScheme.error,
		)

		is AddSiteStatus.Resolving -> Row(
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
			verticalAlignment = Alignment.CenterVertically,
		) {
			CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
			Text("Looking for a source that reads ${status.host}…", style = body, color = muted)
		}

		is AddSiteStatus.Found -> Column(verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs)) {
			Text(
				"${status.host}  →  ${status.source.title}",
				style = MaterialTheme.typography.titleMedium,
				color = strong,
			)
			status.manga?.let { manga ->
				Text("This link is a manga: ${manga.title}", style = body, color = strong)
			}
			if (status.source.isBroken) {
				// Said before opening rather than discovered after. A source upstream knows is
				// broken will fail on the next screen, and a user who was not told reads that as
				// Ageha failing.
				Text(
					"Upstream has flagged this source as broken, so it may not load.",
					style = body,
					color = MaterialTheme.colorScheme.error,
				)
			}
			Text(
				"Opening it switches the source on, so it stays in your Enabled list.",
				style = MaterialTheme.typography.bodySmall,
				color = muted,
			)
		}

		is AddSiteStatus.NotFound -> Column(verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs)) {
			Text(
				"No source in this parsers build reads ${status.host}.",
				style = MaterialTheme.typography.titleMedium,
				color = strong,
			)
			Text(
				"Ageha can't learn a new site from a link. Every source is a parser from the " +
					"kotatsu-parsers-redo project, and new sites are added there -- once one is, it " +
					"reaches Ageha with a parser update, no new release needed.",
				style = body,
				color = muted,
			)
			TextButton(onClick = onRequestUpstream) { Text("Request it upstream") }
		}

		is AddSiteStatus.Failed -> Text(
			"Couldn't check ${status.host}: ${status.reason}",
			style = body,
			color = MaterialTheme.colorScheme.error,
		)
	}
}
