package app.ageha.feature.explore

import app.ageha.core.data.LocaleOption
import app.ageha.core.data.SourceListing
import app.ageha.core.data.SourceRepository
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

class ExploreViewModel(
	private val sources: SourceRepository,
	private val scope: CoroutineScope,
	hideBroken: Boolean = true,
	showAdult: Boolean = false,
	locale: String? = null,
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

	fun openAddSite() {
		_addSite.value = AddSiteState.Open()
	}

	fun closeAddSite() {
		resolving?.cancel()
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
				val answer = withTimeoutOrNull(ADD_SITE_TIMEOUT_MS) { Answer(sources.resolveLink(link)) }
				val resolved = answer?.link
				when {
					answer == null -> AddSiteStatus.Failed(
						host,
						"it took longer than ${ADD_SITE_TIMEOUT_MS / 1000} seconds to answer.",
					)

					resolved == null -> AddSiteStatus.NotFound(host, sources.parsersVersion)

					else -> sources.descriptor(resolved.sourceName)
						?.let { AddSiteStatus.Found(host, it, resolved.manga) }
						?: AddSiteStatus.NotFound(host, sources.parsersVersion)
				}
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

	/** Distinguishes "the resolver answered null" from "the resolver never answered". */
	private class Answer(val link: ResolvedLink?)

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
