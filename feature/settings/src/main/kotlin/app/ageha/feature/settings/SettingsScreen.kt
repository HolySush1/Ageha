package app.ageha.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.ageha.core.designsystem.AgehaAccent
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.FontCoverage
import app.ageha.core.designsystem.ReaderBackground
import app.ageha.core.js.JsRuntime
import app.ageha.core.model.JsCapability
import app.ageha.core.parsers.LockVerification

/** Which panel of the settings screen is showing. */
enum class SettingsSection(val label: String) {
	APPEARANCE("Appearance"),
	READER("Reader"),
	PARSERS("Sources and updates"),
	LIBRARY("Library and backup"),
	ABOUT("About"),
}

/**
 * Settings, laid out as a desktop preferences window: a section list on the left, the panel on the
 * right. Not a scrolling list of every option, which is a phone pattern that stops working the
 * moment there are more than a screenful.
 */
@Composable
fun SettingsScreen(
	theme: AgehaThemeMode,
	readerBackground: ReaderBackground,
	doublePage: Boolean,
	coverOffset: Boolean,
	parsers: ParsersUiState,
	jsRuntime: JsRuntime,
	parsersDescription: (app.ageha.core.parsers.UpdateOutcome?) -> String,
	onTheme: (AgehaThemeMode) -> Unit,
	onReaderBackground: (ReaderBackground) -> Unit,
	onDoublePage: (Boolean) -> Unit,
	onCoverOffset: (Boolean) -> Unit,
	onUpdatePolicy: (UpdatePolicy) -> Unit,
	onCheckForUpdate: () -> Unit,
	onRollBack: () -> Unit,
	onPin: (String?) -> Unit,
	onImportBackup: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var section by remember { mutableStateOf(SettingsSection.APPEARANCE) }
	Row(modifier.fillMaxSize()) {
		Column(
			Modifier
				.width(210.dp)
				.fillMaxHeight()
				.background(MaterialTheme.colorScheme.surfaceContainerLow)
				.padding(vertical = AgehaSpacing.sm),
		) {
			for (option in SettingsSection.entries) {
				SectionRow(option.label, option == section) { section = option }
			}
		}
		Column(
			Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AgehaSpacing.xl),
			verticalArrangement = Arrangement.spacedBy(AgehaSpacing.lg),
		) {
			when (section) {
				SettingsSection.APPEARANCE -> AppearancePanel(theme, onTheme)
				SettingsSection.READER -> ReaderPanel(
					readerBackground, doublePage, coverOffset,
					onReaderBackground, onDoublePage, onCoverOffset,
				)
				SettingsSection.PARSERS -> ParsersPanel(
					parsers, jsRuntime, parsersDescription,
					onUpdatePolicy, onCheckForUpdate, onRollBack, onPin,
				)
				SettingsSection.LIBRARY -> LibraryPanel(onImportBackup)
				SettingsSection.ABOUT -> AboutPanel(parsers)
			}
		}
	}
}

@Composable
private fun SectionRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
	Row(
		Modifier
			.fillMaxWidth()
			.padding(horizontal = AgehaSpacing.sm, vertical = 1.dp)
			.clip(MaterialTheme.shapes.small)
			.background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
			.clickable(onClick = onClick)
			.padding(horizontal = AgehaSpacing.md, vertical = AgehaSpacing.sm),
	) {
		Text(
			label,
			style = MaterialTheme.typography.bodyMedium,
			color = if (isSelected) {
				MaterialTheme.colorScheme.onSecondaryContainer
			} else {
				MaterialTheme.colorScheme.onSurface
			},
		)
	}
}

