package app.ageha.desktop

import androidx.compose.foundation.background
import app.ageha.core.designsystem.glassSurface
import app.ageha.core.designsystem.GlassTone
import app.ageha.core.designsystem.GlassBar
import app.ageha.core.designsystem.AgehaGlass
import app.ageha.core.designsystem.AgehaBackdrop
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.ageha.core.data.CatalogRepository
import app.ageha.core.data.HistoryRepository
import app.ageha.core.data.LibraryRepository
import app.ageha.core.data.SourceRepository
import app.ageha.core.model.AgehaVersion
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.BrandAssets
import app.ageha.feature.explore.BrowseScreen
import app.ageha.feature.explore.BrowseViewModel
import app.ageha.feature.explore.DetailsScreen
import app.ageha.feature.explore.DetailsViewModel
import app.ageha.feature.explore.ExploreViewModel
import app.ageha.feature.downloads.DownloadQueue
import app.ageha.feature.downloads.DownloadsScreen
import app.ageha.feature.explore.SourcePickerScreen
import app.ageha.core.data.ResumePoint
import app.ageha.feature.explore.GlobalSearchScreen
import app.ageha.feature.explore.GlobalSearchViewModel
import app.ageha.feature.library.ContinueScreen
import app.ageha.feature.library.ContinueViewModel
import app.ageha.feature.library.LibraryScreen
import app.ageha.core.parsers.Ageha
import app.ageha.feature.library.LibraryViewModel
import app.ageha.feature.settings.ParsersViewModel
import app.ageha.feature.settings.SettingsScreen
import app.ageha.feature.settings.AppUpdatesUiState
import app.ageha.feature.settings.SettingsSection
import app.ageha.feature.settings.SyncViewModel
import app.ageha.feature.reader.ReaderActions
import app.ageha.feature.reader.ReaderKeys
import app.ageha.feature.reader.ReaderScreen
import app.ageha.feature.reader.ReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
	preferences: Preferences = Preferences(),
	onPreferencesChange: (Preferences) -> Unit = {},
	keyRouter: KeyRouter = remember { KeyRouter() },
	onToggleFullscreen: () -> Unit = {},
	onImportBackup: () -> Unit = {},
	onExportBackup: () -> Unit = {},
	/** Which settings panel opens first. Used by the headless render; see SettingsScreen. */
	initialSettingsSection: SettingsSection = SettingsSection.APPEARANCE,
) {
	val scope = application.scope
	val libraryViewModel = remember {
		LibraryViewModel(application.library, application.catalog, application.history, scope)
	}
	val continueViewModel = remember {
		ContinueViewModel(application.history, application.catalog, scope)
	}
	val globalSearchViewModel = remember {
		GlobalSearchViewModel(application.catalog, application.sources, scope)
	}
	// Seeded from preferences and reporting back into them. The filters are a setting, not screen
	// state: hiding adult sources again on every launch would be a switch that does not stay set.
	val exploreViewModel = remember {
		ExploreViewModel(
			sources = application.sources,
			scope = scope,
			hideBroken = preferences.hideBrokenSources,
			showAdult = preferences.showAdultSources,
			locale = preferences.sourceLanguage,
			onFiltersChanged = { hideBroken, showAdult, locale ->
				onPreferencesChange(
					preferences.copy(
						hideBrokenSources = hideBroken,
						showAdultSources = showAdult,
						sourceLanguage = locale,
					),
				)
			},
		)
	}
	val browseViewModel = remember { BrowseViewModel(application.catalog, application.sources, scope) }
	val detailsViewModel = remember {
		DetailsViewModel(application.catalog, application.library, scope)
	}
	val readerViewModel = remember { ReaderViewModel(application.reader, scope) }
	val downloadQueue = remember { DownloadQueue(application.downloader, scope) }
	val parsersViewModel = remember {
		ParsersViewModel(
			installation = application.sourceStack.installation,
			updates = application.parsersUpdates,
			bundledVersion = Ageha.BUNDLED_PARSERS_VERSION,
			activeVersion = application.sourceStack.parsersVersion,
			sourceCount = application.sources.allDescriptors().size,
			scope = scope,
		)
	}

	val syncViewModel = remember {
		SyncViewModel(
			api = application.syncApi,
			engine = application.syncEngine,
			accounts = application.syncAccounts,
			scope = scope,
			syncOnStart = preferences.syncOnStart,
			onSyncOnStartChanged = { onPreferencesChange(preferences.copy(syncOnStart = it)) },
		)
	}

	// Ageha's own update check. State lives here rather than in a view model because there is one
	// action and one string of result -- a class for it would be ceremony.
	var appUpdateChecking by remember { mutableStateOf(false) }
	var appUpdateResult by remember { mutableStateOf<String?>(null) }

	val notices by application.notices.notices.collectAsState()

	// Where a Continue Reading entry resolved to.
	//
	// Handled here rather than inside either screen because both the shelf and the Continue screen
	// produce these, and all three outcomes are *navigation* -- which is the shell's job. Working
	// out the destination is a suspending database read, so it arrives as state rather than as a
	// return value from the click.
	val pendingResume by continueViewModel.resume.collectAsState()
	LaunchedEffect(pendingResume) {
		when (val point = pendingResume) {
			null -> Unit

			is ResumePoint.Open -> {
				if (point.isCaughtUp) {
					// Said out loud, because reopening the final page of the final chapter looks
					// identical to the app having lost the last page turn.
					application.notices.post(
						"Caught up on ${point.manga.title}",
						"You have read everything Ageha knows about here. Reopening the last page " +
							"you were on -- check the source for new chapters.",
					)
				}
				navigator.read(point.manga, point.chapter, point.page)
				continueViewModel.consumeResume()
			}

			// Known manga, unknown chapters -- history restored from an Android backup carries no
			// chapter rows. The details screen is where a source gets asked for them.
			is ResumePoint.NeedsChapters -> {
				navigator.openManga(point.manga)
				continueViewModel.consumeResume()
			}

			// The source is not in this parsers build. Not an error and not a dead end: the same
			// title, searched across the sources that *are* loaded.
			is ResumePoint.SourceUnavailable -> {
				navigator.searchAllSources(point.title, subject = point.title)
				continueViewModel.consumeResume()
			}
		}
	}

	// What the backdrop shows. The user's own library rather than a stock image: whatever they
	// were last reading, blurred and scrimmed behind the interface. Null on a fresh installation,
	// where AgehaBackdrop falls back to the brand gradient rather than to a grey void.
	val recent by continueViewModel.state.collectAsState()
	val recentHeaders by continueViewModel.imageHeaders.collectAsState()
	val newest = recent.entries.firstOrNull()
	val backdropCover = newest?.manga?.coverUrl
	// Headers are per source -- a cover URL fetched without its source's Referer is a 403, and a
	// 403 here is a backdrop that silently falls back to the gradient.
	val backdropHeaders = newest?.let { recentHeaders[it.manga.sourceName] }.orEmpty()

	// Boxed so notices can float over whatever screen is current. They are application-level --
	// a backup import's report outlives the screen that started it -- so they are anchored to the
	// window rather than owned by a screen.
	Box(modifier.fillMaxSize()) {
		// The reader takes the whole window. Chrome around a page is chrome over somebody's manga,
		// and both the navigation and the backdrop are the app talking about itself while they are
		// trying to read -- cover art behind a page would be the tinted-wash mistake rule 8 exists
		// to prevent, one layer further back. One check suppresses both.
		if (!navigator.isImmersive) {
			AgehaBackdrop(
				coverUrl = backdropCover,
				imageHeaders = backdropHeaders,
				modifier = Modifier.fillMaxSize(),
			) {}
		}
		Column(
			Modifier
				.fillMaxSize()
				// The navigation floats over the content rather than beside it, so the content has
				// to be told to start below it. Without this the first row of every screen sits
				// under the pill, which looks like a layout bug rather than like a layer.
				.padding(top = if (navigator.isImmersive) 0.dp else NAV_CLEARANCE),
		) {
			when (val destination = navigator.current) {
				Destination.Library -> {
					val state by libraryViewModel.state.collectAsState()
					val headers by libraryViewModel.imageHeaders.collectAsState()
					LibraryScreen(
						state = state,
						imageHeaders = headers,
						onOpenManga = navigator::openManga,
						onContinue = { continueViewModel.open(it.mangaId) },
						onSeeAllContinue = navigator::openContinue,
						onSelectCategory = libraryViewModel::selectCategory,
						onSearch = libraryViewModel::search,
						onSort = libraryViewModel::setSort,
						onNeedHeaders = libraryViewModel::ensureHeaders,
						onBrowseSources = { navigator.switchTo(Section.EXPLORE) },
						searchFocus = searchFocus,
					)
				}

				Destination.Continue -> {
					val state by continueViewModel.state.collectAsState()
					val headers by continueViewModel.imageHeaders.collectAsState()
					ContinueScreen(
						state = state,
						imageHeaders = headers,
						onOpen = { continueViewModel.open(it.mangaId) },
						onSearch = continueViewModel::search,
						onRemove = continueViewModel::remove,
						onFindElsewhere = { entry ->
							navigator.searchAllSources(entry.manga.title, subject = entry.manga.title)
						},
						onNeedHeaders = continueViewModel::ensureHeaders,
						onBrowseSources = { navigator.switchTo(Section.EXPLORE) },
						searchFocus = searchFocus,
					)
				}

				is Destination.SearchAll -> {
					LaunchedEffect(destination.query, destination.subject) {
						globalSearchViewModel.search(destination.query, destination.subject)
					}
					val state by globalSearchViewModel.state.collectAsState()
					val headers by libraryViewModel.imageHeaders.collectAsState()
					Column(Modifier.fillMaxSize()) {
						BreadcrumbBar(navigator, "Search all sources")
						GlobalSearchScreen(
							state = state,
							imageHeaders = headers,
							onQuery = globalSearchViewModel::setQuery,
							onSubmit = { globalSearchViewModel.search(state.query, state.subject) },
							onOpenManga = navigator::openManga,
							onOpenSource = navigator::openSource,
							onNeedHeaders = libraryViewModel::ensureHeaders,
							searchFocus = searchFocus,
						)
					}
				}

				Destination.Downloads -> {
					val queued by downloadQueue.jobs.collectAsState()
					DownloadsScreen(
						jobs = queued,
						onCancel = downloadQueue::cancel,
						onRetry = downloadQueue::retry,
						onCancelAll = downloadQueue::cancelAll,
						onClearFinished = downloadQueue::clearFinished,
					)
				}

				Destination.Settings -> {
					val parsersState by parsersViewModel.state.collectAsState()
					val syncState by syncViewModel.state.collectAsState()
					// The count comes from the same live list Continue Reading draws, so the
					// confirm button cannot offer to clear a number that is no longer true.
					val continueState by continueViewModel.state.collectAsState()
					SettingsScreen(
						theme = preferences.theme,
						readerBackground = preferences.readerBackground,
						doublePage = preferences.doublePage,
						coverOffset = preferences.coverOffset,
						parsers = parsersState,
						jsRuntime = application.jsRuntime,
						parsersDescription = parsersViewModel::describe,
						onTheme = { onPreferencesChange(preferences.copy(theme = it)) },
						onReaderBackground = { onPreferencesChange(preferences.copy(readerBackground = it)) },
						onDoublePage = { onPreferencesChange(preferences.copy(doublePage = it)) },
						onCoverOffset = { onPreferencesChange(preferences.copy(coverOffset = it)) },
						onUpdatePolicy = parsersViewModel::setPolicy,
						onCheckForUpdate = parsersViewModel::checkForUpdate,
						onRollBack = parsersViewModel::rollBack,
						onPin = parsersViewModel::pin,
						onImportBackup = onImportBackup,
						onExportBackup = onExportBackup,
						appUpdates = AppUpdatesUiState(
							policy = preferences.appUpdatePolicy,
							currentVersion = AgehaVersion.NAME,
							isChecking = appUpdateChecking,
							lastResult = appUpdateResult,
						),
						onAppUpdatePolicy = { onPreferencesChange(preferences.copy(appUpdatePolicy = it)) },
						onCheckForAppUpdate = {
							appUpdateChecking = true
							appUpdateResult = null
							scope.launch {
								appUpdateResult = application.appUpdates.check().describe()
								appUpdateChecking = false
							}
						},
						sync = syncState,
						onSignIn = syncViewModel::signIn,
						onSignOut = syncViewModel::signOut,
						onSyncNow = syncViewModel::syncNow,
						onSyncOnStart = syncViewModel::setSyncOnStart,
						initialSection = initialSettingsSection,
						onClearHistory = {
							scope.launch {
								val cleared = application.history.clearAll()
								application.notices.post(
									"Reading history cleared",
									"$cleared entries removed. Your library, favourites and " +
										"downloads are untouched.",
								)
							}
						},
						historyCount = continueState.totalCount,
						hideBrokenSources = preferences.hideBrokenSources,
						showAdultSources = preferences.showAdultSources,
						onHideBrokenSources = {
							exploreViewModel.setHideBroken(it)
							onPreferencesChange(preferences.copy(hideBrokenSources = it))
						},
						onShowAdultSources = {
							exploreViewModel.setShowAdult(it)
							onPreferencesChange(preferences.copy(showAdultSources = it))
						},
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
						onHideBroken = exploreViewModel::setHideBroken,
						onShowAdult = exploreViewModel::setShowAdult,
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

				is Destination.Read -> {
					LaunchedEffect(destination.chapter.id, destination.startPage) {
						readerViewModel.open(
							destination.manga,
							destination.chapter,
							destination.startPage,
						)
					}
					val state by readerViewModel.state.collectAsState()
					// The reader owns the keyboard while it is open. Registering the handler
					// here rather than in the window means the bindings live with the screen
					// that defines them, and unregister themselves when it goes away.
					androidx.compose.runtime.DisposableEffect(state.mode, state.pageCount) {
						keyRouter.install { event ->
							ReaderKeys.handle(
								event = event,
								mode = state.mode,
								pageCount = state.pageCount,
								actions = object : ReaderActions {
									override fun nextPage() = readerViewModel.nextPage()
									override fun previousPage() = readerViewModel.previousPage()
									override fun nextChapter() = readerViewModel.nextChapter()
									override fun previousChapter() = readerViewModel.previousChapter()
									override fun goToPage(index: Int) = readerViewModel.goToPage(index)
									override fun setScale(scale: app.ageha.core.model.PageScale) =
										readerViewModel.setScale(scale)
									override fun toggleChrome() = readerViewModel.toggleChrome()
									override fun toggleFullscreen() = onToggleFullscreen()
									override fun close() {
										navigator.back()
									}
								},
							)
						}
						onDispose { keyRouter.clear() }
					}
					// Flush the position when the reader goes away. The debounce that keeps
					// page turns from being one write each would otherwise lose the last one.
					androidx.compose.runtime.DisposableEffect(destination.chapter.id) {
						onDispose { readerViewModel.savePositionNow() }
					}
					ReaderScreen(
						state = state,
						background = preferences.readerBackground,
						doublePage = preferences.doublePage,
						coverOffset = preferences.coverOffset,
						onPageChange = readerViewModel::goToPage,
						onScroll = readerViewModel::recordScroll,
						onNextPage = readerViewModel::nextPage,
						onPreviousPage = readerViewModel::previousPage,
						onSetMode = readerViewModel::setMode,
						onSetScale = readerViewModel::setScale,
						onSetBackground = { onPreferencesChange(preferences.copy(readerBackground = it)) },
						onToggleDoublePage = {
							onPreferencesChange(preferences.copy(doublePage = !preferences.doublePage))
						},
						onToggleCoverOffset = {
							onPreferencesChange(preferences.copy(coverOffset = !preferences.coverOffset))
						},
						onToggleChrome = readerViewModel::toggleChrome,
						onRetry = readerViewModel::retry,
						onClose = { navigator.back() },
						webtoonZoom = preferences.webtoonZoom,
						onSetWebtoonZoom = {
							onPreferencesChange(preferences.copy(webtoonZoom = it))
						},
						// Auto-hide moved into the reader, because the signals it needs --
						// pointer movement, and the pointer being over the bar itself --
						// only exist down there. Driving it from here meant the timer knew
						// about page turns and nothing else.
						onHideChrome = { readerViewModel.setChromeVisible(false) },
					)
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
							onOpenChapter = { chapter ->
								state.manga?.let { navigator.read(it, chapter) }
							},
							onDownloadChapter = { chapter ->
								state.manga?.let { downloadQueue.enqueue(it, listOf(chapter)) }
							},
							onDownloadAll = {
								state.manga?.let { downloadQueue.enqueue(it, state.chapters) }
							},
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

		if (!navigator.isImmersive) {
			FloatingNav(navigator, Modifier.align(Alignment.TopCenter))
		}

		NoticeOverlay(
			notices = notices,
			onDismiss = application.notices::dismiss,
			modifier = Modifier.align(Alignment.BottomEnd),
		)
	}
}

/**
 * The floating navigation.
 *
 * A pill over the backdrop rather than a rail beside it. The rail spent 84dp of a desktop window's
 * width permanently, on five words that never change, in an app whose signature view is a grid of
 * covers that wants every pixel of that width. Floating it costs nothing horizontally and puts the
 * seal and the sections in the place the eye already goes first.
 *
 * The rail could afford to print each section's keyboard shortcut under its label. A pill cannot,
 * and deleting them would quietly remove the only place the shortcuts were discoverable -- so they
 * move into tooltips instead. On a desktop the rail is the discoverable path and the shortcut is
 * the one people actually use, which makes losing them worse than losing the rail.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloatingNav(navigator: Navigator, modifier: Modifier = Modifier) {
	Row(
		modifier
			.padding(top = AgehaSpacing.md)
			.glassSurface(AgehaGlass.PillShape, GlassTone.CHROME)
			.padding(horizontal = AgehaSpacing.sm, vertical = AgehaSpacing.xs),
		horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xxs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// One of the places brand colour belongs -- navigation and library chrome, never the reader.
		androidx.compose.foundation.Image(
			painter = BrandAssets.painter("icon-64.png"),
			contentDescription = "Ageha",
			modifier = Modifier.size(20.dp).padding(end = AgehaSpacing.xs),
		)
		for (section in Section.entries) {
			TooltipArea(tooltip = { ShortcutTooltip(section) }) {
				NavPillItem(
					label = section.label,
					isSelected = navigator.section == section,
					onClick = { navigator.switchTo(section) },
				)
			}
		}
	}
}

/** The shortcut hint the rail used to print under every label. */
@Composable
private fun ShortcutTooltip(section: Section) {
	Box(Modifier.glassSurface(MaterialTheme.shapes.small, GlassTone.RAISED).padding(AgehaSpacing.sm)) {
		Text(
			"${section.label} - ${section.shortcutHint}",
			style = AgehaTextStyles.metadata,
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}

@Composable
private fun NavPillItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
	Box(
		Modifier
			.clip(AgehaGlass.PillShape)
			.background(
				if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
			)
			.clickable(onClick = onClick)
			.padding(horizontal = AgehaSpacing.md, vertical = AgehaSpacing.sm),
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
	}
}

/**
 * How much room the floating navigation needs above the content.
 *
 * The pill's own height plus the gap it floats in. Hard-coded rather than measured because the
 * alternative is a layout pass that exists only to tell the content what it already knows, and
 * this number changes exactly when someone changes the pill.
 */
private val NAV_CLEARANCE = 64.dp

/**
 * Where you are, and the way back.
 *
 * Escape and Alt+Left both go back, but a window with no visible affordance for it is a window
 * that only power users can navigate. The crumb is clickable for the same reason.
 */
@Composable
private fun BreadcrumbBar(navigator: Navigator, title: String) {
	GlassBar(
		contentPadding = PaddingValues(horizontal = AgehaSpacing.md, vertical = AgehaSpacing.sm),
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
	history: HistoryRepository,
	scope: CoroutineScope,
) = Triple(
	LibraryViewModel(library, catalog, history, scope),
	ExploreViewModel(sources, scope),
	BrowseViewModel(catalog, sources, scope),
)
