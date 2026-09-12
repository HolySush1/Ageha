package app.ageha.feature.explore

import app.ageha.core.data.LocaleOption
import app.ageha.core.data.SourceListing
import app.ageha.core.data.SourceRepository
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.source.ResolvedLink
import app.ageha.core.source.SiteLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Which slice of the catalogue the source picker is showing. */
enum class SourceFilter(val label: String) {
	ENABLED("Enabled"),
	ALL("All sources"),
	BROKEN("Known broken"),
}

/**
 * The three source filters, as one value.
 *
 * Held together rather than as three flows because they are read together on every recomputation
 * and because `combine` stops being typed past five arguments. Grouping them also makes the one
 * rule between them expressible in a single place: see [includeBroken].
 */
private data class Filters(
	val view: SourceFilter = SourceFilter.ENABLED,
	val locale: String? = null,
	val hideBroken: Boolean = true,
	val showAdult: Boolean = false,
) {
	/**
	 * Whether broken sources survive filtering.
	 *
	 * The [SourceFilter.BROKEN] view overrides the toggle. Asking to see the broken sources and
	 * being shown an empty list because a switch elsewhere hides broken sources is the kind of
	 * interaction that makes people think the app is lying to them.
	 */
	val includeBroken: Boolean get() = view == SourceFilter.BROKEN || !hideBroken
}

data class ExploreUiState(
	val sources: List<SourceListing> = emptyList(),
	val query: String = "",
	val filter: SourceFilter = SourceFilter.ENABLED,
	val locale: String? = null,
	val availableLocales: List<LocaleOption> = emptyList(),
	/** Whether sources upstream flagged broken are being withheld. */
	val hideBroken: Boolean = true,
	/** Whether adult sources are being shown. Off until the user says otherwise. */
	val showAdult: Boolean = false,
	/**
	 * How many sources each filter is currently withholding.
	 *
	 * These are shown, always. A filter that silently removes rows is indistinguishable from a
	 * catalogue that never had them: a user looking for a source Ageha has hidden gets no result,
	 * no explanation and no way to guess that a switch is responsible. The two counts never
	 * overlap -- an adult source that is also broken is counted once, under adult.
	 */
	val hiddenAdultCount: Int = 0,
	val hiddenBrokenCount: Int = 0,
	val parsersVersion: String = "",
	val totalCount: Int = 0,
	val enabledCount: Int = 0,
	/**
	 * How many of the default English sources are currently off.
	 *
	 * The button that turns them on shows this, and goes away at zero. A button that stays put
	 * after it has nothing left to do teaches people that it does nothing.
	 */
	val defaultsOff: Int = 0,
	/**
	 * How many sources the same search would find with the filter set to [SourceFilter.ALL].
	 *
	 * Carried so the screen can tell "nothing is called that" apart from "nothing you have turned
	 * on is called that". They look identical to a user and they need opposite advice, and on a
	 * fresh installation -- where nothing is enabled yet -- it is always the second one.
	 */
	val matchesInAllSources: Int = 0,
	val isLoading: Boolean = true,
) {
	/** Whether anything is being withheld, which is what the screen needs to know to say so. */
	val hasHiddenSources: Boolean get() = hiddenAdultCount > 0 || hiddenBrokenCount > 0
}

/**
 * The source picker.
 *
 * The catalogue is around 1360 entries, which is the fact that shapes this screen. It is far too
 * many to scroll and far too many to enable by default, so the picker opens on *enabled* sources
 * and treats browsing the full list as a deliberate act. Filtering runs in memory over a list the
 * parsers library already holds -- there is no query to push down to.
 *
 * Three filters narrow it further, and they are independent rather than one exclusive choice.
 * That is the whole point: "show me the broken ones" and "hide the broken ones" are different
 * questions, a catalogue of 1360 sources in every language is unusable without a language filter,
 * and adult sources are something a user should opt into rather than scroll past.
 *
 * @param onFiltersChanged called whenever a filter changes, so the shell can persist it. The
 *   filters outlive the window: re-hiding adult sources on every launch would be a setting that
 *   does not stay set, and re-showing them would be worse.
 */
