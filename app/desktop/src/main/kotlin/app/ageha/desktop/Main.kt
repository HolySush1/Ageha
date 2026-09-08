package app.ageha.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.ageha.core.backup.defaultBackupFileName
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.BrandAssets
import app.ageha.core.data.LocalArchive
import app.ageha.core.sync.SyncOutcome
import app.ageha.feature.settings.AppUpdatePolicy
import app.ageha.core.designsystem.ThemeGallery
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Ageha's entry point.
 *
 * `--gallery` opens the design system gallery instead of the app. Kept reachable rather than
 * deleted after milestone 5: a design system nobody can look at is a design system that drifts,
 * and the gallery is how a palette change is reviewed.
 */
fun main(args: Array<String>) {
	if (args.contains("--gallery")) {
		return application {
			Window(
				onCloseRequest = ::exitApplication,
				title = "Ageha - design system",
				icon = BrandAssets.windowIcon(),
			) {
				ThemeGallery(Modifier.fillMaxSize())
			}
		}
	}

	val app = AgehaApplication.start()
	try {
		application { AgehaWindow(app, onExit = ::exitApplication) }
	} finally {
		// Runs on every exit path, including the window being closed by the OS. The source stack
		// holds an OkHttp cache journal and open jar handles; on Windows, leaving them open stops
		// the next launch's parsers update from replacing the build it is updating.
		app.close()
	}
}

