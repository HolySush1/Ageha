package app.ageha.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ageha.core.designsystem.AgehaAccent
import app.ageha.core.designsystem.AgehaSegmented
import app.ageha.core.designsystem.AgehaSelect
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaSwitch
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.MotionPreference
import app.ageha.core.designsystem.CardStyle
import app.ageha.core.designsystem.FontCoverage
import app.ageha.core.designsystem.PanelHeading
import app.ageha.core.designsystem.ReaderBackground
import app.ageha.core.designsystem.SettingRow
import app.ageha.core.designsystem.SettingsRows
import app.ageha.core.designsystem.dashedBorder
import app.ageha.core.js.JsRuntime
import app.ageha.core.model.JsCapability
import app.ageha.core.model.PageScale
import app.ageha.core.model.ReaderMode
import app.ageha.core.network.AgehaPaths
import app.ageha.core.parsers.LockVerification

/**
 * Which panel of the settings screen is showing.
 *
 * Seven slots, in the handoff's order. Six carry the handoff's own name; the seventh is where it
 * puts **Tracking** (AniList / MAL / Kitsu) and Ageha puts [SYNC] instead. That is CLAUDE.md rule
 * 9 -- external tracking services are permanently out of scope -- and it is not an unfinished
 * corner: WIRING.md itself notes that row needs an OAuth flow the mockup has no UI for, and
 * "where was I and what is next" is answered locally by Continue Reading.
 */
enum class SettingsSection(val label: String) {
	READING("Reading"),
	LIBRARY("Library"),
	SOURCES("Sources"),
	DOWNLOADS("Downloads"),
	APPEARANCE("Appearance"),
	SYNC("Sync"),
	ABOUT("About"),
}

/**
 * Settings: a 188dp rail on the left, one panel on the right.
 *
 * ## Why the controls are selects and segments now, and not radio groups
 *
 * This screen used to be a vertical stack of radio buttons under sub-headings, which shows every
 * option without a click and is the better control *given room*. The handoff's layout does not
 * have that room: its rows are a label and a mono hint on the left with one control on the right,
 * and a stack of radios cannot enter that layout. Matching the look meant matching the control.
 *
 * The division between the two is arity rather than taste. Two or three short options are a
 * segmented group -- everything visible, one click to change. Four or more, or options long enough
 * to wrap, are a select. See `AgehaSelect`.
 *
 * ## What sits below the rows
 *
 * Not everything here is a setting. Sign-in is a form, the parsers block is three buttons and a
 * status, and the licence list is a document. Those follow the row container rather than being
 * forced into it -- a row whose "control" is a 200dp password field is a row in name only.
 */
