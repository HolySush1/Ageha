package app.ageha.feature.explore

import app.ageha.core.data.LocaleOption
import app.ageha.core.data.SourceListing
import app.ageha.core.data.SourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
class ExploreViewModel(
	private val sources: SourceRepository,
	private val scope: CoroutineScope,
	hideBroken: Boolean = true,
	showAdult: Boolean = false,
	locale: String? = null,
	private val onFiltersChanged: (hideBroken: Boolean, showAdult: Boolean, locale: String?) -> Unit =
		{ _, _, _ -> },
) {

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