/**
 * How long a link lookup in the Add site dialog may take.
 *
 * Generous, because a manga link can mean a details request through the browser tier -- Comix
 * answers in tens of seconds, not milliseconds -- while a front page resolves almost at once. Cancel
 * is always on screen, so a long wait is never a trap.
 *
 * Top level and internal rather than in the companion, which is private: the timeout test needs
 * this exact figure, and a copy of it in the test would be a number free to drift.
 */
internal const val ADD_SITE_TIMEOUT_MS = 60_000L

/**
 * Which of the sources serving a site to offer first.
 *
 * The reading language the person is most likely to want, then English, then whatever upstream's
 * resolver named. That last fallback is what the dialog used to do for *everything*, and it is why
 * pasting mangaball.net answered "Manga Ball (Arabic)": `LinkResolver` returns the first source in
 * the build's declaration order that serves the host, 42 languages share that domain, and `AR`
 * sorts first. Upstream is not wrong to pick one -- it is only wrong as a default.
 *
 * Matched on the primary subtag, so a `pt-BR` source answers to a `pt` machine. [systemLanguage] is
 * passed in rather than read here so a test does not depend on the machine running it.
 */
internal fun preferredSource(
	candidates: List<SourceDescriptor>,
	resolved: String,
	systemLanguage: String,
): SourceDescriptor? {
	val upstream = candidates.firstOrNull { it.name == resolved }
	if (candidates.size <= 1) return upstream ?: candidates.firstOrNull()
	fun language(descriptor: SourceDescriptor) =
		descriptor.locale?.substringBefore('-')?.lowercase()
	return candidates.firstOrNull { language(it) == systemLanguage.lowercase() }
		?: candidates.firstOrNull { language(it) == "en" }
		?: upstream
		?: candidates.firstOrNull()
}

