package app.ageha.core.sync

import app.ageha.core.database.AgehaDatabase
import app.ageha.core.database.dao.RestorePayload
import app.ageha.core.database.entity.MangaEntity
import app.ageha.core.database.entity.MangaTagsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

/**
 * Two-way sync against a kotatsu-syncserver.
 *
 * ## Why this exists at all, given tracking does not
 *
 * The brief cut Shikimori, AniList and friends because each needs an OAuth client id and secret
 * *registered by this project's owner* -- credentials that cannot be invented, cannot be committed
 * to a public GPL repository and cannot be tested without being real. Sync is not that. It needs a
 * server address and an account, both supplied by the user, and the client can be driven end to
 * end against a server that speaks the documented protocol. The distinction is not "one is network
 * and one is not"; it is whether the thing that cannot be tested is a credential this project
 * would have to hold.
 *
 * The brief asked whether the protocol was Android-coupled. It is not: it is four POSTs of JSON
 * over OkHttp. What *is* Android-coupled is the whole apparatus around it -- `AccountManager` for
 * credentials, `ContentProviderClient` for the database, `AbstractThreadedSyncAdapter` for
 * scheduling -- none of which is protocol. `docs/ARCHITECTURE.md` 7d records the evidence.
 *
 * ## The exchange
 *
 * Per resource, and the protocol is not incremental: **the client sends everything it has and the
 * server answers with the merged result.** Sending only what changed since a watermark would be a
 * different protocol, and the server would not understand it.
 *
 *  1. Read every local row, tombstones included ([app.ageha.core.database.dao.SyncDao]).
 *  2. POST it with a timestamp.
 *  3. If the server sends a payload back, upsert all of it -- rows it invented, rows it merged and
 *     rows it is telling us were deleted elsewhere, which arrive as tombstones.
 *  4. Only then collect tombstones older than the retention window.
 *
 * Step 4 comes last for a reason worth stating: a tombstone the server has not seen is a deletion
 * that has not propagated, and collecting it early does not delete the row -- it deletes the
 * *deletion*, and the next sync from any other device restores the entry the user removed.
 *
 * ## Ordering, and the foreign keys
 *
 * Everything is written through [RestorePayload], the same single-transaction path a backup import
 * uses, and for the same reason: manga rows must exist before the history and favourites that
 * point at them, and categories before favourites. A favourite arriving for a category this device
 * has never seen is dropped and reported rather than allowed to abort the transaction -- the same
 * trade the importer makes, because a sync that fails wholesale over one row is a sync that never
 * completes again.
 */