@Composable
fun SettingsScreen(
	theme: AgehaThemeMode,
	/** Whether Ageha animates. See `MotionPreference` and the desktop app's `SystemMotion`. */
	motion: MotionPreference,
	readerBackground: ReaderBackground,
	doublePage: Boolean,
	coverOffset: Boolean,
	parsers: ParsersUiState,
	jsRuntime: JsRuntime,
	parsersDescription: (app.ageha.core.parsers.UpdateOutcome?) -> String,
	onTheme: (AgehaThemeMode) -> Unit,
	onMotion: (MotionPreference) -> Unit,
	onReaderBackground: (ReaderBackground) -> Unit,
	onDoublePage: (Boolean) -> Unit,
	onCoverOffset: (Boolean) -> Unit,
	onUpdatePolicy: (UpdatePolicy) -> Unit,
	onCheckForUpdate: () -> Unit,
	onRollBack: () -> Unit,
	onPin: (String?) -> Unit,
	onImportBackup: () -> Unit,
	onExportBackup: () -> Unit,
	appUpdates: AppUpdatesUiState,
	onAppUpdatePolicy: (AppUpdatePolicy) -> Unit,
	onCheckForAppUpdate: () -> Unit,
	sync: SyncUiState,
	onSignIn: (String, String, String, Boolean) -> Unit,
	onSignOut: () -> Unit,
	onSyncNow: () -> Unit,
	onSyncOnStart: (Boolean) -> Unit,
	onClearHistory: () -> Unit,
	historyCount: Int,
	/** The source picker's filters, mirrored here because this is where people look for them. */
	hideBrokenSources: Boolean,
	showAdultSources: Boolean,
	onHideBrokenSources: (Boolean) -> Unit,
	onShowAdultSources: (Boolean) -> Unit,
	/**
	 * Opens a CBZ from disk.
	 *
	 * Here because the window's native menu bar is gone -- Ageha draws its own caption now, and a
	 * Swing menu strip under a custom title bar looks like two applications stacked. Every other
	 * item that menu carried already existed somewhere on this screen; this one did not, and
	 * leaving it on Ctrl+O alone would have made it a feature only someone reading the source
	 * could find.
	 */
	onOpenArchive: () -> Unit = {},
	/** The handoff's `opts.mode`. A default for manga that have never been given one. */
	readerMode: ReaderMode = ReaderMode.DEFAULT,
	onReaderMode: (ReaderMode) -> Unit = {},
	/** The handoff's `opts.fit`. */
	pageScale: PageScale = PageScale.FIT_PAGE,
	onPageScale: (PageScale) -> Unit = {},
	/** The handoff's `opts.preload`. Zero means the whole chapter. */
	preloadPages: Int = 6,
	onPreloadPages: (Int) -> Unit = {},
	/** The handoff's `opts.grid`. */
	cardStyle: CardStyle = CardStyle.COVER,
	onCardStyle: (CardStyle) -> Unit = {},
	/** The handoff's `opts.nsfwBlur`. */
	blurAdultCovers: Boolean = false,
	onBlurAdultCovers: (Boolean) -> Unit = {},
	/** The handoff's `opts.parallel`, per source. */
	parallelDownloads: Int = 2,
	onParallelDownloads: (Int) -> Unit = {},
	/** Whether the library's shelf rail starts folded. */
	railCollapsed: Boolean = false,
	onRailCollapsed: (Boolean) -> Unit = {},
	modifier: Modifier = Modifier,
	/**
	 * Which panel opens first.
	 *
	 * A parameter rather than always Appearance so the headless render can draw each panel. Every
	 * one of them is a screen that can fail to compose, and the ones reached by three clicks are
	 * precisely the ones nobody checks before a release.
	 */
	initialSection: SettingsSection = SettingsSection.APPEARANCE,
	/**
	 * Fetch and start the optional browser component. Null hides the offer entirely.
	 *
	 * Nullable so the headless render and the tests can draw this panel without a handler, and so
	 * the offer is absent rather than inert when nothing can service it -- the mistake the failure
	 * panel's remedy button made for two releases.
	 */
	onInstallBrowser: (() -> Unit)? = null,
	/** What that install is doing, while it runs. Null when nothing is running. */
	browserProgress: String? = null,
	/** Image plus HTTP cache, in bytes. See the Downloads panel. */
	cacheBytes: Long = 0L,
	/** The browser component's profile, in bytes. Reported only. */
	browserCacheBytes: Long = 0L,
	/** Empty the image and HTTP caches. Null draws no button. */
	onClearCache: (() -> Unit)? = null,
	clearingCache: Boolean = false,
) {
	var section by remember { mutableStateOf(initialSection) }
	Row(
		modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 40.dp),
		horizontalArrangement = Arrangement.spacedBy(26.dp),
	) {
		Column(Modifier.width(188.dp).fillMaxHeight()) {
			for (option in SettingsSection.entries) {
				SectionRow(option.label, option == section) { section = option }
			}
			Spacer(Modifier.weight(1f))
			// Where the settings actually live, in a dashed box at the foot of the rail.
			//
			// The handoff puts this here and it earns its place: every value on this screen is
			// written to one file, and someone syncing a machine, filing a bug or recovering a
			// profile needs the path. Dashed rather than solid, because it is a note about the
			// application rather than a control in it.
			Box(
				Modifier
					.padding(AgehaSpacing.md)
					.fillMaxWidth()
					.dashedBorder(AgehaTheme.skin.lineStrong, MaterialTheme.shapes.medium)
					.padding(AgehaSpacing.sm),
			) {
				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(
						"Settings are stored in",
						style = AgehaTextStyles.monoMeta,
						color = AgehaTheme.skin.inkFaint,
					)
					Text(
						AgehaPaths.dataDir.resolve("preferences.json").path,
						style = AgehaTextStyles.monoMeta,
						color = AgehaTheme.skin.inkFaint,
					)
				}
			}
		}
		Column(
			Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(AgehaSpacing.lg),
		) {
			when (section) {
				SettingsSection.READING -> ReadingPanel(
					readerMode, onReaderMode,
					doublePage, onDoublePage,
					coverOffset, onCoverOffset,
					pageScale, onPageScale,
					readerBackground, onReaderBackground,
					preloadPages, onPreloadPages,
				)

				SettingsSection.LIBRARY -> LibraryPanel(
					cardStyle, onCardStyle,
					blurAdultCovers, onBlurAdultCovers,
					railCollapsed, onRailCollapsed,
					onImportBackup, onExportBackup, onClearHistory, historyCount, onOpenArchive,
				)

				SettingsSection.SOURCES -> SourcesPanel(
					parsers, jsRuntime, parsersDescription,
					onUpdatePolicy, onCheckForUpdate, onRollBack, onPin,
					hideBrokenSources, showAdultSources,
					onHideBrokenSources, onShowAdultSources,
					onInstallBrowser, browserProgress,
				)

				SettingsSection.DOWNLOADS -> DownloadsPanel(
					parallel = parallelDownloads,
					onParallel = onParallelDownloads,
					cacheBytes = cacheBytes,
					browserCacheBytes = browserCacheBytes,
					onClearCache = onClearCache,
					clearingCache = clearingCache,
				)

				SettingsSection.APPEARANCE -> AppearancePanel(theme, onTheme, motion, onMotion)

				SettingsSection.SYNC -> SyncPanel(
					sync, onSignIn, onSignOut, onSyncNow, onSyncOnStart,
				)

				SettingsSection.ABOUT -> AboutPanel(
					parsers, appUpdates, onAppUpdatePolicy, onCheckForAppUpdate,
				)
			}
		}
	}
}

