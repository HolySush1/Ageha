package app.ageha.desktop

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.ageha.core.data.CatalogRepository
import app.ageha.core.data.LibraryRepository
import app.ageha.core.data.SourceRepository
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.BrandAssets
import app.ageha.feature.explore.BrowseScreen
import app.ageha.feature.explore.BrowseViewModel
import app.ageha.feature.explore.DetailsScreen
import app.ageha.feature.explore.DetailsViewModel
import app.ageha.feature.explore.ExploreViewModel
import app.ageha.feature.explore.SourcePickerScreen
import app.ageha.feature.library.LibraryScreen
import app.ageha.feature.library.LibraryViewModel
import kotlinx.coroutines.CoroutineScope

/**
 * The application shell: a navigation rail, and whichever screen is current.
 *
 * A rail down the left rather than a bar across the bottom. This is a desktop window -- vertical
 * space is the scarce axis when the content is a grid of tall covers, and the pointer reaches the
 * left edge as easily as anywhere. Every rail item also names its keyboard shortcut, because on a
 * desktop the rail is the discoverable path and the shortcut is the one people actually use.
 */
@Composable
fun AgehaShell(
	application: AgehaApplication,
	navigator: Navigator,
	searchFocus: FocusRequester,
	modifier: Modifier = Modifier,
) {
	val scope = application.scope
	val libraryViewModel = remember { LibraryViewModel(application.library, application.catalog, scope) }
	val exploreViewModel = remember { ExploreViewModel(application.sources, scope) }
	val browseViewModel = remember { BrowseViewModel(application.catalog, application.sources, scope) }
	val detailsViewModel = remember {
		DetailsViewModel(application.catalog, application.library, scope)
	}

	Row(modifier.fillMaxSize()) {
		NavigationRail(navigator)
		Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
			Column(Modifier.fillMaxSize()) {
				when (val destination = navigator.current) {
					Destination.Library -> {
						val state by libraryViewModel.state.collectAsState()
						val headers by libraryViewModel.imageHeaders.collectAsState()
						LibraryScreen(
							state = state,
							imageHeaders = headers,
							onOpenManga = navigator::openManga,
							onSelectCategory = libraryViewModel::selectCategory,
							onSearch = libraryViewModel::search,
							onSort = libraryViewModel::setSort,
							onNeedHeaders = libraryViewModel::ensureHeaders,
							onBrowseSources = { navigator.switchTo(Section.EXPLORE) },
							searchFocus = searchFocus,
						)
					}

					Destination.Sources -> {
						val state by exploreViewModel.state.collectAsState()
						SourcePickerScreen(
							state = state,
							onOpenSource = navigator::openSource,
							onSearch = exploreViewModel::search,
							onFilter = exploreViewModel::setFilter,
							onLocale = exploreViewModel::setLocale,
							onSetEnabled = exploreViewModel::setEnabled,
							searchFocus = searchFocus,
						)
					}

					is Destination.Browse -> {
						// Keyed on the source name so switching sources restarts the listing
						// rather than appending one source's results to another's.
						LaunchedEffect(destination.sourceName) { browseViewModel.open(destination.sourceName) }
						val state by browseViewModel.state.collectAsState()
						Column(Modifier.fillMaxSize()) {
							BreadcrumbBar(navigator, state.sourceTitle)
							BrowseScreen(
								state = state,
								onOpenManga = navigator::openManga,
								onSort = browseViewModel::setSort,
								onSearch = browseViewModel::search,
								onSubmitSearch = browseViewModel::submitSearch,
								onLoadMore = { browseViewModel.loadMore() },
								onRetry = browseViewModel::retry,
								searchFocus = searchFocus,
							)
						}
					}

					is Destination.Details -> {
						LaunchedEffect(destination.manga.id, destination.manga.sourceName) {
							detailsViewModel.open(destination.manga)
						}
						val state by detailsViewModel.state.collectAsState()
						Column(Modifier.fillMaxSize()) {
							BreadcrumbBar(navigator, state.manga?.title ?: destination.manga.title)
							DetailsScreen(
								state = state,
								// The reader is milestone 7. Until it exists, opening a chapter
								// does nothing rather than pretending to -- see the note in
								// docs/ARCHITECTURE.md 7.
								onOpenChapter = {},
								onToggleCategory = detailsViewModel::toggleCategory,
								onAddToLibrary = detailsViewModel::addToDefaultCategory,
								onRemoveFromLibrary = detailsViewModel::removeFromLibrary,
								onSelectBranch = detailsViewModel::selectBranch,
								onRetry = detailsViewModel::retry,
							)
						}
					}
				}
			}
		}
	}
}

/**
 * The navigation rail.
 *
 * The seal sits at the top as the app's mark. This is one of the places brand colour belongs --
 * navigation and library chrome, never the reader.
 */
@Composable
private fun NavigationRail(navigator: Navigator) {
	Column(
		Modifier
			.width(84.dp)
			.fillMaxHeight()
			.background(MaterialTheme.colorScheme.surfaceContainer)
			.padding(vertical = AgehaSpacing.md),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(AgehaSpacing.xs),
	) {
		androidx.compose.foundation.Image(
			painter = BrandAssets.painter("icon-64.png"),
			contentDescription = "Ageha",
			modifier = Modifier.size(32.dp).padding(bottom = AgehaSpacing.xs),
		)
		Spacer(Modifier.height(AgehaSpacing.sm))
		for (section in Section.entries) {
			RailItem(
				label = section.label,
				hint = section.shortcutHint,
				isSelected = navigator.section == section,
				onClick = { navigator.switchTo(section) },
			)
		}
	}
}

@Composable
private fun RailItem(label: String, hint: String, isSelected: Boolean, onClick: () -> Unit) {
	Column(
		Modifier
			.width(72.dp)
			.clip(MaterialTheme.shapes.small)
			.background(
				if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
			)
			.clickable(onClick = onClick)
			.padding(vertical = AgehaSpacing.sm),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text(
			label,
			style = MaterialTheme.typography.labelLarge,
			color = if (isSelected) {
				MaterialTheme.colorScheme.onSecondaryContainer
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
		)
		Text(hint, style = AgehaTextStyles.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

/**
 * Where you are, and the way back.
 *
 * Escape and Alt+Left both go back, but a window with no visible affordance for it is a window
 * that only power users can navigate. The crumb is clickable for the same reason.
 */
@Composable
private fun BreadcrumbBar(navigator: Navigator, title: String) {
	Row(
		Modifier
			.fillMaxWidth()
			.padding(horizontal = AgehaSpacing.md, vertical = AgehaSpacing.sm),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.sm),
	) {
		if (navigator.canGoBack) {
			Text(
				"< Back",
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.primary,
				modifier = Modifier
					.clip(MaterialTheme.shapes.small)
					.clickable { navigator.back() }
					.padding(horizontal = AgehaSpacing.sm, vertical = AgehaSpacing.xs),
			)
		}
		Text(
			title,
			style = MaterialTheme.typography.titleSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
		)
	}
}

/** Kept so the shell can be constructed in tests without a Koin graph. */
internal fun buildViewModels(
	library: LibraryRepository,
	sources: SourceRepository,
	catalog: CatalogRepository,
	scope: CoroutineScope,
) = Triple(
	LibraryViewModel(library, catalog, scope),
	ExploreViewModel(sources, scope),
	BrowseViewModel(catalog, sources, scope),
)