// `Flow.debounce` has been marked preview for years and its signature has not moved. The
// alternative is hand-rolling a timer per window-geometry change, which is more code doing the
// same thing less clearly.
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun ApplicationScope.AgehaWindow(app: AgehaApplication, onExit: () -> Unit) {
	val store = app.preferencesStore
	var preferences by remember { mutableStateOf(store.load()) }
	val navigator = remember { Navigator() }
	val searchFocus = remember { FocusRequester() }

	val keyRouter = remember { KeyRouter() }
	var isFullscreen by remember { mutableStateOf(false) }

	val windowState = rememberWindowState(
		size = DpSize(preferences.window.width.dp, preferences.window.height.dp),
		position = preferences.window.x?.let { x ->
			preferences.window.y?.let { y -> WindowPosition(x.dp, y.dp) }
		} ?: WindowPosition.PlatformDefault,
		placement = if (preferences.window.isMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
	)

	// Sync once, at startup, if an account is configured.
	//
	// `Unit` rather than the preference as the key: this is a one-shot at launch, and keying it on
	// `syncOnStart` would fire another sync every time the user toggled the switch in settings.
	// With no account configured the engine returns NotConfigured without touching the network, so
	// the check costs nothing on the installations that will never use this.
	//
	// Only failures are surfaced. A sync that worked is not news, and a notice card on every
	// launch is a notice card nobody reads -- but a sync that has been quietly failing for a
	// fortnight is exactly what a user needs told.
	LaunchedEffect(Unit) {
		if (!preferences.syncOnStart) return@LaunchedEffect
		val outcome = app.syncEngine.sync()
		if (outcome is SyncOutcome.Failed) {
			app.notices.post(
				title = "Sync failed",
				detail = outcome.describe(),
				isError = true,
			)
		}
	}

	// Ageha's own update check, at startup, subject to the policy.
	//
	// Only NOTIFY posts a notice. AUTOMATIC checks and stays quiet -- the installer is what
	// actually applies the update on every platform, so under that policy there is nothing for the
	// user to do and nothing worth interrupting them for. MANUAL does not look at all, and that
	// has to mean *no request*, not a request whose result is hidden: a setting called "never
	// check" that still contacts GitHub would be a lie.
	LaunchedEffect(Unit) {
		if (preferences.appUpdatePolicy == AppUpdatePolicy.MANUAL) return@LaunchedEffect
		val outcome = app.appUpdates.check()
		if (outcome is AppUpdateOutcome.Available && preferences.appUpdatePolicy == AppUpdatePolicy.NOTIFY) {
			app.notices.post("Ageha ${outcome.version} is available", outcome.describe())
		}
	}

	// Fullscreen is a window placement, not a preference: it is a mode you are in right now, and
	// restoring an app fullscreen because it was fullscreen last week is startling.
	LaunchedEffect(isFullscreen) {
		windowState.placement = when {
			isFullscreen -> WindowPlacement.Fullscreen
			preferences.window.isMaximized -> WindowPlacement.Maximized
			else -> WindowPlacement.Floating
		}
	}

	// Geometry is saved as it changes rather than only on close, because a crash or a forced
	// shutdown should not cost the user their window layout. Debounced so a drag across the
	// screen is one write and not four hundred.
	LaunchedEffect(windowState) {
		snapshotFlow {
			WindowGeometry(
				width = windowState.size.width.value.toInt(),
				height = windowState.size.height.value.toInt(),
				x = windowState.position.takeIf { it.isSpecified }?.x?.value?.toInt(),
				y = windowState.position.takeIf { it.isSpecified }?.y?.value?.toInt(),
				// Fullscreen is transient, so it is never what gets saved -- a window remembered as
				// fullscreen has no size to come back to.
				isMaximized = windowState.placement == WindowPlacement.Maximized,
			)
		}
			.distinctUntilChanged()
			.debounce(WINDOW_SAVE_DEBOUNCE_MS)
			.collect { geometry ->
				preferences = preferences.copy(window = geometry)
				store.save(preferences)
			}
	}

	Window(
		onCloseRequest = onExit,
		state = windowState,
		title = "Ageha",
		icon = BrandAssets.windowIcon(),
		// Ageha draws its own caption. See TitleBar.kt for what that buys and what it costs --
		// including the one thing it cannot give back, which is edge-drag Aero Snap.
		undecorated = true,
		onPreviewKeyEvent = { event ->
			if (event.type != KeyEventType.KeyDown) {
				false
			} else if (keyRouter.dispatch(event)) {
				// The current screen claimed it. The reader does this while it is open so its
				// arrow keys can follow the reading direction.
				true
			} else {
				when {
					event.isCtrlPressed && event.key == Key.One -> {
						navigator.switchTo(Section.LIBRARY); true
					}
					event.isCtrlPressed && event.key == Key.Two -> {
						navigator.switchTo(Section.CONTINUE); true
					}
					event.isCtrlPressed && event.key == Key.Three -> {
						navigator.switchTo(Section.EXPLORE); true
					}
					event.isCtrlPressed && event.key == Key.Four -> {
						navigator.switchTo(Section.DOWNLOADS); true
					}
					// Ctrl+comma opens preferences on every desktop platform worth matching.
					event.isCtrlPressed && event.key == Key.Comma -> {
						navigator.switchTo(Section.SETTINGS); true
					}
					// Ctrl+K opens the full-page search: the keyboard half of the magnifier in the
					// navigation pill, and the shortcut the `CTRL K` cap beside Explore's search
					// field promises.
					//
					// It used to toggle a popover panel. That panel showed three library rows and
					// two source results in total, which is a fine shape for a command palette
					// jumping to a known destination and the wrong one for searching 1360 sites --
					// the answer you wanted was usually the sixth row. Same key, same intent, no
					// cap.
					event.isCtrlPressed && event.key == Key.K -> {
						navigator.openGlobalSearch()
						runCatching { searchFocus.requestFocus() }
						true
					}
					// Ctrl+Shift+F searches every enabled source, from wherever you are. Tested
					// *before* plain Ctrl+F, because a shifted event reports Ctrl as pressed too
					// and the unshifted branch would otherwise swallow it.
					event.isCtrlPressed && event.isShiftPressed && event.key == Key.F -> {
						navigator.openGlobalSearch()
						runCatching { searchFocus.requestFocus() }
						true
					}
					// Ctrl+F focuses whichever search field the current screen owns. Every screen
					// has exactly one, so there is no ambiguity about which.
					event.isCtrlPressed && event.key == Key.F -> {
						runCatching { searchFocus.requestFocus() }; true
					}
					// Ctrl+O opens a comic archive. Bound here rather than advertised by a menu
					// that no longer exists; the visible way in is Settings > Library.
					event.isCtrlPressed && event.key == Key.O -> {
						openLocalArchive(app, navigator); true
					}
					// Escape goes back, and is *not* consumed at the root -- otherwise it would
					// swallow the key that dismisses a dropdown or a dialog.
					event.key == Key.Escape -> navigator.back()
					event.isCtrlPressed && event.key == Key.W -> { onExit(); true }
					event.isCtrlPressed && event.key == Key.Q -> { onExit(); true }
					else -> false
				}
			}
		},
	) {
		// No `MenuBar`. Ageha draws its own caption now, and Compose's menu bar is a Swing
		// `JMenuBar` inside the frame -- under a custom title bar it renders as a native grey
		// strip belonging to a different application.
		//
		// Nothing it carried was lost. Navigation and the theme picker were duplicates of the
		// nav pill, the skin switcher and Settings > Appearance. Import and export already lived
		// in Settings > Library and backup. "Open comic archive" lived *only* here, so it moved
		// there too -- and every shortcut the menu advertised is still bound below, because they
		// were always handled by `onPreviewKeyEvent` rather than by the menu.
		AgehaTheme(
			mode = preferences.theme,
			systemInDarkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
		) {
			Box(Modifier.fillMaxSize()) {
				Column(Modifier.fillMaxSize()) {
					// The bar stays put while reading, and that is deliberate. The handoff calls
					// the reader a layer "above everything", but it is describing a browser
					// prototype with no window to manage: hiding this strip on a desktop takes
					// minimise and close away from someone halfway through a chapter, to hide
					// 38px they are not looking at. True immersion is fullscreen, which the
					// reader already has a key for.
					// Ageha's own drag area rather than Compose's, so that dragging a maximised
					// window restores it under the cursor the way Windows does. See WindowResize.kt.
					WindowDragArea(
						window = window,
						isMaximized = windowState.placement == WindowPlacement.Maximized,
						onRestore = { windowState.placement = WindowPlacement.Floating },
					) {
						AgehaTitleBar(
							context = windowContextLine(app, navigator),
							theme = preferences.theme,
							onTheme = { mode ->
								preferences = preferences.copy(theme = mode)
								store.save(preferences)
							},
							onMinimize = { windowState.isMinimized = true },
							onToggleMaximize = {
								// Reads the *placement* rather than the saved preference, so the
								// button always reverses whatever the window is doing now --
								// including after a Win+arrow snap this app never saw.
								windowState.placement =
									if (windowState.placement == WindowPlacement.Maximized) {
										WindowPlacement.Floating
									} else {
										WindowPlacement.Maximized
									}
							},
							onClose = onExit,
							// So the middle button shows a restore mark, and says "Restore", once
							// the window already fills the screen.
							isMaximized = windowState.placement == WindowPlacement.Maximized,
						)
					}
					AgehaShell(
						application = app,
						navigator = navigator,
						searchFocus = searchFocus,
						modifier = Modifier.fillMaxSize(),
						preferences = preferences,
						onPreferencesChange = { updated ->
							preferences = updated
							store.save(updated)
						},
						keyRouter = keyRouter,
						onToggleFullscreen = { isFullscreen = !isFullscreen },
						onImportBackup = { importBackup(app, navigator) },
						onExportBackup = { exportBackup(app) },
						onOpenArchive = { openLocalArchive(app, navigator) },
					)
				}
				// Last, so the edges sit above the content that would otherwise swallow the drag.
				// Off while maximised or fullscreen: resizing a window that has no free edges is
				// meaningless, and leaving the handles live there lets a stray drag pull a
				// maximised window into a strange half-state.
				WindowResizeHandles(
					window = window,
					enabled = windowState.placement == WindowPlacement.Floating,
				)
			}
		}
	}
}

/** Long enough that dragging a window is one write, short enough to survive a crash. */
private const val WINDOW_SAVE_DEBOUNCE_MS = 400L

/**
 * Pick a backup and restore it.
 *
 * The picker runs on the calling thread because `FileDialog` is modal and must be -- the user is
 * choosing a file and nothing else should proceed. The import itself is launched onto the
 * application scope, since restoring a large library takes long enough to block the UI thread
 * noticeably.
 */
private fun importBackup(app: AgehaApplication, navigator: Navigator) {
	val file = FilePicker.openFile("Import an Android backup", setOf("zip", "bk")) ?: return
	app.scope.launch {
		runCatching { app.backupImporter.import(file) }
			.onSuccess { result ->
				// The importer's own account, in full. It names what was restored, what it does
				// not support yet, what a newer Android app wrote that it did not recognise, and
				// every row it dropped with the reason -- so a partial import is legible rather
				// than looking like success.
				app.notices.post("Imported ${file.name}", result.describe())
				navigator.switchTo(Section.LIBRARY)
			}
			.onFailure { failure ->
				app.notices.post(
					title = "Could not import ${file.name}",
					detail = failure.message,
					isError = true,
				)
			}
	}
}

/**
 * Pick a destination and write a backup.
 *
 * The counterpart of [importBackup], and it takes the same shape for the same reasons: the picker
 * is modal on the calling thread, the work goes to the application scope. Nothing navigates
 * afterwards -- an export leaves the user exactly where they were, and yanking them to another
 * screen to prove a file was written would be worse than the notice that says so.
 */
private fun exportBackup(app: AgehaApplication) {
	val file = FilePicker.saveFile("Export a backup", defaultBackupFileName()) ?: return
	app.scope.launch {
		runCatching { app.backupExporter.export(file) }
			.onSuccess { result ->
				// Per-section counts, not "done". Someone exporting a library they cannot afford
				// to lose is entitled to see that the favourites count matches what they have.
				app.notices.post("Backup saved", result.describe())
			}
			.onFailure { failure ->
				app.notices.post(
					title = "Could not export to " + file.name,
					detail = failure.message,
					isError = true,
				)
			}
	}
}

/**
 * Open a CBZ from disk, straight into the reader.
 *
 * A local file is not added to the library and does not become a source -- it is opened, read and
 * closed, which is what someone double-clicking a file wants. Reading position still persists,
 * because the archive's id is derived from its absolute path and is stable across runs.
 */
private fun openLocalArchive(app: AgehaApplication, navigator: Navigator) {
	val file = FilePicker.openFile("Open a comic archive", setOf("cbz", "zip")) ?: return
	val reason = LocalArchive.unsupportedReason(file)
	if (reason != null) {
		// Named rather than swallowed. Someone whose file is a .cbr needs to be told it is a RAR
		// and that repackaging as CBZ works -- "could not open archive" sends them looking for a
		// bug in Ageha instead.
		app.notices.post("Cannot open ${file.name}", reason, isError = true)
		return
	}
	val (manga, chapter) = app.reader.localManga(file)
	navigator.read(manga, chapter)
}