@Composable
private fun SectionRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
	Row(
		Modifier
			.fillMaxWidth()
			.padding(vertical = 1.dp)
			.clip(MaterialTheme.shapes.medium)
			// `--accent-soft` and full-strength ink, as the handoff's selected rail row is.
			// Unselected carries no fill and no border at all: a rail is a list of places, and
			// bordering every one of them turns a quiet column into seven competing buttons.
			.background(
				if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
			)
			.clickable(onClick = onClick)
			.padding(horizontal = 13.dp, vertical = 10.dp),
	) {
		Text(
			label,
			style = MaterialTheme.typography.bodyMedium,
			color = if (isSelected) {
				MaterialTheme.colorScheme.onSurface
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
		)
	}
}

/**
 * Reading.
 *
 * ## The page-flow controls are one decision split three ways
 *
 * `Reading mode` picks the flow, `Right to left` picks the direction, and `Two pages` pairs them.
 * The handoff offers Single / Double / Long strip as one segmented control, which folds the
 * pairing into the mode; Ageha keeps them apart because its reader mode is *per manga* while the
 * pairing is global -- a reader who set one webtoon to strip mode has not asked every tankoubon to
 * stop pairing pages.
 *
 * The direction and pairing rows disappear in Long strip. A control that cannot do anything is
 * worse than a missing one: it invites a click and answers with nothing.
 */
@Composable
private fun ReadingPanel(
	mode: ReaderMode,
	onMode: (ReaderMode) -> Unit,
	doublePage: Boolean,
	onDoublePage: (Boolean) -> Unit,
	coverOffset: Boolean,
	onCoverOffset: (Boolean) -> Unit,
	scale: PageScale,
	onScale: (PageScale) -> Unit,
	background: ReaderBackground,
	onBackground: (ReaderBackground) -> Unit,
	preload: Int,
	onPreload: (Int) -> Unit,
) {
	PanelHeading(
		"Reading",
		"How a chapter opens when you have not told Ageha anything about that title in " +
			"particular. Everything here is a default: the reader's own controls still write a " +
			"per-manga override, because a webtoon and a scanlated volume want different answers.",
	)
	val isStrip = mode == ReaderMode.WEBTOON
	SettingsRows(
		buildList {
			add {
				SettingRow(
					"Reading mode",
					if (isStrip) "continuous vertical strip" else "one page at a time",
				) {
					AgehaSegmented(
						value = isStrip,
						options = listOf(false, true),
						onSelect = { strip ->
							onMode(if (strip) ReaderMode.WEBTOON else ReaderMode.DEFAULT)
						},
						label = { if (it) "Long strip" else "Paged" },
					)
				}
			}
			if (!isStrip) {
				add {
					SettingRow(
						"Right to left",
						"japanese reading order · flips arrows and page ticks",
					) {
						AgehaSwitch(
							checked = mode == ReaderMode.REVERSED,
							onCheckedChange = {
								onMode(if (it) ReaderMode.REVERSED else ReaderMode.STANDARD)
							},
						)
					}
				}
				add {
					SettingRow(
						"Two pages side by side",
						"needs a window wide enough for both at a readable size",
					) {
						AgehaSwitch(checked = doublePage, onCheckedChange = onDoublePage)
					}
				}
				if (doublePage) {
					add {
						SettingRow(
							"First page stands alone",
							"a printed book pairs from page 2, so spreads line up",
						) {
							AgehaSwitch(checked = coverOffset, onCheckedChange = onCoverOffset)
						}
					}
				}
			}
			add {
				SettingRow("Page fit", "how a page is scaled into the window") {
					AgehaSelect(
						value = scale,
						options = PageScale.entries,
						onSelect = onScale,
						label = { it.label },
					)
				}
			}
			add {
				SettingRow(
					"Reader background",
					"always a neutral · no brand colour touches the artwork",
				) {
					AgehaSelect(
						value = background,
						options = ReaderBackground.entries,
						onSelect = onBackground,
						label = { it.label },
					)
				}
			}
			add {
				SettingRow(
					"Preload next pages",
					"fetched ahead of what is on screen, so a page turn does not wait",
				) {
					AgehaSelect(
						value = preload,
						options = PRELOAD_OPTIONS,
						onSelect = onPreload,
						label = ::preloadLabel,
					)
				}
			}
		},
	)
	Text("Keyboard", style = MaterialTheme.typography.titleMedium)
	SettingsRows(
		readerHelp.map { (keys, meaning) ->
			{
				SettingRow(meaning, keys) {}
			}
		},
	)
}

