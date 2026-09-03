package app.ageha.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.ageha.core.designsystem.BrandAssets
import app.ageha.core.designsystem.ThemeGallery

/**
 * Milestone 5's entry point: the theme gallery.
 *
 * This is the review artefact the brief gates on -- "reviewed before any real screen is built" --
 * not a placeholder. Milestone 6 replaces the window's content with the application shell and
 * keeps the gallery reachable behind a developer flag, because a design system nobody can look at
 * is a design system that drifts.
 */
fun main() = application {
	Window(
		onCloseRequest = ::exitApplication,
		title = "Ageha - design system",
		icon = BrandAssets.windowIcon(),
		state = rememberWindowState(width = 1240.dp, height = 900.dp),
	) {
		ThemeGallery(Modifier.fillMaxSize())
	}
}
