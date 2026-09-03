package app.ageha.desktop

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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.BrandAssets
import app.ageha.core.data.LocalArchive
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
					// Ctrl+F focuses whichever search field the current screen owns. Every screen
					// has exactly one, so there is no ambiguity about which.
					event.isCtrlPressed && event.key == Key.F -> {
						runCatching { searchFocus.requestFocus() }; true
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
		MenuBar {
			Menu("File", mnemonic = 'F') {
				Item("Open comic archive...", shortcut = androidx.compose.ui.input.key.KeyShortcut(Key.O, ctrl = true)) {
					openLocalArchive(app, navigator)
				}
				Separator()
				Item("Import Android backup...") { importBackup(app, navigator) }
				Separator()
				Item("Quit", shortcut = androidx.compose.ui.input.key.KeyShortcut(Key.Q, ctrl = true), onClick = onExit)
			}
			Menu("View", mnemonic = 'V') {
				Item("Library", shortcut = androidx.compose.ui.input.key.KeyShortcut(Key.One, ctrl = true)) {
					navigator.switchTo(Section.LIBRARY)
				}
				Item("Continue reading", shortcut = androidx.compose.ui.input.key.KeyShortcut(Key.Two, ctrl = true)) {
					navigator.switchTo(Section.CONTINUE)
				}
				Item("Explore", shortcut = androidx.compose.ui.input.key.KeyShortcut(Key.Three, ctrl = true)) {
					navigator.switchTo(Section.EXPLORE)
				}
				Item("Downloads", shortcut = androidx.compose.ui.input.key.KeyShortcut(Key.Four, ctrl = true)) {
					navigator.switchTo(Section.DOWNLOADS)
				}
				Item("Settings", shortcut = androidx.compose.ui.input.key.KeyShortcut(Key.Comma, ctrl = true)) {
					navigator.switchTo(Section.SETTINGS)
				}
				Separator()
				for (mode in AgehaThemeMode.entries) {
					RadioButtonItem(
						text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
						selected = preferences.theme == mode,
						onClick = {
							preferences = preferences.copy(theme = mode)
							store.save(preferences)
						},
					)
				}
			}
		}
		AgehaTheme(
			mode = preferences.theme,
			systemInDarkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
		) {
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
			)
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