/** The handoff's 3 / 6 / 12 / Whole chapter. Zero is the whole chapter; see `Preferences`. */
private val PRELOAD_OPTIONS = listOf(3, 6, 12, 0)

private fun preloadLabel(pages: Int): String = if (pages == 0) "Whole chapter" else "$pages pages"

/**
 * Library: how the shelf is drawn, and the things that move data in or out of it.
 *
 * Backup, archive-opening and history-clearing sit below the rows rather than inside them. They
 * are actions, not settings -- a row whose control is "Import an Android backup" reads as a
 * preference you can leave switched on.
 */
@Composable
private fun LibraryPanel(
	cardStyle: CardStyle,
	onCardStyle: (CardStyle) -> Unit,
	blurAdult: Boolean,
	onBlurAdult: (Boolean) -> Unit,
	railCollapsed: Boolean,
	onRailCollapsed: (Boolean) -> Unit,
	onImportBackup: () -> Unit,
	onExportBackup: () -> Unit,
	onClearHistory: () -> Unit,
	historyCount: Int,
	onOpenArchive: () -> Unit,
) {
	PanelHeading(
		"Library",
		"Your own collection: how densely it is drawn, and how it gets in and out of Ageha. " +
			"Nothing here leaves this machine.",
	)
	SettingsRows(
		listOf(
			{
				SettingRow("Card style", "how many covers fit across the shelf") {
					AgehaSegmented(
						value = cardStyle,
						options = CardStyle.entries,
						onSelect = onCardStyle,
						label = { it.label },
					)
				}
			},
			{
				SettingRow(
					"Blur 18+ covers",
					"until the pointer is on them · titles stay legible",
				) {
					AgehaSwitch(checked = blurAdult, onCheckedChange = onBlurAdult)
				}
			},
			{
				SettingRow(
					"Fold the shelf rail",
					"hands roughly one more column back to the grid",
				) {
					AgehaSwitch(checked = railCollapsed, onCheckedChange = onRailCollapsed)
				}
			},
		),
	)

	Text("Local files", style = MaterialTheme.typography.titleMedium)
	Explain(
		"Open a comic archive from this machine -- a .cbz or a plain .zip of images. It opens in " +
			"the reader without being added to your library. Ctrl+O does the same from anywhere.",
	)
	Button(onClick = onOpenArchive) { Text("Open a comic archive...") }

	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	Text("Backup", style = MaterialTheme.typography.titleMedium)
	Explain(
		"Ageha reads the backup file the Android app produces: library, categories, favourites, " +
			"history and reading positions. Anything it cannot restore is reported rather than " +
			"skipped quietly. Export writes the same format back, so it restores into Ageha and " +
			"into Kotatsu-Redo on a phone. Downloaded chapters are not included -- they are " +
			"ordinary CBZ files already, and copying the folder moves them.",
	)
	Row(horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm)) {
		Button(onClick = onImportBackup) { Text("Import an Android backup") }
		OutlinedButton(onClick = onExportBackup) { Text("Export a backup") }
	}

	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	Text("Reading history", style = MaterialTheme.typography.titleMedium)
	Explain(
		"Continue Reading is built from this history, and it never leaves your machine -- there " +
			"is no account behind it and nothing is sent anywhere. Clearing it empties the " +
			"Continue Reading banner and shelf. Your library, favourites and downloads are not " +
			"touched.",
	)
	// Two-step, and the second step names the number. This is the one irreversible button in
	// settings -- soft-deleted rows are tombstones, not an undo -- and a single click that
	// silently discards years of reading positions is not a button, it is a trap.
	var confirming by remember { mutableStateOf(false) }
	if (confirming) {
		Row(horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm)) {
			Button(
				onClick = {
					onClearHistory()
					confirming = false
				},
				colors = ButtonDefaults.buttonColors(
					containerColor = MaterialTheme.colorScheme.error,
					contentColor = MaterialTheme.colorScheme.onError,
				),
			) {
				Text("Clear $historyCount entries permanently")
			}
			TextButton(onClick = { confirming = false }) { Text("Cancel") }
		}
	} else {
		TextButton(onClick = { confirming = true }, enabled = historyCount > 0) {
			Text(if (historyCount > 0) "Clear reading history" else "Nothing to clear")
		}
	}
}

/**
 * Sources: what the catalogue shows, and where the parsers come from.
 *
 * The two filter rows write the **same keys** the Explore filter rail writes, which is the
 * handoff's own instruction ("Sources mirrors the four Explore filters -- same state object").
 * Ageha has three real filters rather than four: there is no unverified-mirror tier to show, so
 * that row is absent rather than present and inert. The language filter lives only in Explore,
 * where the handoff puts its chips and where the list of languages actually exists.
 */
