package app.ageha.core.data

import app.ageha.core.database.dao.SourcesDao
import app.ageha.core.database.entity.MangaSourceEntity
import app.ageha.core.model.AgehaContentType
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.source.MangaSourceRegistry
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

	suspend fun setEnabled(name: String, enabled: Boolean) {
		val existing = sources.find(name)
		if (existing == null) {
			sources.upsert(
				MangaSourceEntity(
					source = name,
					isEnabled = enabled,
					sortKey = sources.nextSortKey(),
					// `added_in` records the app version that first saw this source, so a later
					// release can offer "sources added since you last looked". 0 until versioning
					// lands with packaging in milestone 9.
					addedIn = 0,
					lastUsedAt = 0,
					isPinned = false,
					cfState = 0,
				),
			)
		} else {
			sources.setEnabled(name, enabled)
		}
	}

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
	): List<SourceListing> {
		val needle = query.trim().lowercase()
		return listings.filter { listing ->
			val descriptor = listing.descriptor
			(needle.isEmpty() || descriptor.title.lowercase().contains(needle) ||
				descriptor.name.lowercase().contains(needle)) &&
				(locale == null || descriptor.locale == locale) &&
				(contentType == null || descriptor.contentType == contentType)
		}
	}

	/** The locales present in the catalogue, for the picker's filter. */
	fun availableLocales(): List<String> =
		registry.availableSources().mapNotNull { it.locale }.distinct().sorted()
}
