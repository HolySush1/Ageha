package app.ageha.core.data

import app.ageha.core.model.AgehaVersion
import app.ageha.core.database.dao.SourcesDao
import app.ageha.core.database.entity.MangaSourceEntity
import app.ageha.core.model.AgehaContentType
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.source.MangaSourceRegistry
import app.ageha.core.source.ResolvedLink
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A source as the UI needs to see it: what the parsers library says about it, plus what the user
 * has decided about it.
 */
data class SourceListing(
	val descriptor: SourceDescriptor,
	val isEnabled: Boolean,
    val isPinned: Boolean,
	val lastUsedAt: Long,
) {
	val name: String get() = descriptor.name
	val title: String get() = descriptor.title
}

/**
 * The source catalogue.
 *
 * Two halves that have to be joined by *name string*: the parsers library owns which sources
 * exist, and the database owns which ones this user has turned on. The join key is deliberately
 * the name and never an ordinal -- `MangaParserSource` is generated per parsers build by KSP, so
 * its constants and their order differ between builds (docs/ARCHITECTURE.md 3). A source list
 * persisted by ordinal would silently point at different sources after an update.
 */
class SourceRepository(
	private val sources: SourcesDao,
	private val registry: MangaSourceRegistry,
) {

	/** Every source the loaded parsers build offers. Around 1360 of them. */
	fun allDescriptors(): List<SourceDescriptor> = registry.availableSources()

	fun descriptor(name: String): SourceDescriptor? = registry.descriptorFor(name)

	val parsersVersion: String get() = registry.parsersVersion

	/**
	 * Every source, with the user's settings folded in.
	 *
	 * A source with no row yet is reported as **disabled**. That is the correct default for a
	 * catalogue of 1360: enabling everything would make the explore screen unusable and would
	 * have Ageha querying sites the user never asked for.
	 */
	fun observeAll(): Flow<List<SourceListing>> = sources.observeAll().map { rows ->
		val byName = rows.associateBy { it.source }
		registry.availableSources().map { descriptor ->
			val row = byName[descriptor.name]
			SourceListing(
				descriptor = descriptor,
				isEnabled = row?.isEnabled ?: false,
				isPinned = row?.isPinned ?: false,
				lastUsedAt = row?.lastUsedAt ?: 0L,
			)
		}
	}

	/** Only the sources the user turned on, in their chosen order. */
	fun observeEnabled(): Flow<List<SourceListing>> = observeAll().map { all ->
		all.filter { it.isEnabled }.sortedWith(
			compareByDescending<SourceListing> { it.isPinned }.thenByDescending { it.lastUsedAt },
		)
	}

	/**
	 * Which source handles [url] -- already normalised by `SiteLinks` -- and the manga it names.
	 *
	 * Null when no source in the loaded parsers build handles the site. A pass-through, and kept
	 * here rather than called on the registry from the screen so that the view model depends on
	 * one repository for everything about sources, as it already does for enabling them.
	 */
	suspend fun resolveLink(url: String): ResolvedLink? = registry.resolveLink(url)

	/**
	 * The same question asked of one particular source, for when the user picked a source other
	 * than the one the library's resolver named.
	 *
	 * Null when [sourceName] cannot read the link, which the caller shows as the site rather than
	 * the title.
	 */
	suspend fun resolveLinkAs(url: String, sourceName: String): ResolvedLink? =
		registry.resolveLinkAs(url, sourceName)

	/** Let the registry prepare for a lookup, so the Add site dialog's first Find is not the slow one. */
	fun warmLinkIndex() = registry.warmLinkIndex()

	suspend fun setEnabled(name: String, enabled: Boolean) {
		val existing = sources.find(name)
		if (existing == null) {
			sources.upsert(
				MangaSourceEntity(
					source = name,
					isEnabled = enabled,
					sortKey = sources.nextSortKey(),
					// The release that first saw this source, so a later one can offer "sources
					// added since you last looked". Rows written before Ageha had a version
					// carry 0, which reads as "unknown" rather than as release zero.
					addedIn = AgehaVersion.CODE,
					lastUsedAt = 0,
					isPinned = false,
					cfState = 0,
				),
			)
		} else {
			sources.setEnabled(name, enabled)
		}
	}

	/**
	 * The default sources that are not currently on.
	 *
	 * What the Explore button counts, so the button can say how many it would turn on and go quiet
	 * once there are none. Observed rather than fetched, so pressing the button empties it without
	 * anything having to remember to refresh.
	 */
	fun observeDefaultsOff(): Flow<List<SourceDescriptor>> = sources.observeAll().map { rows ->
		val on = rows.filter { it.isEnabled }.mapTo(mutableSetOf()) { it.source }
		DefaultSources.from(registry.availableSources()).filterNot { it.name in on }
	}

	/**
	 * Turn on every default source that is currently off.
	 *
	 * Explicitly an *action*, not a policy: something pressed a button or typed a command asking
	 * for the default set, so a default that was switched off earlier is switched back on. That is
	 * what "default English sources" means to the person pressing it, and the alternative -- a
	 * button that silently skips the sources you once turned off -- is a button whose result
	 * nobody can predict.
	 *
	 * It never turns anything **off**. A source outside the default set keeps whatever it has,
	 * whether that is a language you added or an 18+ source you chose; this only ever adds. So the
	 * worst it can do is give you back sources you can turn off again, one switch each.
	 *
	 * Returns the names newly enabled, for the caller to report. Empty means everything in the
	 * default set was already on.
	 *
	 * @see DefaultSources for what qualifies, and why it is computed rather than listed.
	 */
	suspend fun enableDefaults(): List<String> {
		val rows = sources.all().associateBy { it.source }
		val off = DefaultSources.from(registry.availableSources())
			.filterNot { rows[it.name]?.isEnabled == true }
		if (off.isEmpty()) return emptyList()

		var sortKey = sources.nextSortKey()
		sources.upsertAll(
			off.map { descriptor ->
				// An existing row is updated in place rather than replaced, so a source that was
				// pinned, or last opened last Tuesday, does not lose that by being re-enabled.
				rows[descriptor.name]?.copy(isEnabled = true) ?: MangaSourceEntity(
					source = descriptor.name,
					isEnabled = true,
					sortKey = sortKey++,
					addedIn = AgehaVersion.CODE,
					lastUsedAt = 0,
					isPinned = false,
					cfState = 0,
				)
			},
		)
		return off.map { it.name }
	}

	/**
	 * Seed the defaults, but only on a first run.
	 *
	 * Guarded on the table being *completely* empty, which is the difference between this and
	 * [enableDefaults]. Nobody asked for this one -- it happens during startup, before any window
	 * is on screen -- so it may only act where there is no decision to overrule. Running it at
	 * every launch would switch on each new English source a parsers update introduced, quietly
	 * rearranging a list under the person who curated it.
	 */
	suspend fun seedDefaultsOnFirstRun(): List<String> =
		if (sources.all().isEmpty()) enableDefaults() else emptyList()

	/** Records that the user actually browsed this source, so it can float up the list. */
	suspend fun markUsed(name: String, now: Long = System.currentTimeMillis()) {
		if (sources.find(name) == null) setEnabled(name, enabled = true)
		sources.markUsed(name, now)
	}

	/**
	 * Filter the catalogue for the source picker.
	 *
	 * Substring rather than fuzzy matching. With 1360 entries a fuzzy match returns something for
	 * every query, which sounds helpful and means the user cannot tell "no such source" from "you
	 * typed it wrong".
	 */
	fun search(
		listings: List<SourceListing>,
		query: String,
		locale: String? = null,
		contentType: AgehaContentType? = null,
		/**
		 * Whether adult sources are included. Off by default so that every caller which has not
		 * thought about it gets the safe answer -- the failure mode of the opposite default is a
		 * screen full of pornography on somebody's work laptop.
		 */
		includeAdult: Boolean = false,
		/** Whether sources upstream has flagged broken are included. */
		includeBroken: Boolean = true,
	): List<SourceListing> {
		val needle = query.trim().lowercase()
		return listings.filter { listing ->
			val descriptor = listing.descriptor
			(needle.isEmpty() || descriptor.title.lowercase().contains(needle) ||
				descriptor.name.lowercase().contains(needle)) &&
				(locale == null || descriptor.locale == locale) &&
				(contentType == null || descriptor.contentType == contentType) &&
				(includeAdult || !descriptor.isAdult) &&
				(includeBroken || !descriptor.isBroken)
		}
	}

	/**
	 * The languages present in [listings], with how many sources each has.
	 *
	 * Takes the list rather than reading the whole catalogue so the counts describe what the user
	 * can actually reach: with adult sources hidden, offering "Japanese (94)" and then showing
	 * eleven of them is a menu that lies. Callers pass the set *before* locale filtering, since a
	 * language menu filtered by the selected language would only ever offer one entry.
	 *
	 * Display names come from the JDK rather than a table of our own. `Locale.forLanguageTag`
	 * returns a locale with an empty display language for a tag it does not recognise, and upstream
	 * tags are not guaranteed to be well formed, so an unrecognised tag falls back to showing the
	 * tag itself rather than a blank row.
	 */
	fun availableLocales(listings: List<SourceListing>): List<LocaleOption> = listings
		.mapNotNull { it.descriptor.locale }
		.groupingBy { it }
		.eachCount()
		.map { (tag, count) -> LocaleOption(tag = tag, displayName = displayLanguage(tag), count = count) }
		.sortedBy { it.displayName.lowercase() }

	private fun displayLanguage(tag: String): String =
		Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifEmpty { tag.uppercase() }
}

/** One language the catalogue offers, as the picker's menu needs to show it. */
data class LocaleOption(
	/** The upstream tag. The only part that is persisted or compared; the rest is presentation. */
	val tag: String,
	val displayName: String,
	val count: Int,
)