@Composable
private fun SourcesPanel(
	state: ParsersUiState,
	jsRuntime: JsRuntime,
	describe: (app.ageha.core.parsers.UpdateOutcome?) -> String,
	onPolicy: (UpdatePolicy) -> Unit,
	onCheck: () -> Unit,
	onRollBack: () -> Unit,
	onPin: (String?) -> Unit,
	hideBrokenSources: Boolean,
	showAdultSources: Boolean,
	onHideBrokenSources: (Boolean) -> Unit,
	onShowAdultSources: (Boolean) -> Unit,
	/** Fetch and start the optional browser component. Null hides the offer entirely. */
	onInstallBrowser: (() -> Unit)?,
	/** What that install is doing, while it runs. Null when nothing is running. */
	browserProgress: String?,
) {
	PanelHeading(
		"Sources",
		"Ageha's ${state.sourceCount} manga sources come from a library that updates " +
			"independently of the app. Sites change constantly, so this is the update that " +
			"matters most -- and it does not need a new version of Ageha.",
	)
	SettingsRows(
		listOf(
			{
				SettingRow("Hide 18+ content", "adult catalogues stay out of explore and search") {
					AgehaSwitch(
						checked = !showAdultSources,
						onCheckedChange = { onShowAdultSources(!it) },
					)
				}
			},
			{
				SettingRow("Hide broken sources", "upstream has flagged these as not working") {
					AgehaSwitch(checked = hideBrokenSources, onCheckedChange = onHideBrokenSources)
				}
			},
			{
				SettingRow("When a new build is found", state.policy.detail.lowercase()) {
					AgehaSelect(
						value = state.policy,
						options = UpdatePolicy.entries,
						onSelect = onPolicy,
						label = { it.label },
					)
				}
			},
		),
	)
	Explain(
		"The same two switches live in the source picker's filter rail -- they are one setting " +
			"seen from two places. They change what the catalogue offers you, not what Ageha can " +
			"do: a source you have already turned on keeps working everywhere, search included.",
	)

	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

	Row(
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Button(onClick = onCheck, enabled = !state.isChecking) { Text("Check now") }
		if (state.isChecking) CircularProgressIndicator(Modifier.size(18.dp))
		if (state.canRollBack) OutlinedButton(onClick = onRollBack) { Text("Roll back") }
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
	JavaScriptStatus(jsRuntime, onInstallBrowser, browserProgress)
}

/**
 * Downloads.
 *
 * One row, and it is the only one here with a real backend. The handoff also lists Wi-Fi only and
 * an image-quality setting; neither is built, and neither is drawn. A desktop JVM has no portable
 * way to ask whether a connection is metered, and Ageha stores what the source served rather than
 * recompressing it -- so both rows would be switches that change nothing, which is worse than a
 * shorter panel.
 */
@Composable
private fun DownloadsPanel(
	parallel: Int,
	onParallel: (Int) -> Unit,
	/** Image plus HTTP cache, in bytes. What "Clear cache" would reclaim. */
	cacheBytes: Long,
	/** The browser component's Chromium profile, in bytes. Reported, never cleared -- see below. */
	browserCacheBytes: Long,
	onClearCache: (() -> Unit)?,
	clearingCache: Boolean,
) {
	PanelHeading(
		"Downloads",
		"Chapters are saved as ordinary CBZ files, readable in any comic reader. What is on " +
			"this device, and the controls for reclaiming space, live on the Downloads screen.",
	)
	SettingsRows(
		listOf(
			{
				SettingRow(
					"Parallel downloads",
					"per source · low on purpose, these are small sites",
				) {
					AgehaSelect(
						value = parallel,
						options = PARALLEL_OPTIONS,
						onSelect = onParallel,
						label = { "$it at a time" },
					)
				}
			},
		),
	)
	Explain(
		"Per source rather than overall, which is the number that matters: eighty connections to " +
			"one small site is how an application gets its whole user base blocked. Raising this " +
			"speeds up a queue spread across several sources and does very little for one.",
	)

	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

	// The cache, which until now was invisible.
	//
	// Reading a chapter writes every page of it to disk whether or not anything was downloaded --
	// Coil caches the images, OkHttp caches the responses -- and neither was reported anywhere or
	// removable from inside the application. Up to three quarters of a gigabyte of manga could sit
	// under the user's profile with no way to find it, which for a reader is a privacy question
	// as much as a disk one.
	Text("Cache", style = MaterialTheme.typography.titleMedium)
	SettingsRows(
		listOf(
			{
				SettingRow(
					"Cached pages and covers",
					// The size is the hint rather than a separate line, because the size *is* the
					// reason anyone reads this row.
					formatCacheBytes(cacheBytes) + " · rebuilt automatically as you read",
				) {
					if (onClearCache != null) {
						Button(onClick = onClearCache, enabled = !clearingCache && cacheBytes > 0) {
							Text(if (clearingCache) "Clearing…" else "Clear cache")
						}
					}
				}
			},
		),
	)
	Explain(
		"Nothing you have downloaded is touched -- those are CBZ files on the Downloads screen, " +
			"and they are the only copies Ageha treats as yours. This is the throwaway copy of " +
			"everything you have merely looked at, and clearing it costs you nothing but a " +
			"re-fetch of anything you open again.",
	)
	if (browserCacheBytes > 0) {
		Explain(
			"The browser component holds a further " + formatCacheBytes(browserCacheBytes) +
				", and is left alone on purpose: that folder is its cookie store, so emptying it " +
				"would sign you out of every source you have logged into and throw away the " +
				"anti-bot clearances that make those sources work.",
		)
	}
}

/**
 * Bytes as something a person can read.
 *
 * Binary units, and one decimal below 10 so that a cache creeping past a gigabyte reads as "1.4 GB"
 * rather than flattening to "1 GB" -- this number exists to be watched, and a figure that only
 * moves in whole gigabytes looks stuck.
 */
private fun formatCacheBytes(bytes: Long): String {
	if (bytes <= 0L) return "empty"
	val units = listOf("B", "KB", "MB", "GB", "TB")
	var value = bytes.toDouble()
	var unit = 0
	while (value >= 1024 && unit < units.lastIndex) {
		value /= 1024
		unit++
	}
	return when {
		unit == 0 -> "${value.toInt()} ${units[unit]}"
		value < 10 -> String.format("%.1f %s", value, units[unit])
		else -> "${value.toInt()} ${units[unit]}"
	}
}

/** The handoff's 1 / 3 / 5 / 8, plus Ageha's own polite default of 2. */
private val PARALLEL_OPTIONS = listOf(1, 2, 3, 5, 8)

@Composable
private fun Explain(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.bodyMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun AppearancePanel(
	theme: AgehaThemeMode,
	onTheme: (AgehaThemeMode) -> Unit,
	motion: MotionPreference,
	onMotion: (MotionPreference) -> Unit,
) {
	PanelHeading(
		"Appearance",
		"Ember is flat, warm and opaque; Glass is frosted, cool and fully rounded. They are the " +
			"same layout in two materials, and the switcher in the title bar changes the same " +
			"setting this row does.",
	)
	SettingsRows(
		listOf(
			{
				SettingRow("Skin", themeHint(theme)) {
					AgehaSelect(
						value = theme,
						options = AgehaThemeMode.entries,
						onSelect = onTheme,
						label = ::themeLabel,
					)
				}
			},
			{
				SettingRow("Motion", motionHint(motion)) {
					AgehaSelect(
						value = motion,
						options = MotionPreference.entries,
						onSelect = onMotion,
						label = ::motionLabel,
					)
				}
			},
		),
	)
	val coverage = remember { FontCoverage.detect() }
	Text("Fonts", style = MaterialTheme.typography.titleMedium)
	Explain(
		"Ageha bundles the two faces its design is drawn in, so they look the same on every " +
			"machine. Only CJK coverage depends on what is installed here.",
	)
	Text("Interface: ${coverage.ui}", style = AgehaTextStyles.metadata)
	Text("Data and labels: ${coverage.mono}", style = AgehaTextStyles.metadata)
	if (coverage.usesBundledCjk) {
		// Said out loud, because it explains why titles look different here than on a machine with
		// its own CJK fonts -- and because someone who then installs their distribution's font
		// package should know Ageha will stop using the bundled one.
		Explain(
			"This machine has no CJK font installed, so Ageha is using the face bundled with " +
				"the Linux packages. Installing your distribution's Noto CJK package will take " +
				"precedence over it.",
		)
	}
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

private fun motionLabel(preference: MotionPreference): String = when (preference) {
	MotionPreference.SYSTEM -> "Follow Windows"
	MotionPreference.FULL -> "Full"
	MotionPreference.REDUCED -> "Reduced"
}

/**
 * What each motion setting actually does, stated rather than implied.
 *
 * "Follow Windows" is the one that needs explaining: nothing on this screen tells the user which
 * Windows setting is being followed, and a row whose current value depends on a switch three
 * menus deep in another application is a row people distrust. So it names the switch.
 */
private fun motionHint(preference: MotionPreference): String = when (preference) {
	MotionPreference.SYSTEM ->
		"Follows Settings > Accessibility > Visual effects > Animation effects"
	MotionPreference.FULL -> "Animate regardless of the Windows setting"
	MotionPreference.REDUCED -> "No transitions anywhere. Every change is instant"
}

private fun themeLabel(mode: AgehaThemeMode): String = when (mode) {
	AgehaThemeMode.SYSTEM -> "Follow system"
	AgehaThemeMode.LIGHT -> "Light"
	AgehaThemeMode.EMBER -> "Ember"
	AgehaThemeMode.GLASS -> "Glass"
	AgehaThemeMode.AMOLED -> "AMOLED"
}

private fun themeHint(mode: AgehaThemeMode): String = when (mode) {
	AgehaThemeMode.SYSTEM -> "follows the os, resolving dark to ember"
	AgehaThemeMode.LIGHT -> "paper surfaces, indigo chrome"
	AgehaThemeMode.EMBER -> "flat · warm near-black · red accent"
	AgehaThemeMode.GLASS -> "frosted · cool blue-grey · violet accent"
	AgehaThemeMode.AMOLED -> "true black backmost surfaces, for oled panels"
}

/**
 * What the JavaScript engine can and cannot do.
 *
 * Worth its own block because the answer explains a whole class of source failure. Around 257
 * sources fall back to an anti-bot script only *sometimes*, so without this a user sees a source
 * that worked yesterday and does not today, with no way to tell why.
 */
@Composable
private fun JavaScriptStatus(
	jsRuntime: JsRuntime,
	onInstallBrowser: (() -> Unit)?,
	browserProgress: String?,
) {
	Text("JavaScript", style = MaterialTheme.typography.titleMedium)
	val hasPlain = JsCapability.PLAIN_SCRIPT in jsRuntime.capabilities
	val hasBrowser = jsRuntime.capabilities.any { it.requiresBrowser }
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
	) {
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
	// The same install the failure panel offers, reachable before anything has broken.
	//
	// Discovering an optional component only at the moment a source fails is a poor way to find
	// out it exists -- and it is the *slow* moment to find out, because the download is hundreds
	// of megabytes and the user is standing in front of a manga they wanted to read now. Someone
	// who knows they use one of those sources can get it out of the way here.
	if (!hasBrowser && onInstallBrowser != null) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
		) {
			Button(onClick = onInstallBrowser, enabled = browserProgress == null) {
				Text("Install browser component")
			}
			browserProgress?.let {
				Text(it, style = AgehaTextStyles.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
		Explain(
			"Around 200MB of Chromium, downloaded once into Ageha's data directory. It is not " +
				"part of the installer because the other ~1,340 sources never start it.",
		)
	}
}

/**
 * About: what this is, what updates it, and what it is built out of.
 *
 * The licence list is here rather than behind a dead button. It was a `TextButton(onClick = {})`,
 * which was the one control on this screen that genuinely did nothing -- and for a GPL-3.0
 * application whose dependencies carry their own terms, an inert Licences button is not a cosmetic
 * omission.
 */
@Composable
private fun AboutPanel(
	parsers: ParsersUiState,
	appUpdates: AppUpdatesUiState,
	onPolicy: (AppUpdatePolicy) -> Unit,
	onCheck: () -> Unit,
) {
	PanelHeading(
		"About",
		"Ageha ${appUpdates.currentVersion} -- a desktop manga reader, GPL-3.0, ported from the " +
			"Kotatsu-Redo Android app. It updates far more rarely than its sources do: a site " +
			"changing needs a new parsers build, not a new Ageha.",
	)
	SettingsRows(
		listOf(
			{
				SettingRow("Update channel", appUpdates.policy.detail.lowercase()) {
					AgehaSelect(
						value = appUpdates.policy,
						options = AppUpdatePolicy.entries,
						onSelect = onPolicy,
						label = { it.label },
					)
				}
			},
			{
				SettingRow(
					"Version",
					"sources ${parsers.sourceCount} · parsers ${parsers.activeVersion}",
				) {
					OutlinedButton(onClick = onCheck, enabled = !appUpdates.isChecking) {
						Text(if (appUpdates.isChecking) "Checking..." else "Check for updates")
					}
				}
			},
		),
	)
	Explain(
		"Installing an update is your installer's job, not Ageha's: Windows, macOS and Linux each " +
			"handle it their own way and none of them can be switched on or off from in here. " +
			"What this setting controls is whether Ageha looks, and whether it tells you.",
	)
	appUpdates.lastResult?.let { SelectionContainer { Explain(it) } }

	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	var licences by remember { mutableStateOf(false) }
	TextButton(onClick = { licences = !licences }) {
		Text(if (licences) "Hide licences" else "Licences")
	}
	if (licences) {
		SelectionContainer {
			SettingsRows(
				LICENCES.map { entry ->
					{
						SettingRow(entry.name, entry.role) {
							Text(
								entry.terms,
								style = AgehaTextStyles.monoMeta,
								color = AgehaTheme.skin.inkFaint,
							)
						}
					}
				},
			)
		}
		Explain(
			"Ageha itself is GPL-3.0. The full text ships as LICENSE beside the application, and " +
				"NOTICE.md records every component above with its copyright line. The parsers " +
				"are a separate project and are not part of Ageha.",
		)
	}
}

/** One row of the licence list. */
private data class Licence(val name: String, val terms: String, val role: String)

/**
 * What Ageha is built out of, as the About panel lists it.
 *
 * A constant rather than a generated list. A build-time scan of the resolved dependency graph
 * would be more complete and much less useful -- it would name forty transitive artefacts nobody
 * chose, and bury the handful that actually shape what this application is.
 */
private val LICENCES = listOf(
	Licence("Ageha", "GPL-3.0-or-later", "this application"),
	Licence("kotatsu-parsers-redo", "Apache-2.0", "every manga source"),
	Licence("Archivo", "SIL OFL 1.1", "the interface face"),
	Licence("JetBrains Mono", "SIL OFL 1.1", "counts, paths and labels"),
	Licence("Compose Multiplatform", "Apache-2.0", "the whole interface"),
	Licence("OkHttp", "Apache-2.0", "every network request"),
	Licence("Room", "Apache-2.0", "the library database"),
	Licence("Coil", "Apache-2.0", "cover art"),
	Licence("Koin", "Apache-2.0", "wiring"),
	Licence("Rhino", "MPL-2.0", "the script engine"),
)

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

/**
 * Sync against a self-hosted kotatsu-syncserver.
 *
 * Two things this panel says out loud that a settings screen usually would not, because both are
 * things a user is entitled to know before typing a password into an application:
 *
 *  - **Where the password goes.** Desktop has no system keychain a plain JVM can reach, so a
 *    remembered password is a file on this machine. Saying so is the difference between a user
 *    making that choice and discovering it.
 *  - **That the server is theirs.** This is not an Ageha service, and there is no default host.
 *    Nothing is sent anywhere until an address is typed here.
 *
 * This is the slot the handoff gives to **Tracking**. See [SettingsSection] for why Ageha has none.
 */
@Composable
private fun SyncPanel(
	state: SyncUiState,
	onSignIn: (String, String, String, Boolean) -> Unit,
	onSignOut: () -> Unit,
	onSyncNow: () -> Unit,
	onSyncOnStart: (Boolean) -> Unit,
) {
	PanelHeading(
		"Sync",
		"Ageha syncs reading history, favourites and categories with a kotatsu-syncserver you " +
			"run yourself -- the same protocol and the same server the Android app uses, so the " +
			"two stay in step. There is no Ageha-hosted service and no default address: nothing " +
			"leaves this machine until you enter one.",
	)

	if (state.isSignedIn) {
		SettingsRows(
			listOf(
				{
					SettingRow("Account", "${state.email} · ${state.syncUrl}") {
						TextButton(onClick = onSignOut, enabled = !state.isBusy) {
							Text("Sign out")
						}
					}
				},
				{
					SettingRow(
						"Sync when Ageha starts",
						"the moment another device's changes are most likely waiting",
					) {
						AgehaSwitch(
							checked = state.syncOnStart,
							onCheckedChange = onSyncOnStart,
							enabled = !state.isBusy,
						)
					}
				},
				{
					SettingRow(
						"Sync now",
						if (state.isPasswordStored) {
							"password stored on this machine, in ageha's data folder"
						} else {
							"password not stored · ageha will ask when the session expires"
						},
					) {
						Button(onClick = onSyncNow, enabled = !state.isBusy) {
							Text(if (state.isBusy) "Syncing..." else "Sync now")
						}
					}
				},
			),
		)
		Explain(
			"Ageha syncs at startup and when you ask it to. It does not sync on a timer -- a " +
				"desktop app that is open all day would spend the day re-sending your whole " +
				"library, because the protocol is not incremental.",
		)
	} else {
		var url by remember { mutableStateOf(state.syncUrl) }
		var email by remember { mutableStateOf(state.email) }
		var password by remember { mutableStateOf("") }
		var rememberPassword by remember { mutableStateOf(true) }
		OutlinedTextField(
			value = url,
			onValueChange = { url = it },
			label = { Text("Server address") },
			placeholder = { Text("sync.example.org") },
			singleLine = true,
			enabled = !state.isBusy,
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = email,
			onValueChange = { email = it },
			label = { Text("Email") },
			singleLine = true,
			enabled = !state.isBusy,
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = password,
			onValueChange = { password = it },
			label = { Text("Password") },
			singleLine = true,
			enabled = !state.isBusy,
			visualTransformation = PasswordVisualTransformation(),
			modifier = Modifier.fillMaxWidth(),
		)
		Row(verticalAlignment = Alignment.CenterVertically) {
			AgehaSwitch(
				checked = rememberPassword,
				onCheckedChange = { rememberPassword = it },
				enabled = !state.isBusy,
			)
			Text("Remember the password", modifier = Modifier.padding(start = AgehaSpacing.sm))
		}
		Explain(
			if (rememberPassword) {
				"Stored in a file in Ageha's data folder so sync can renew its session on its " +
					"own. Protected by your user account; there is no keychain on desktop that " +
					"Ageha can reach without shipping a native library per platform."
			} else {
				"Nothing is written to disk except the session token. Ageha will ask again when " +
					"it expires."
			},
		)
		Button(
			onClick = { onSignIn(url, email, password, rememberPassword) },
			enabled = !state.isBusy,
		) {
			Text(if (state.isBusy) "Signing in..." else "Sign in")
		}
	}

	state.message?.let { message ->
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
		SelectionContainer {
			Text(
				message,
				style = AgehaTextStyles.metadata,
				color = if (state.isError) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
		}
	}
}