class SyncEngine(
	private val database: AgehaDatabase,
	private val api: SyncApi,
	private val accounts: SyncAccountStore,
	private val appVersion: Int = APP_VERSION,
	private val databaseVersion: Int = app.ageha.core.database.AGEHA_DATABASE_VERSION,
	private val now: () -> Long = System::currentTimeMillis,
) {

	/**
	 * Sync both resources.
	 *
	 * Favourites first, then history, matching no particular requirement of the server -- it is
	 * simply the order in which a failure is least confusing, because favourites carry the
	 * categories that history's manga rows may end up filed under.
	 */
	suspend fun sync(): SyncOutcome = withContext(Dispatchers.IO) {
		val account = accounts.load()
			?: return@withContext SyncOutcome.NotConfigured

		try {
			var token = account.token ?: authenticate(account)
			val report = SyncReport()
			for (resource in SyncResource.entries) {
				token = exchange(account, resource, token, report)
			}
			collectTombstones(report)
			SyncOutcome.Success(report)
		} catch (e: SyncApiException) {
			SyncOutcome.Failed(e.message.orEmpty(), e.code)
		} catch (e: java.io.IOException) {
			// The server being unreachable is an ordinary event for a self-hosted service on a
			// laptop that moves between networks. It is not an error worth a stack trace.
			SyncOutcome.Failed(e.message ?: "The sync server could not be reached.", code = null)
		}
	}

	/**
	 * One resource, with a single retry on 401.
	 *
	 * The token expires, and the Android client handles that with an OkHttp `Authenticator` that
	 * silently re-authenticates. This does the same thing explicitly, because an `Authenticator`
	 * is a blocking callback on a connection thread and re-entering suspending code from one is a
	 * deadlock waiting for a slow server. One retry, not a loop: if a fresh token is also rejected
	 * the credentials are wrong, and retrying just locks the account out.
	 */
	private suspend fun exchange(
		account: SyncAccount,
		resource: SyncResource,
		token: String,
		report: SyncReport,
	): String {
		val payload = buildPayload(resource)
		return try {
			apply(resource, api.exchange(account.syncUrl, resource, token, payload, appVersion, databaseVersion), report)
			token
		} catch (e: SyncApiException) {
			if (e.code != HttpURLConnection.HTTP_UNAUTHORIZED) throw e
			val fresh = authenticate(account)
			apply(resource, api.exchange(account.syncUrl, resource, fresh, payload, appVersion, databaseVersion), report)
			fresh
		}
	}

	private suspend fun authenticate(account: SyncAccount): String {
		val password = account.password
		if (password.isNullOrEmpty()) {
			// Deliberately a protocol-shaped failure rather than a special case: the caller
			// already knows how to show "the server rejected this", and the remedy is the same
			// screen either way.
			throw SyncApiException(
				"Ageha needs the account password to sign in again. Open Settings > Sync and re-enter it.",
				HttpURLConnection.HTTP_UNAUTHORIZED,
			)
		}
		val token = api.authenticate(account.syncUrl, account.email, password)
		accounts.save(account.copy(token = token))
		return token
	}

	/** Everything this device holds for one resource, tombstones included. */
	private suspend fun buildPayload(resource: SyncResource): SyncDto {
		val dao = database.syncDao()
		return when (resource) {
			SyncResource.HISTORY -> {
				val history = dao.allHistory()
				val manga = mangaOf(history.map { it.mangaId })
				SyncDto(
					// A history row whose manga row is missing cannot be represented, since the
					// payload embeds the manga. The foreign key makes it unreachable; skipping is
					// still better than inventing one.
					history = history.mapNotNull { row -> manga[row.mangaId]?.let { row.toSyncDto(it) } },
					timestamp = now(),
				)
			}

			SyncResource.FAVOURITES -> {
				val favourites = dao.allFavourites()
				val manga = mangaOf(favourites.map { it.mangaId })
				SyncDto(
					favourites = favourites.mapNotNull { row -> manga[row.mangaId]?.let { row.toSyncDto(it) } },
					categories = dao.allCategories().map { it.toSyncDto() },
					timestamp = now(),
				)
			}
		}
	}

	/**
	 * Manga records with their tags, two queries for the whole set.
	 *
	 * Reuses [app.ageha.core.database.dao.ExportDao]'s bulk reads rather than adding a second pair
	 * of identical queries to [app.ageha.core.database.dao.SyncDao]. The two features want the
	 * same rows here -- it is only the *tombstoned* rows they disagree about, and manga are never
	 * tombstoned.
	 */
	private suspend fun mangaOf(ids: List<Long>): Map<Long, MangaSyncDto> {
		val distinct = ids.distinct()
		if (distinct.isEmpty()) return emptyMap()
		val dao = database.exportDao()
		val tags = dao.tagsByMangaIds(distinct).groupBy({ it.mangaId }, { it.tag.toSyncDto() })
		return dao.mangaByIds(distinct).associateBy(MangaEntity::id) { entity ->
			entity.toSyncDto(tags[entity.id].orEmpty().toSet())
		}
	}

	/** Write back whatever the server sent, in foreign-key order, in one transaction. */
	private suspend fun apply(resource: SyncResource, answer: SyncDto?, report: SyncReport) {
		if (answer == null) {
			// 204: nothing to merge. Not an empty payload -- see SyncApi.
			return
		}
		val manga = LinkedHashMap<Long, MangaSyncDto>()
		val history = answer.history.orEmpty().onEach { manga[it.manga.id] = it.manga }
		val categories = answer.categories.orEmpty()
		val knownCategories = categories.mapTo(mutableSetOf()) { it.categoryId }

		// A category the *server* knows about may already exist here from an earlier sync, so the
		// payload's own list is not the only source of truth for what is referenceable.
		database.favouritesDao().categories().mapTo(knownCategories) { it.categoryId }

		val favourites = answer.favourites.orEmpty().filter { favourite ->
			if (favourite.categoryId in knownCategories) {
				true
			} else {
				report.dropped += "favourite for manga " + favourite.mangaId + " refers to category " +
					favourite.categoryId + ", which neither this device nor the payload defines"
				false
			}
		}.onEach { manga[it.manga.id] = it.manga }

		val tags = LinkedHashMap<Long, MangaTagSyncDto>()
		val tagLinks = LinkedHashSet<MangaTagsEntity>()
		manga.values.forEach { entry ->
			entry.tags.forEach { tag ->
				tags[tag.id] = tag
				tagLinks += MangaTagsEntity(entry.id, tag.id)
			}
		}

		database.restoreDao().restore(
			RestorePayload(
				categories = categories.map { it.toEntity() },
				manga = manga.values.map { it.toEntity() },
				tags = tags.values.map { it.toEntity() },
				tagLinks = tagLinks.toList(),
				history = history.map { it.toEntity() },
				favourites = favourites.map { it.toEntity() },
			),
		)

		when (resource) {
			SyncResource.HISTORY -> report.historyReceived += history.size
			SyncResource.FAVOURITES -> {
				report.favouritesReceived += favourites.size
				report.categoriesReceived += categories.size
			}
		}
	}

	/**
	 * Drop tombstones the server has already seen and that are older than the retention window.
	 *
	 * Runs only after every resource has been exchanged successfully. The window is the Android
	 * app's four days, and matching it is the point: two clients collecting on different schedules
	 * is exactly how a deletion comes back.
	 */
	private suspend fun collectTombstones(report: SyncReport) {
		val before = now() - TOMBSTONE_RETENTION_MS
		val dao = database.syncDao()
		report.tombstonesCollected += dao.purgeHistoryTombstones(before)
		report.tombstonesCollected += dao.purgeFavouriteTombstones(before)
		report.tombstonesCollected += dao.purgeCategoryTombstones(before)
	}

	companion object {

		/**
		 * How long a deletion is remembered after it has been sent.
		 *
		 * Four days, from the Android app. Long enough for a device that was switched off over a
		 * weekend to learn about the deletion; short enough that the payload does not grow without
		 * bound, since the protocol re-sends everything on every sync.
		 */
		val TOMBSTONE_RETENTION_MS: Long = TimeUnit.DAYS.toMillis(4)

		/** Sent as `X-App-Version`. Ageha's, not the Android app's. */
		const val APP_VERSION = 1
	}
}