class ExploreViewModel(
	private val sources: SourceRepository,
	private val scope: CoroutineScope,
	hideBroken: Boolean = true,
	showAdult: Boolean = false,
	locale: String? = null,
	/**
	 * The language to prefer when one site is served by a source per language.
	 *
	 * Injected, with the machine's own as the default, so a test can state the language it means
	 * instead of inheriting whatever the machine running it happens to be set to.
	 */
	private val systemLanguage: String = Locale.getDefault().language,
	private val onFiltersChanged: (hideBroken: Boolean, showAdult: Boolean, locale: String?) -> Unit =
		{ _, _, _ -> },
) {

	private val _addSite = MutableStateFlow<AddSiteState>(AddSiteState.Closed)

	/**
	 * The Add site dialog.
	 *
	 * Its own flow rather than a field on [state], and for a measurable reason: [state] is a
	 * `combine` that re-runs a search over all ~1,360 sources whenever any input changes. Putting
	 * the dialog's text in there would repeat that search on every character of a pasted URL, for
	 * a dialog that shares nothing with the list behind it.
	 */
	val addSite: StateFlow<AddSiteState> = _addSite.asStateFlow()

	/** The lookup in flight, so a new link or a closed dialog can stop it. */
	private var resolving: Job? = null

	private val query = MutableStateFlow("")
	private val filters = MutableStateFlow(
		Filters(locale = locale, hideBroken = hideBroken, showAdult = showAdult),
	)

	val state: StateFlow<ExploreUiState> = combine(
		sources.observeAll(),
		query,
		filters,
		// Its own flow rather than a count derived from `all`, because "off" here means off in the
		// database, and `all` has already had the tab and the filters applied by the time anything
		// could count it.
		sources.observeDefaultsOff(),
	) { all, text, f, defaultsOff ->
		val visible = when (f.view) {
			SourceFilter.ENABLED -> all.filter { it.isEnabled }
			SourceFilter.ALL -> all
			// Upstream flags sources it knows are broken. Surfacing them as their own view is
			// better than hiding them: a user whose favourite source stopped working gets an
			// answer instead of assuming Ageha broke.
			SourceFilter.BROKEN -> all.filter { it.descriptor.isBroken }
		}

		val found = sources.search(
			listings = visible,
			query = text,
			locale = f.locale,
			includeAdult = f.showAdult,
			includeBroken = f.includeBroken,
		).sortedWith(SOURCE_ORDER)

		// What the same search finds with both toggles off, so the difference between the two is
		// exactly what the toggles withheld -- rather than a number derived from a second guess
		// at the filter logic, which is how the count and the list end up disagreeing.
		val unfiltered = sources.search(
			listings = visible,
			query = text,
			locale = f.locale,
			includeAdult = true,
			includeBroken = true,
		)
		val afterAdult = unfiltered.filter { f.showAdult || !it.descriptor.isAdult }

		ExploreUiState(
			sources = found,
			query = text,
			filter = f.view,
			locale = f.locale,
			// Computed without the query so the language menu does not reshuffle under the
			// pointer while someone is typing, and without the locale filter so it offers more
			// than the language already selected.
			availableLocales = sources.availableLocales(
				sources.search(
					listings = visible,
					query = "",
					locale = null,
					includeAdult = f.showAdult,
					includeBroken = f.includeBroken,
				),
			),
			hideBroken = f.hideBroken,
			showAdult = f.showAdult,
			hiddenAdultCount = unfiltered.size - afterAdult.size,
			hiddenBrokenCount = if (f.includeBroken) 0 else afterAdult.count { it.descriptor.isBroken },
			parsersVersion = sources.parsersVersion,
			totalCount = all.size,
			enabledCount = all.count { it.isEnabled },
			defaultsOff = defaultsOff.size,
			// Only computed when this filter came back with nothing, which is the only time the
			// screen asks. Running the same search over 1360 entries on every keystroke to answer
			// a question nobody asked would be the one expensive thing on this screen.
			matchesInAllSources = if (found.isNotEmpty() || f.view == SourceFilter.ALL) {
				0
			} else {
				sources.search(
					listings = all,
					query = text,
					locale = f.locale,
					includeAdult = f.showAdult,
					includeBroken = f.includeBroken,
				).size
			},
			isLoading = false,
		)
	}.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ExploreUiState())

	fun search(text: String) {
		query.value = text
	}

	fun setFilter(which: SourceFilter) {
		filters.update { it.copy(view = which) }
	}

	fun setLocale(tag: String?) {
		filters.update { it.copy(locale = tag) }
		publish()
	}

	fun setHideBroken(hide: Boolean) {
		filters.update { it.copy(hideBroken = hide) }
		publish()
	}

	fun setShowAdult(show: Boolean) {
		filters.update { it.copy(showAdult = show) }
		publish()
	}

	fun setEnabled(name: String, enabled: Boolean) {
		scope.launch { sources.setEnabled(name, enabled) }
	}

	/**
	 * What the last lookup resolved, kept because the dialog's state cannot answer for it.
	 *
	 * Specifically: whether the *link* named a manga. Once a re-resolve has come back empty the
	 * status carries no manga, and without this a second change of language would read that as a
	 * site link and never ask again.
	 */
	private var resolvedLink: ResolvedLink? = null

	fun openAddSite() {
		_addSite.value = AddSiteState.Open()
		// Listing every source that serves a domain means constructing every parser in the build,
		// which is the slow part of a lookup and the same work whenever it happens. Started here so
		// it runs while the person is still pasting, rather than landing on the Find click.
		scope.launch { sources.warmLinkIndex() }
	}

	fun closeAddSite() {
		resolving?.cancel()
		resolvedLink = null
		_addSite.value = AddSiteState.Closed
	}

	/**
	 * The text changed.
	 *
	 * Any answer on screen belonged to the previous text, so it goes: "comix.to → Comix" left
	 * showing under a field that now reads something else is a result for a question nobody is
	 * asking any more, and its Open button would open the wrong thing.
	 */
	fun setAddSiteInput(text: String) {
		val open = _addSite.value as? AddSiteState.Open ?: return
		if (text == open.input) return
		resolving?.cancel()
		resolvedLink = null
		_addSite.value = open.copy(input = text, status = AddSiteStatus.Idle)
	}

	fun findSite() {
		val open = _addSite.value as? AddSiteState.Open ?: return
		val link = SiteLinks.normalise(open.input)
		if (link == null) {
			_addSite.value = open.copy(status = AddSiteStatus.NotALink)
			return
		}
		val host = SiteLinks.hostOf(link)
		resolving?.cancel()
		_addSite.value = open.copy(status = AddSiteStatus.Resolving(host))
		resolving = scope.launch {
			val status = try {
				// Wrapped so a timeout can be told apart from an honest "no source": both would
				// otherwise arrive as null, and they mean opposite things to the person waiting.
				val answer = withTimeoutOrNull(ADD_SITE_TIMEOUT_MS) { Answer(found(host, link)) }
				answer?.status ?: AddSiteStatus.Failed(
					host,
					"it took longer than ${ADD_SITE_TIMEOUT_MS / 1000} seconds to answer.",
				)
			} catch (e: CancellationException) {
				// Cancelled because the text changed or the dialog closed. Writing a status now
				// would resurrect a lookup the person has already moved on from.
				throw e
			} catch (e: Exception) {
				AddSiteStatus.Failed(host, e.message ?: e::class.simpleName.orEmpty())
			}
			// Applied only if the dialog is still open on the same link. Cancellation covers the
			// common case; this covers the one where the answer lands in the same instant the
			// text changes, which cancellation alone can lose the race to.
			_addSite.update { current ->
				if (current is AddSiteState.Open && SiteLinks.normalise(current.input) == link) {
					current.copy(status = status)
				} else {
					current
				}
			}
		}
	}

	/**
	 * Take the found source: switch it on, close the dialog, and say where to go.
	 *
	 * Switched on without asking, because pasting a site's link *is* the asking. A source that was
	 * opened from a link and then vanished from the Enabled list the moment the user looked away
	 * would read as the link not having worked.
	 *
	 * @return what was found, for the caller to navigate to; null when nothing was.
	 */
	fun acceptFound(): AddSiteStatus.Found? {
		val found = (_addSite.value as? AddSiteState.Open)?.status as? AddSiteStatus.Found ?: return null
		setEnabled(found.source.name, true)
		closeAddSite()
		return found
	}

	/**
	 * Resolve [link] and decide what the dialog should show for it.
	 *
	 * Every source serving the site is offered, not just the one upstream named. A site served by
	 * a source per language resolves to whichever the build declares first, which for mangaball.net
	 * is the Arabic one out of 42 -- an answer that is not wrong so much as arbitrary, and that left
	 * the other 41 with no way in at all.
	 */
	private suspend fun found(host: String, link: String): AddSiteStatus {
		val unavailable = AddSiteStatus.NotFound(host, sources.parsersVersion)
		val resolved = sources.resolveLink(link) ?: return unavailable
		resolvedLink = resolved
		val upstream = sources.descriptor(resolved.sourceName) ?: return unavailable
		val candidates = (listOf(upstream) + resolved.alternatives.mapNotNull(sources::descriptor))
			.distinctBy { it.name }
			.sortedBy { it.title.lowercase() }
		val preferred = preferredSource(candidates, upstream.name, systemLanguage) ?: return unavailable
		val manga = when {
			resolved.manga == null -> null
			preferred.name == upstream.name -> resolved.manga
			// The default landed somewhere upstream did not, so the title has to be asked of that
			// source -- otherwise Open manga opens it in the language just passed over.
			else -> sources.resolveLinkAs(link, preferred.name)?.manga
		}
		return AddSiteStatus.Found(host, preferred, manga, candidates)
	}

	/**
	 * Choose a different one of the sources serving this site.
	 *
	 * The selection moves at once and the list stays put, because it is a list the user is clicking
	 * down: replacing it with a spinner would take the rows out from under the cursor. Only the
	 * title line is uncertain for a moment, and it says so through
	 * [AddSiteStatus.Found.reresolving].
	 */
	fun chooseSource(name: String) {
		val open = _addSite.value as? AddSiteState.Open ?: return
		val found = open.status as? AddSiteStatus.Found ?: return
		if (name == found.source.name) return
		val picked = found.candidates.firstOrNull { it.name == name } ?: return
		val link = SiteLinks.normalise(open.input)
		// A link that named the site as a whole has no title to carry across, so there is nothing
		// to ask and no reason to touch the network.
		if (resolvedLink?.manga == null || link == null) {
			_addSite.value = open.copy(status = found.copy(source = picked, reresolving = false))
			return
		}
		resolving?.cancel()
		_addSite.value = open.copy(status = found.copy(source = picked, reresolving = true))
		resolving = scope.launch {
			val manga = try {
				withTimeoutOrNull(ADD_SITE_TIMEOUT_MS) { sources.resolveLinkAs(link, picked.name) }?.manga
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				// Offering the site is the right way to be wrong here. Landing on a front page is a
				// small annoyance; opening a title in a language the user just declined is not.
				null
			}
			_addSite.update { current ->
				val status = (current as? AddSiteState.Open)?.status as? AddSiteStatus.Found
				// Applied only if this is still the source on screen. Cancellation covers the
				// ordinary case; this covers a second click landing while the first is in flight.
				if (current is AddSiteState.Open && status?.source?.name == picked.name) {
					current.copy(status = status.copy(manga = manga, reresolving = false))
				} else {
					current
				}
			}
		}
	}

	/**
	 * Switch on every source serving this site, and open the chosen one.
	 *
	 * For the reader who wants a site in three languages and would otherwise paste the same link
	 * three times. Additive and individually reversible, like [enableDefaults], so it asks nothing
	 * first.
	 *
	 * @return what was found, for the caller to navigate to; null when nothing was.
	 */
	fun enableAll(): AddSiteStatus.Found? {
		val found = (_addSite.value as? AddSiteState.Open)?.status as? AddSiteStatus.Found ?: return null
		found.candidates.forEach { setEnabled(it.name, true) }
		closeAddSite()
		return found
	}

	/** Distinguishes "the lookup reached an answer" from "the lookup never finished". */
	private class Answer(val status: AddSiteStatus)

	/**
	 * Turn on the default English sources.
	 *
	 * No confirmation, because there is nothing here to be sorry about: it only ever switches
	 * sources *on*, the list it changes is the one on screen, and every row it touches has its own
	 * switch to undo it. A dialog guarding an additive, visible, reversible action is a dialog
	 * people learn to dismiss without reading.
	 */
	fun enableDefaults() {
		scope.launch { sources.enableDefaults() }
	}

	fun markUsed(name: String) {
		scope.launch { sources.markUsed(name) }
	}

	/** Hands the current filters to whoever is persisting them. */
	private fun publish() {
		val f = filters.value
		onFiltersChanged(f.hideBroken, f.showAdult, f.locale)
	}

	private companion object {
		/** Pinned first, then most recently used, then alphabetical. */
		val SOURCE_ORDER = compareByDescending<SourceListing> { it.isPinned }
			.thenByDescending { it.lastUsedAt }
			.thenBy { it.title.lowercase() }

		const val STOP_TIMEOUT_MS = 5_000L
	}
}
