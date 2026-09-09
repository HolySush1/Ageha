package app.ageha.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.ageha.core.data.CatalogRepository
import app.ageha.core.data.HistoryRepository
import app.ageha.core.data.LibraryRepository
import app.ageha.core.data.SourceRepository
import app.ageha.core.model.AgehaVersion
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateMapOf
import app.ageha.core.designsystem.AgehaMotion
import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaSpacing
import app.ageha.core.designsystem.interactive
import app.ageha.core.designsystem.isMotionEnabled
import app.ageha.core.designsystem.motionTween
import app.ageha.core.designsystem.rememberInteraction
import app.ageha.core.designsystem.rowHoverTint
import app.ageha.core.designsystem.snappySpring
import app.ageha.core.designsystem.AgehaTextStyles
import app.ageha.core.designsystem.BrandAssets
import app.ageha.feature.explore.BrowseScreen
import app.ageha.feature.explore.BrowseViewModel
import app.ageha.feature.explore.DetailsScreen
import app.ageha.feature.explore.DetailsViewModel
import app.ageha.feature.explore.ExploreViewModel
import app.ageha.feature.downloads.DownloadQueue
import app.ageha.feature.downloads.DownloadStatus
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
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
	/** Opens a CBZ from disk. Reached from Settings > Library, and from Ctrl+O. */
	onOpenArchive: () -> Unit = {},
	/** Which settings panel opens first. Used by the headless render; see SettingsScreen. */
	initialSettingsSection: SettingsSection = SettingsSection.APPEARANCE,
	/**
	 * Whether the command panel is up.
	 *
	 * Hoisted rather than kept here, because two things open it and only one of them is inside
	 * this composable: the magnifier in the nav pill, and Ctrl+K, which is bound on the window
	 * because that is where key events arrive. A `CTRL K` cap is drawn beside Explore's search
	 * field, and a cap printed next to a field that does not answer to that key is worse than no
	 * cap at all -- so the shortcut has to be real, and it has to reach this state.
	 */
) {
	val scope = application.scope
	// Read once rather than at each call site, because two of the uses below are inside
	// `LaunchedEffect` keys and a composition local cannot be read from a coroutine.
	val isMotion = isMotionEnabled
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
		DetailsViewModel(application.catalog, application.library, application.history, scope)
	}
	// Keyed on the two defaults it reads, so changing them in Settings rebuilds the view model
	// rather than leaving the new value to take effect at the next launch.
	val readerViewModel = remember(
		preferences.defaultReaderMode,
		preferences.defaultPageScale,
		preferences.preloadPages,
	) {
		ReaderViewModel(
			reader = application.reader,
			scope = scope,
			defaultMode = preferences.defaultReaderMode,
			defaultScale = preferences.defaultPageScale,
			preloadAhead = preferences.preloadPages,
		)
	}
	// *Not* keyed on the concurrency setting: rebuilding the queue would drop every job in it.
	// The setting is pushed into the live queue instead, from the row that changes it.
	val downloadQueue = remember {
		DownloadQueue(application.downloader, scope, preferences.parallelDownloads)
	}
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

	// Something the library grid could not do, said out loud.
	//
	// Marking a never-opened title read is a request the schema cannot honour -- there is no
	// chapter to point a reading position at until one has been stored. Surfaced here rather than
	// inside the view model for the same reason the resume outcomes are: a notice is
	// application-level furniture and outlives the screen that produced it.
	val libraryNotice by libraryViewModel.notice.collectAsState()
	LaunchedEffect(libraryNotice) {
		libraryNotice?.let { message ->
			application.notices.post("Nothing to mark read yet", message)
			libraryViewModel.consumeNotice()
		}
	}

	// Ember and Glass are two *materials*, and swapping one for the other instantly reads as a
	// glitch rather than as a change of setting -- the whole window changes colour, translucency
	// and corner radius between one frame and the next.
	//
	// So the content fades up from dim over the new skin. Not a cross-fade of the two: the
	// difference between them includes `AgehaSkin.isFlat`, a Boolean, and no amount of
	// interpolation will get you halfway between opaque and frosted. Half the surfaces would cross
	// and the other half would snap, which looks worse than either.
	//
	// The backdrop is deliberately left out of the fade. It is the ground the window is painted
	// on; dimming it would show whatever is behind the window rather than a darker Ageha.
	val skinFade = remember { Animatable(1f) }
	// Built in composition rather than inside the effect below, and not only for tidiness:
	// `motionTween` reads the reduced-motion local, a coroutine has no composition to read it
	// from, and a bare `tween` there would be an animation the Motion setting cannot switch off.
	// `MotionThroughTokensTest` fails the build over exactly this, and did.
	val skinFadeSpec = motionTween<Float>(AgehaMotion.TRANSITION_MS)
	var lastSkin by remember { mutableStateOf<AgehaThemeMode?>(null) }
	LaunchedEffect(preferences.theme, isMotion) {
		val previous = lastSkin
		lastSkin = preferences.theme
		// Never on the first composition. A window that faded in every launch would put this
		// animation on the path to resuming a chapter, which is the one path that must not grow --
		// and it would make the headless render capture a half-dim frame.
		if (previous == null || previous == preferences.theme || !isMotion) return@LaunchedEffect
		skinFade.snapTo(SKIN_FADE_FLOOR)
		skinFade.animateTo(1f, skinFadeSpec)
	}

	// Boxed so notices can float over whatever screen is current. They are application-level --
	// a backup import's report outlives the screen that started it -- so they are anchored to the
	// window rather than owned by a screen.
	Box(modifier.fillMaxSize()) {
		// The reader takes the whole window. Chrome around a page is chrome over somebody's manga,
		// and both the navigation and the backdrop are the app talking about itself while they are
		// trying to read -- brand colour behind a page would be the tinted-wash mistake rule 8
		// exists to prevent, one layer further back. One check suppresses both.
		//
		// The backdrop takes no arguments: it is derived entirely from the theme, which is chosen
		// in Settings -> Appearance. It used to be handed the most recent Continue Reading cover,
		// which meant the window's background changed when the user finished a chapter and made
		// the Appearance setting only half true. See AgehaBackdrop for the full account.
		if (!navigator.isImmersive) {
			AgehaBackdrop(modifier = Modifier.fillMaxSize()) {}
		}
		// Screens arrive from the direction they came from.
		//
		// This is the animation in Ageha that carries the most information, and its absence was
		// the most expensive: before it, opening a manga and pressing Escape back out of it were
		// visually identical events. Both were a hard cut, so the only way to know which had
		// happened was to read the screen that appeared. Depth is the thing being reported --
		// forward slides in from the right, back from the left -- and *sideways* is neither, so
		// switching section is a straight cross-fade with no direction at all.
		//
		// The specs are hoisted out of `transitionSpec` because that lambda is not a composable
		// scope, and `motionTween` has to be one: it reads the reduced-motion local.
		val fadeUp = motionTween<Float>(AgehaMotion.TRANSITION_MS)
		val fadeAway = motionTween<Float>(AgehaMotion.TRANSITION_MS, easing = AgehaMotion.exit)
		val travel = motionTween<IntOffset>(AgehaMotion.TRANSITION_MS)
		val slidePx = with(LocalDensity.current) { AgehaMotion.slide.roundToPx() }
		AnimatedContent(
			targetState = ScreenKey(navigator.current, navigator.depth, navigator.section),
			transitionSpec = {
				val toReader = targetState.destination is Destination.Read ||
					initialState.destination is Destination.Read
				val deeper = targetState.depth > initialState.depth
				val sameDepth = targetState.depth == initialState.depth
				when {
					// The reader gets a fade and nothing else. Sliding a page of somebody's manga
					// in from the edge of the window is motion over the artwork, which is the
					// thing CLAUDE.md 8 exists to prevent -- one layer further out than usual.
					toReader || sameDepth ->
						fadeIn(fadeUp) togetherWith fadeOut(fadeAway)

					deeper ->
						(fadeIn(fadeUp) + slideInHorizontally(travel) { slidePx }) togetherWith
							(fadeOut(fadeAway) + slideOutHorizontally(travel) { -slidePx / 2 })

					else ->
						(fadeIn(fadeUp) + slideInHorizontally(travel) { -slidePx }) togetherWith
							(fadeOut(fadeAway) + slideOutHorizontally(travel) { slidePx / 2 })
				}
					// Both screens are full-window and the same size, so there is nothing for a
					// size transform to animate -- and its clipping would cut the slide off at the
					// window edge instead of letting the outgoing screen leave.
					.using(SizeTransform(clip = false))
			},
			modifier = Modifier
				.fillMaxSize()
				// The navigation floats over the content rather than beside it, so the content has
				// to be told to start below it. Without this the first row of every screen sits
				// under the pill, which looks like a layout bug rather than like a layer.
				.padding(top = if (navigator.isImmersive) 0.dp else NAV_CLEARANCE)
				.graphicsLayer { alpha = skinFade.value },
			label = "screen",
		) { screen ->
			when (val destination = screen.destination) {
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
						onSort = libraryViewModel::setSort,
						onNeedHeaders = libraryViewModel::ensureHeaders,
						onBrowseSources = { navigator.switchTo(Section.EXPLORE) },
						cardStyle = preferences.cardStyle,
						blurAdultCovers = preferences.blurAdultCovers,
						// The handoff's "All 260 chapters". Ageha already has that list, on the
						// details screen, with the read and downloaded flags WIRING.md asks for.
						onOpenChapters = { navigator.openManga(it.manga) },
						onMarkRead = libraryViewModel::markRead,
						onMarkUnread = libraryViewModel::markUnread,
						onFindElsewhere = { manga ->
							navigator.searchAllSources(manga.title, subject = manga.title)
						},
					)
				}

				Destination.Continue -> {
					val state by continueViewModel.state.collectAsState()
					val headers by continueViewModel.imageHeaders.collectAsState()
					ContinueScreen(
						state = state,
						imageHeaders = headers,
						// The chapter list, not the reader.
						//
						// Clicking a row used to resume immediately, which is one click to the
						// page you were on and no clicks at all to anything else -- not the
						// chapters either side of it, not what you had already read, not the
						// description. The chapter list answers all of those and carries a
						// "Continue reading" button that does the one thing this used to do, so
						// resuming costs one extra click and everything else costs one fewer.
						//
						// An opened archive is the exception, and it has to be: a CBZ is one
						// chapter with no source behind it, so the details screen has nothing to
						// ask and would show an empty list under a failure notice. There is
						// nowhere for it to go but the reader.
						onOpen = { entry ->
							if (entry.isLocalFile) {
								continueViewModel.open(entry.mangaId)
							} else {
								navigator.openManga(entry.manga)
							}
						},
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
					// The library half of the search, which this screen inherited when it replaced
					// the command panel. Driven off the same query the source fan-out uses, so the
					// two halves can never disagree about what was asked.
					//
					// Local and instant: it filters rows already in memory rather than issuing a
					// request, so unlike the source groups it needs no debounce and no minimum
					// length.
					val libraryState by libraryViewModel.state.collectAsState()
					LaunchedEffect(state.query) { libraryViewModel.search(state.query) }
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
							libraryMatches = if (state.query.isBlank()) {
								emptyList()
							} else {
								libraryState.entries.map { it.manga }
							},
						)
					}
				}

				Destination.Downloads -> {
					val queued by downloadQueue.jobs.collectAsState()
					// Rescanned when the queue changes rather than on a timer. A chapter finishing
					// is the only thing that grows this figure while the screen is open, and a
					// filesystem walk on a poll would be work done to observe nothing happening.
					// `deleteEpoch` re-triggers it after a delete, which the queue never sees.
					var deleteEpoch by remember { mutableStateOf(0) }
					val storage by produceState(
						app.ageha.core.data.StorageReport.EMPTY,
						queued.size,
						deleteEpoch,
					) {
						value = withContext(Dispatchers.IO) {
							runCatching { application.downloadInventory.scan() }
								.getOrDefault(app.ageha.core.data.StorageReport.EMPTY)
						}
					}
					DownloadsScreen(
						jobs = queued,
						onCancel = downloadQueue::cancel,
						onRetry = downloadQueue::retry,
						onCancelAll = downloadQueue::cancelAll,
						onClearFinished = downloadQueue::clearFinished,
						onPause = downloadQueue::pause,
						onResume = downloadQueue::resume,
						onPauseAll = downloadQueue::pauseAll,
						onResumeAll = downloadQueue::resumeAll,
						// Derived from `queued` rather than read off the queue's own getter, so
						// the label recomposes when the list does. The getter reads the same
						// flow, but Compose has no way to know that.
						hasRunningWork = queued.any {
							it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING
						},
						// The thumbnail opens the reader. The inventory is keyed by a sanitised
						// directory name rather than by a manga id, so the way back to the title
						// is a search for that name -- which is also the only path that works
						// when the download came from a source that is no longer enabled.
						onOpenTitle = { title ->
							navigator.searchAllSources(title.title, subject = title.title)
						},
						storage = storage,
						onDeleteTitle = { title ->
							scope.launch {
								val freed = withContext(Dispatchers.IO) {
									application.downloadInventory.delete(title)
								}
								deleteEpoch++
								// Reported rather than silent. This is the one destructive action
								// on the screen, and saying how much it actually reclaimed is the
								// difference between a delete you can trust and one you hope
								// worked.
								application.notices.post(
									"Deleted ${title.title}",
									"${title.chapterCount} chapters removed. The title stays in " +
										"your library; only the downloaded files are gone.",
								)
								if (freed <= 0L) {
									application.notices.post(
										"Nothing was deleted",
										"Ageha could not remove the files for ${title.title}. " +
											"They may be open in another program.",
										isError = true,
									)
								}
							}
						},
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
						motion = preferences.motion,
						onMotion = { onPreferencesChange(preferences.copy(motion = it)) },
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
						onOpenArchive = onOpenArchive,
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
						readerMode = preferences.defaultReaderMode,
						onReaderMode = {
							onPreferencesChange(preferences.copy(defaultReaderMode = it))
						},
						pageScale = preferences.defaultPageScale,
						onPageScale = {
							onPreferencesChange(preferences.copy(defaultPageScale = it))
						},
						preloadPages = preferences.preloadPages,
						onPreloadPages = {
							onPreferencesChange(preferences.copy(preloadPages = it))
						},
						cardStyle = preferences.cardStyle,
						onCardStyle = { onPreferencesChange(preferences.copy(cardStyle = it)) },
						blurAdultCovers = preferences.blurAdultCovers,
						onBlurAdultCovers = {
							onPreferencesChange(preferences.copy(blurAdultCovers = it))
						},
						parallelDownloads = preferences.parallelDownloads,
						onParallelDownloads = {
							// Applied to the live queue as well as stored. The queue outlives this
							// screen, so writing only the preference would leave the setting
							// taking effect at the next launch -- which for a control whose whole
							// point is "make this download finish sooner" is no effect at all.
							downloadQueue.perSourceConcurrency = it
							onPreferencesChange(preferences.copy(parallelDownloads = it))
						},
						railCollapsed = preferences.libraryRailCollapsed,
						onRailCollapsed = {
							onPreferencesChange(preferences.copy(libraryRailCollapsed = it))
						},
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
						onEnableDefaults = exploreViewModel::enableDefaults,
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
							onFindElsewhere = { manga ->
								navigator.searchAllSources(manga.title, subject = manga.title)
							},
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
									override fun chapters() {
										readerViewModel.savePositionNow()
										navigator.openChapterList(destination.manga)
									}
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
						onVisibleThrough = readerViewModel::recordVisible,
						onNextPage = readerViewModel::nextPage,
						onPreviousPage = readerViewModel::previousPage,
						onNextChapter = readerViewModel::nextChapter,
						onPreviousChapter = { readerViewModel.previousChapter() },
						// The position is flushed on the way out rather than left to the
						// debounce, for the same reason the DisposableEffect below does it: the
						// reader is about to be popped, and the page turn most worth remembering
						// is the last one.
						onOpenChapterList = {
							readerViewModel.savePositionNow()
							navigator.openChapterList(destination.manga)
						},
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
						preloadPages = preferences.preloadPages,
						imageLoader = application.imageLoader,
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
							// Resolved through the same pipeline Continue Reading uses, rather
							// than by opening `state.marker`'s chapter directly. That pipeline
							// already knows the three things this button would otherwise have to
							// re-derive: that a finished chapter means the *next* one, that a
							// manga with no stored chapters needs them fetched first, and that a
							// source missing from this parsers build has nothing to open at all.
							onContinueReading = {
								continueViewModel.open(destination.manga.id)
							},
							onMarkReadThrough = detailsViewModel::markReadThrough,
							onMarkUnreadFrom = detailsViewModel::markUnreadFrom,
							// `state.manga` rather than the destination's copy, so the query is
							// the title the source returned rather than the one the card was
							// built from -- those differ for a backup-imported row, whose stored
							// title can be years stale. Falls back to the destination when the
							// fetch has not landed or failed, which is when this is most needed.
							onFindElsewhere = {
								val title = state.manga?.title ?: destination.manga.title
								navigator.searchAllSources(title, subject = title)
							},
						)
					}
				}
			}
		}

		if (!navigator.isImmersive) {
			FloatingNav(
				navigator = navigator,
				// Straight to the full-page search, not a popover.
				//
				// This used to open the command panel, which showed at most three library rows and
				// two results from every enabled source *combined*. Against 1360 sources that is
				// not a summary, it is a coin toss -- reported as "this small bar causes me to
				// miss out on a lot of searches", which is exactly what a five-row cap over a
				// fan-out search does. The full page groups every source separately and caps
				// nothing.
				onSearchAllSources = {
					navigator.openGlobalSearch()
					runCatching { searchFocus.requestFocus() }
				},
				modifier = Modifier.align(Alignment.TopCenter),
			)
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
private fun FloatingNav(
	navigator: Navigator,
	onSearchAllSources: () -> Unit,
	modifier: Modifier = Modifier,
) {
	// Where each word sits inside the pill, measured rather than assumed.
	//
	// The alternative -- deriving the indicator's position from the item widths -- would mean the
	// pill knowing the width of "Downloads" set in `labelLarge` at the current density, which is a
	// number only layout has. Measuring is four lines and stays correct when somebody renames a
	// section or the type scale changes.
	val slots = remember { mutableStateMapOf<Section, PillSlot>() }
	var pillOrigin by remember { mutableStateOf(0f) }
	val density = LocalDensity.current

	// Section.CONTINUE is reachable but has no word in the pill, so there is genuinely nothing to
	// point at while you are on it. The indicator retreats rather than parking under an unrelated
	// word and claiming you are somewhere you are not.
	val lit = slots[navigator.section]
	val indicatorAlpha by animateFloatAsState(
		targetValue = if (lit == null) 0f else 1f,
		animationSpec = motionTween(AgehaMotion.QUICK_MS),
		label = "nav-indicator-alpha",
	)
	// Held at the last known slot while fading out, so it dissolves in place instead of sliding
	// back to the origin on its way to invisible.
	var resting by remember { mutableStateOf(PillSlot(0.dp, 0.dp)) }
	if (lit != null) resting = lit

	// The *first* placement is not a movement, and must not be animated.
	//
	// Nothing knows where the words are until layout has run, so the first measurement always
	// arrives as a change from (0, 0) -- and a spring would faithfully animate the indicator
	// growing out of the pill's left edge on every launch, reporting a navigation that did not
	// happen. Snapping until the first slot is known also makes the pill correct in a single
	// rendered frame, which is what the headless capture in `ShellRender` sees.
	var placed by remember { mutableStateOf(false) }
	val travelSpec = if (placed) snappySpring(Dp.VisibilityThreshold) else snap()
	LaunchedEffect(resting) { if (resting.width > 0.dp) placed = true }

	val indicatorX by animateDpAsState(
		targetValue = resting.x,
		animationSpec = travelSpec,
		label = "nav-indicator-x",
	)
	val indicatorWidth by animateDpAsState(
		targetValue = resting.width,
		animationSpec = travelSpec,
		label = "nav-indicator-width",
	)

	Box(
		modifier
			.padding(top = AgehaSpacing.md)
			.glassSurface(AgehaGlass.PillShape, GlassTone.CHROME)
			.padding(AgehaSpacing.xs)
			.onGloballyPositioned { pillOrigin = it.positionInWindow().x },
	) {
		// One indicator that travels, rather than four backgrounds that switch.
		//
		// The eye follows movement, and the movement is the message: it says the selection *went*
		// from Library to Explore. Extinguishing one highlight and lighting another two hundred
		// pixels away says only that something changed, and leaves the user to work out what.
		//
		// Drawn behind the words, sized and placed from the measured slot, so it fits "Downloads"
		// and "Library" without either being padded to match the other.
		if (indicatorWidth > 0.dp) {
			Box(
				Modifier
					.offset(x = indicatorX)
					.width(indicatorWidth)
					.height(NAV_ITEM_HEIGHT)
					.graphicsLayer { alpha = indicatorAlpha }
					.clip(AgehaGlass.PillShape)
					.background(MaterialTheme.colorScheme.primaryContainer)
					.border(1.dp, AgehaTheme.skin.accentLine, AgehaGlass.PillShape),
			)
		}
		Row(
			horizontalArrangement = Arrangement.spacedBy(AgehaSpacing.xxs),
			verticalAlignment = Alignment.CenterVertically,
		) {
			for (section in NAV_PILL_LEADING) {
				TooltipArea(tooltip = { ShortcutTooltip(section) }) {
					NavPillItem(
						label = section.label,
						isSelected = navigator.section == section,
						onClick = { navigator.switchTo(section) },
						onMeasured = { slots[section] = it },
						pillOrigin = pillOrigin,
						density = density,
					)
				}
			}
			SearchAllButton(
				isSelected = navigator.current is Destination.SearchAll,
				onClick = onSearchAllSources,
			)
			for (section in NAV_PILL_TRAILING) {
				TooltipArea(tooltip = { ShortcutTooltip(section) }) {
					NavPillItem(
						label = section.label,
						isSelected = navigator.section == section,
						onClick = { navigator.switchTo(section) },
						onMeasured = { slots[section] = it },
						pillOrigin = pillOrigin,
						density = density,
					)
				}
			}
			HomeButton(
				isSelected = navigator.section == Section.LIBRARY && !navigator.canGoBack,
				onClick = navigator::openHome,
			)
		}
	}
}

/**
 * Where one word sits in the pill, in the pill's own coordinates.
 *
 * Measured in window space and then subtracted, rather than read from `positionInParent`, because
 * the indicator and the `Row` are siblings inside the pill's `Box` and only window space is common
 * to both. A parent-relative reading would be relative to the `Row`, and the two agree only for as
 * long as nobody adds a second child.
 */
@Immutable
private data class PillSlot(val x: Dp, val width: Dp)

/**
 * The pill items' height, which the sliding indicator has to match.
 *
 * A constant rather than a measurement, and this one is worth being honest about: the indicator
 * could measure its height the same way it measures its width, but every item in the pill is the
 * same height by construction -- 9dp of vertical padding around one line of `labelLarge` -- and a
 * per-item height would be three more state writes per frame to reproduce a number that is the
 * same for all of them. It changes when somebody changes [NavPillItem]'s padding, which is the
 * same moment [NAV_CLEARANCE] changes, and they sit ten lines apart for that reason.
 */
private val NAV_ITEM_HEIGHT = 38.dp

/**
 * Back to the screen Ageha opens on, from anywhere.
 *
 * ## Why this is not the same thing as the Library word two places to its left
 *
 * That one switches *section*, and each section keeps its own back stack -- so pressing Library
 * from four screens deep in Explore returns you to whatever details screen you last left the
 * library on, which is deliberate and is what makes flicking between sections cheap. It is also
 * not what someone reaching for a home button wants: they want the top, and there was no control
 * in Ageha that took them there. Escape unwinds one screen at a time and the breadcrumb only
 * appears on the screens that have one.
 *
 * So this resets the stack as well, and lights only when you are actually at the root -- a home
 * button that looks pressed while you are three screens deep is a home button that has lied about
 * where you are.
 *
 * ## Why an icon at the end rather than a sixth word
 *
 * The same reason the magnifier is one: five words plus a sixth is where a pill becomes a menu
 * bar. It sits at the trailing end because that is the far corner of the pill -- the easiest
 * target in it after the two ends of the window -- and because a control that undoes navigation
 * belongs beside the controls that perform it, not in the middle of them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeButton(isSelected: Boolean, onClick: () -> Unit) {
	val shape = AgehaGlass.PillShape
	TooltipArea(
		tooltip = {
			Box(
				Modifier
					.glassSurface(MaterialTheme.shapes.small, GlassTone.RAISED)
					.padding(AgehaSpacing.sm),
			) {
				Text(
					"Home - the library, at the top",
					style = AgehaTextStyles.metadata,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}
		},
	) {
		val hover = rememberInteraction()
		// Home keeps its own highlight rather than joining the sliding indicator, because it is
		// not one of the four sections -- at the root of the library *both* are lit, and one
		// indicator cannot be in two places. Its fill is crossed instead, so it still arrives
		// rather than appearing.
		val fill by animateColorAsState(
			targetValue = if (isSelected) {
				MaterialTheme.colorScheme.primaryContainer
			} else {
				Color.Transparent
			},
			animationSpec = motionTween(AgehaMotion.QUICK_MS),
			label = "home-fill",
		)
		Box(
			Modifier
				.clip(shape)
				.background(fill)
				.interactive(hover, hoverTint = rowHoverTint, shape = shape)
				.border(
					1.dp,
					if (isSelected) AgehaTheme.skin.accentLine else AgehaTheme.skin.line,
					shape,
				)
				.clickable(interactionSource = hover, indication = null, onClick = onClick)
				.padding(horizontal = 14.dp, vertical = 9.dp)
				.testTag(NAV_HOME_TAG),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				imageVector = Icons.Default.Home,
				// Named, because an icon-only control with no accessible name is a control only
				// the person who wrote it can use.
				contentDescription = "Home",
				tint = if (isSelected) {
					MaterialTheme.colorScheme.onSurface
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				modifier = Modifier.size(18.dp),
			)
		}
	}
}

/**
 * Test tag for the end-to-end journey driver.
 *
 * See [SEARCH_ALL_TAG]: an icon has no text to find it by.
 */
const val NAV_HOME_TAG = "nav-home"

/**
 * The sections either side of the search button.
 *
 * Written out rather than derived from `Section.entries` with a filter, because the split point is
 * the design -- search sits *between* Explore and Downloads -- and a filter would have to encode
 * that as an index, which is the kind of thing that reorders silently when someone adds a section.
 *
 * [Section.CONTINUE] is in neither list, and that is the handoff's doing rather than an oversight:
 * Continue Reading is the *banner at the top of the Library* there, not a place you navigate to.
 * The screen survives, keeps Ctrl+2, and is still reached by the banner's see-all link.
 */
private val NAV_PILL_LEADING = listOf(Section.LIBRARY, Section.EXPLORE)
private val NAV_PILL_TRAILING = listOf(Section.DOWNLOADS, Section.SETTINGS)

/**
 * Search every enabled source, from anywhere.
 *
 * ## Why it is in the pill rather than being a sixth section
 *
 * The sections are *places*: a library, a history, a catalogue, a queue, some settings. Search is
 * an **action** you take on the place you are already in, and giving it a section would have
 * pushed it into the same back stack as the screen it was launched from and made Escape ambiguous.
 * A trailing icon in the same pill is reachable from every screen without claiming to be one.
 *
 * ## Why an icon rather than a sixth word
 *
 * Five words plus a sixth is the point at which a pill becomes a menu bar. A magnifier is the one
 * glyph in this interface that needs no label at all -- and it gets one anyway, in a tooltip and
 * in `contentDescription`, because an icon-only control with no accessible name is a control only
 * the person who wrote it can use.
 *
 * It carries a selected state like every other item in the pill: while the results are on screen,
 * the thing that opened them is lit. Without that, this is the one destination in Ageha you can be
 * looking at while the navigation claims you are somewhere else.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchAllButton(isSelected: Boolean, onClick: () -> Unit) {
	val searchHover = rememberInteraction()
	TooltipArea(
		tooltip = {
			Box(
				Modifier
					.glassSurface(MaterialTheme.shapes.small, GlassTone.RAISED)
					.padding(AgehaSpacing.sm),
			) {
				Text(
					"Search your library and every enabled source - Ctrl+K",
					style = AgehaTextStyles.metadata,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}
		},
	) {
		Box(
			Modifier
				.padding(horizontal = 5.dp)
				.size(40.dp)
				// Circular in both skins, unlike everything else in the pill. Ember's items are
				// 10dp boxes and Glass's are lozenges, but the handoff draws this one as a circle
				// either way -- it is a *button* among labels, and the shape is what says so.
				.clip(CircleShape)
				// Always the accent, selected or not. The handoff fills this circle
				// unconditionally -- it is the pill's one fixed landmark, and a button that
				// changed colour when you were looking at its results would make the brightest
				// point in the bar wander. Selection shows as a ring instead.
				.background(AgehaTheme.skin.accent)
				.then(
					if (isSelected) {
						Modifier.border(2.dp, AgehaTheme.skin.accentLine, CircleShape)
					} else {
						Modifier
					},
				)
				// The one control in the pill that *does* scale. It is a button rather than a
				// label, it has room around it on both sides, and it is the pill's fixed
				// landmark -- so it is the one place a bit of weight under the pointer reads as
				// affordance rather than as the bar shuffling.
				.interactive(searchHover, hoverScale = HOVER_SCALE_PILL_BUTTON)
				.clickable(interactionSource = searchHover, indication = null, onClick = onClick)
				.testTag(SEARCH_ALL_TAG),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				imageVector = Icons.Default.Search,
				contentDescription = "Search your library and every enabled source",
				// White rather than `onPrimary`, because this fill is the *raw* accent rather than
				// the contrast-corrected one -- see AgehaSkin.accent. Both skins' accents are
				// mid-tone enough that white clears the 3:1 a non-text glyph is held to.
				tint = Color.White,
				modifier = Modifier.size(18.dp),
			)
		}
	}
}

/**
 * Test tag for the end-to-end journey driver.
 *
 * An icon has no text to find it by, and "the fifth thing in the pill" is a locator that breaks
 * the next time a section is added.
 */
const val SEARCH_ALL_TAG = "nav-search-all"

/**
 * How much the search circle grows under the pointer.
 *
 * Larger than a cover's four percent, which is not an inconsistency. A 40dp circle at 1.04 gains
 * under two pixels and reads as nothing; a 132dp card at the same ratio gains five and reads as a
 * lift. The scale that feels like "the same amount" is a bigger number on a smaller control.
 */
private const val HOVER_SCALE_PILL_BUTTON = 1.08f

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

/**
 * One word in the pill.
 *
 * Active is the handoff's three-part treatment and it needs all three: `--accent-soft` fill,
 * `--accent-line` border, full-strength ink. The fill alone is too quiet against a translucent
 * container in Glass, and the border alone reads as a focus ring rather than as a selection.
 *
 * **The first two of those three now live on the indicator rather than here**, because there is
 * only one selection and it travels. What stays on the item is the ink, which does have to be
 * per-item -- it crosses from the muted variant to full strength as the indicator arrives, so the
 * word lights up as the highlight reaches it rather than a beat before or after.
 *
 * Inactive still draws its own `--line` border. Without it the items have no edges until one is
 * selected, and the pill reads as a strip of text that happens to be clickable instead of as a row
 * of controls.
 */
@Composable
private fun NavPillItem(
	label: String,
	isSelected: Boolean,
	onClick: () -> Unit,
	/** Reports this word's place in the pill, so the indicator can slide to it. */
	onMeasured: (PillSlot) -> Unit,
	/** The pill's own left edge, in window space. See [PillSlot]. */
	pillOrigin: Float,
	density: Density,
) {
	val shape = AgehaGlass.PillShape
	val hover = rememberInteraction()
	// Crossed rather than switched, and at the same duration the indicator takes to arrive.
	val ink by animateColorAsState(
		targetValue = if (isSelected) {
			MaterialTheme.colorScheme.onSurface
		} else {
			MaterialTheme.colorScheme.onSurfaceVariant
		},
		animationSpec = motionTween(AgehaMotion.QUICK_MS),
		label = "nav-ink",
	)
	Box(
		Modifier
			.onGloballyPositioned { coordinates ->
				with(density) {
					onMeasured(
						PillSlot(
							x = (coordinates.positionInWindow().x - pillOrigin).toDp(),
							width = coordinates.size.width.toDp(),
						),
					)
				}
			}
			.clip(shape)
			// No scale. These sit shoulder to shoulder with 2dp between them, and a word that grew
			// under the pointer would collide with its neighbours -- and drag the measured slot
			// the indicator is chasing along with it.
			.interactive(hover, hoverTint = rowHoverTint, shape = shape)
			.border(
				1.dp,
				if (isSelected) Color.Transparent else AgehaTheme.skin.line,
				shape,
			)
			.clickable(interactionSource = hover, indication = null, onClick = onClick)
			.padding(horizontal = 19.dp, vertical = 9.dp),
	) {
		Text(label, style = MaterialTheme.typography.labelLarge, color = ink)
	}
}

/**
 * What the shell's screen transition is actually comparing.
 *
 * Three fields, and only one of them is the screen: the transition is decided by [depth] and
 * [section], and [destination] is carried along so the outgoing screen can still be drawn while it
 * leaves. `AnimatedContent` keeps the previous state composed for the length of the transition, so
 * it cannot simply read `navigator.current` -- by then that is the screen it is transitioning *to*.
 *
 * Equality is hand-written against [Destination.transitionId] rather than generated, and that is
 * the point of the class. A generated `equals` would compare the [Destination], and two of those
 * carry an `AgehaManga` whose own generated `equals` walks its entire chapter list -- on every
 * recomposition, to answer a question a short string answers exactly as well.
 */
@Immutable
private class ScreenKey(
	val destination: Destination,
	val depth: Int,
	val section: Section,
) {
	private val id = "${section.name}/$depth/${destination.transitionId}"

	override fun equals(other: Any?): Boolean = other is ScreenKey && other.id == id

	override fun hashCode(): Int = id.hashCode()
}

/**
 * How dim the window goes at the moment the skin changes, before fading back up.
 *
 * Far enough to read as a deliberate swap of material rather than a repaint glitch; not so far
 * that the window looks like it blacked out. See the fade in [AgehaShell] for why this is a fade
 * *up* rather than a cross-fade between the two skins.
 */
private const val SKIN_FADE_FLOOR = 0.45f

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

/**
 * How long the panel waits before asking every enabled source.
 *
 * 150ms is WIRING.md's figure and it is about *cost*, not smoothness: each fan-out is one request
 * per enabled source, sent from the user's own address. Typing "one piece" without this is nine
 * fan-outs where one was wanted.
 */
private const val REMOTE_SEARCH_DEBOUNCE_MS = 150L

/**
 * The shortest query worth fanning out on.
 *
 * Two characters match most of every catalogue, so a source is asked to compute and serve a result
 * set nobody will read. The local half of the panel still answers from the first keystroke.
 */
private const val REMOTE_SEARCH_MIN_LENGTH = 3