/** What one sync did. */
data class SyncReport(
	var historyReceived: Int = 0,
	var favouritesReceived: Int = 0,
	var categoriesReceived: Int = 0,
	var tombstonesCollected: Int = 0,
	val dropped: MutableList<String> = mutableListOf(),
)

/**
 * The result of a sync.
 *
 * A sealed outcome rather than an exception, for the same reason `CatalogResult` is: a sync server
 * being unreachable is an ordinary event on a laptop that changes networks, and a screen should
 * render it, not unwind over it.
 */
sealed interface SyncOutcome {

	/** No account is set up. Not a failure -- most installations will never configure one. */
	data object NotConfigured : SyncOutcome

	data class Success(val report: SyncReport) : SyncOutcome

	/** [code] is the HTTP status where there was one, null for a transport failure. */
	data class Failed(val reason: String, val code: Int?) : SyncOutcome

	fun describe(): String = when (this) {
		NotConfigured -> "No sync account is set up."
		is Failed -> if (code != null) "Sync failed (" + code + "): " + reason else "Sync failed: " + reason
		is Success -> buildString {
			append("Received ")
			append(report.historyReceived)
			append(" history, ")
			append(report.favouritesReceived)
			append(" favourite(s), ")
			append(report.categoriesReceived)
			append(" category(ies).")
			if (report.tombstonesCollected > 0) {
				append(" Collected ")
				append(report.tombstonesCollected)
				append(" expired deletion(s).")
			}
			if (report.dropped.isNotEmpty()) {
				appendLine()
				appendLine()
				appendLine("Skipped " + report.dropped.size + " row(s):")
				report.dropped.take(10).forEach { appendLine("  " + it) }
			}
		}.trimEnd()
	}
}