@Composable
private fun PanelTitle(text: String) {
	Text(text, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Explain(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.bodyMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun AppearancePanel(theme: AgehaThemeMode, onTheme: (AgehaThemeMode) -> Unit) {
	PanelTitle("Appearance")
	for (mode in AgehaThemeMode.entries) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			RadioButton(selected = theme == mode, onClick = { onTheme(mode) })
			Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
		}
	}
	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	val coverage = remember { FontCoverage.detect() }
	Text("Fonts", style = MaterialTheme.typography.titleMedium)
	Explain("Ageha uses whichever of its preferred families this machine has installed.")
	Text("Titles: ${coverage.serif}", style = AgehaTextStyles.metadata)
	Text("Interface: ${coverage.sans}", style = AgehaTextStyles.metadata)
	if (coverage.missingScripts.isNotEmpty()) {
		// Named rather than hidden. A user seeing boxes where a Korean title should be needs to
		// know it is a missing font and not a broken source.
		Text(
			"No font installed for: ${coverage.missingScripts.joinToString(", ")}. " +
				"Titles in those scripts will show as boxes until one is installed.",
			style = AgehaTextStyles.metadata,
			color = MaterialTheme.colorScheme.error,
		)
	}
}

@Composable
private fun ReaderPanel(
	background: ReaderBackground,
	doublePage: Boolean,
	coverOffset: Boolean,
	onBackground: (ReaderBackground) -> Unit,
	onDoublePage: (Boolean) -> Unit,
	onCoverOffset: (Boolean) -> Unit,
) {
	PanelTitle("Reader")
	Text("Background", style = MaterialTheme.typography.titleMedium)
	Explain(
		"Independent of the app theme, and always a neutral -- no brand colour is drawn " +
			"anywhere the artwork is visible.",
	)
	for (option in ReaderBackground.entries) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			RadioButton(selected = background == option, onClick = { onBackground(option) })
			Box(Modifier.size(18.dp).clip(MaterialTheme.shapes.extraSmall).background(option.color))
			Text(option.label, Modifier.padding(start = AgehaSpacing.sm))
		}
	}
	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	Text("Page layout", style = MaterialTheme.typography.titleMedium)
	Row(verticalAlignment = Alignment.CenterVertically) {
		Switch(checked = doublePage, onCheckedChange = onDoublePage)
		Text("Two pages side by side", Modifier.padding(start = AgehaSpacing.sm))
	}
	if (doublePage) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Switch(checked = coverOffset, onCheckedChange = onCoverOffset)
			Text("First page stands alone", Modifier.padding(start = AgehaSpacing.sm))
		}
		Explain(
			"A printed book puts page 1 alone on the right and pairs the rest, so artwork drawn " +
				"across a fold lines up. Turn this off for releases with no cover page.",
		)
	}
	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	Text("Keyboard", style = MaterialTheme.typography.titleMedium)
	for ((keys, meaning) in app.ageha.feature.settings.readerHelp) {
		Row(horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.md)) {
			Text(keys, style = AgehaTextStyles.readerHud, modifier = Modifier.width(150.dp))
			Text(meaning, style = AgehaTextStyles.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

@Composable
private fun ParsersPanel(
	state: ParsersUiState,
	jsRuntime: JsRuntime,
	describe: (app.ageha.core.parsers.UpdateOutcome?) -> String,
	onPolicy: (UpdatePolicy) -> Unit,
	onCheck: () -> Unit,
	onRollBack: () -> Unit,
	onPin: (String?) -> Unit,
) {
	PanelTitle("Sources and updates")
	Explain(
		"Ageha's ${state.sourceCount} manga sources come from an external library that updates " +
			"independently of the app. Sites change constantly, so this is the update that " +
			"matters most -- and it does not need a new version of Ageha.",
	)
	SelectionContainer {
		Column {
			Text("Active build: ${state.activeVersion}", style = AgehaTextStyles.readerHud)
			Text("Bundled with this app: ${state.bundledVersion}", style = AgehaTextStyles.metadata)
		}
	}

	when (val verification = state.verification) {
		is LockVerification.Verified, null -> Unit
		else -> Text(
			// Verification runs before every load, not only after a download: a build can be
			// corrupted on disk between launches, and loading unvouched-for code is worse than
			// losing some source coverage.
			"This build did not verify against its checksum lock and will not be loaded. " +
				"Ageha will fall back to the bundled build. ($verification)",
			style = AgehaTextStyles.metadata,
			color = MaterialTheme.colorScheme.error,
		)
	}

	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	Text("When a new build is found", style = MaterialTheme.typography.titleMedium)
	for (option in UpdatePolicy.entries) {
		Row(verticalAlignment = Alignment.Top) {
			RadioButton(selected = state.policy == option, onClick = { onPolicy(option) })
			Column(Modifier.padding(top = AgehaSpacing.sm)) {
				Text(option.label, style = MaterialTheme.typography.bodyMedium)
				Text(
					option.detail,
					style = AgehaTextStyles.metadata,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}

	Row(horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
		Button(onClick = onCheck, enabled = !state.isChecking) { Text("Check now") }
		if (state.isChecking) CircularProgressIndicator(Modifier.size(18.dp))
		if (state.canRollBack) {
			OutlinedButton(onClick = onRollBack) { Text("Roll back") }
		}
		if (state.isPinned) {
			OutlinedButton(onClick = { onPin(null) }) { Text("Unpin") }
		} else {
			OutlinedButton(onClick = { onPin(state.activeVersion) }) { Text("Pin this build") }
		}
	}
	if (state.isPinned) {
		Explain(
			"Pinned to ${state.state.pinnedVersion}. Ageha will still tell you when a newer " +
				"build exists, but will not install one.",
		)
	}
	describe(state.lastOutcome).takeIf { it.isNotEmpty() }?.let { Explain(it) }
	if (state.rejectedCount > 0) {
		Explain(
			"${state.rejectedCount} build(s) were checked and refused. A refused build is never " +
				"retried, and refusing one costs you nothing -- the build you have keeps working.",
		)
	}

	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	JavaScriptStatus(jsRuntime)
}

/**
 * What the JavaScript engine can and cannot do.
 *
 * Worth its own block because the answer explains a whole class of source failure. Around 257
 * sources fall back to an anti-bot script only *sometimes*, so without this a user sees a source
 * that worked yesterday and does not today, with no way to tell why.
 */
@Composable
private fun JavaScriptStatus(jsRuntime: JsRuntime) {
	Text("JavaScript", style = MaterialTheme.typography.titleMedium)
	val hasPlain = JsCapability.PLAIN_SCRIPT in jsRuntime.capabilities
	val hasBrowser = jsRuntime.capabilities.any { it.requiresBrowser }
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm)) {
		if (hasPlain) AgehaAccent.NewChapterDot()
		Text(
			if (hasPlain) {
				"Script engine: available. Sources that use an anti-bot script will work."
			} else {
				"Script engine: missing. Around 257 sources will fail when a site challenges them."
			},
			style = AgehaTextStyles.metadata,
		)
	}
	Text(
		if (hasBrowser) {
			"Browser component: installed."
		} else {
			"Browser component: not installed. Around 20 of the sources need a real browser " +
				"engine and will say so rather than failing silently."
		},
		style = AgehaTextStyles.metadata,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun LibraryPanel(onImportBackup: () -> Unit) {
	PanelTitle("Library and backup")
	Explain(
		"Ageha reads the backup file the Android app produces: library, categories, favourites, " +
			"history and reading positions. Anything it cannot restore is reported rather than " +
			"skipped quietly.",
	)
	Button(onClick = onImportBackup) { Text("Import an Android backup") }
	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	Explain(
		"Ageha's own backup export lands with the sync work. Until then the database file is " +
			"portable on its own -- copying it moves your whole library.",
	)
}

@Composable
private fun AboutPanel(parsers: ParsersUiState) {
	PanelTitle("Ageha")
	Explain("A desktop manga reader. GPL-3.0, ported from the Kotatsu-Redo Android app.")
	SelectionContainer {
		Column(verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs)) {
			Text("Sources: ${parsers.sourceCount}", style = AgehaTextStyles.metadata)
			Text("Parsers build: ${parsers.activeVersion}", style = AgehaTextStyles.metadata)
			Text(
				"Parsers come from Kotatsu-Redo/kotatsu-parsers-redo and are not part of Ageha.",
				style = AgehaTextStyles.metadata,
			)
		}
	}
	TextButton(onClick = {}) { Text("Licences") }
}

/**
 * The reader's key bindings, restated for the settings screen.
 *
 * Duplicated from `:feature:reader` rather than depended on: settings would otherwise have to
 * depend on the reader module purely to render a table of strings, and the two modules have
 * nothing else to say to each other. `ReaderKeysTest` asserts the reader's own list stays
 * complete; this one is documentation and is allowed to be a shorter summary.
 */
internal val readerHelp: List<Pair<String, String>> = listOf(
	"Left / Right" to "Turn the page, in reading order",
	"Space / Shift+Space" to "Forward / back",
	"Page Up / Page Down" to "Forward / back",
	"Home / End" to "First / last page",
	"N / P" to "Next / previous chapter",
	"1 / 2 / 3 / 4" to "Fit page / width / height / original",
	"F or F11" to "Fullscreen",
	"H" to "Show or hide the controls",
	"Ctrl + wheel" to "Zoom about the pointer",
	"Escape" to "Close the reader",
)
