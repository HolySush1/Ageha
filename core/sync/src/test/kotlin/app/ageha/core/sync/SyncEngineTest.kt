package app.ageha.core.sync

import app.ageha.core.database.AgehaDatabase
import app.ageha.core.database.AgehaDatabaseFactory
import app.ageha.core.database.dao.RestorePayload
import app.ageha.core.database.entity.FavouriteCategoryEntity
import app.ageha.core.database.entity.FavouriteEntity
import app.ageha.core.database.entity.HistoryEntity
import app.ageha.core.database.entity.MangaEntity
import app.ageha.core.database.entity.MangaTagsEntity
import app.ageha.core.database.entity.TagEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Syncing against a server that speaks the protocol.
 *
 * Driven through a real socket ([FakeSyncServer]) rather than a stubbed transport, so what is
 * under test is the payload OkHttp actually puts on the wire and the response the client actually
 * reads back. The assertions are mostly about the payload, because the payload is the half this
 * project can get wrong on its own -- the server is somebody else's program and no amount of
 * mocking makes a wrong field name right.
 */
class SyncEngineTest {

	private lateinit var db: AgehaDatabase
	private lateinit var server: FakeSyncServer
	private lateinit var http: OkHttpClient

	private val json = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true }

	@BeforeEach
	fun open() {
		db = AgehaDatabaseFactory.openInMemory()
		server = FakeSyncServer()
		http = OkHttpClient.Builder()
			.callTimeout(10, TimeUnit.SECONDS)
			.build()
	}

	@AfterEach
	fun close() {
		server.close()
		db.close()
		http.dispatcher.executorService.shutdown()
		http.connectionPool.evictAll()
	}

	private fun engine(
		dir: File,
		account: SyncAccount,
		now: Long = 1_000_000_000_000L,
	): Pair<SyncEngine, SyncAccountStore> {
		val store = SyncAccountStore(File(dir, "sync.json"))
		store.save(account)
		return SyncEngine(db, SyncApi(http), store, now = { now }) to store
	}

	@Test
	@DisplayName("with no account configured, sync is a no-op rather than a failure")
	fun notConfigured(@TempDir dir: File) = runBlocking {
		// Most installations will never set one up. That must not read as an error.
		val engine = SyncEngine(db, SyncApi(http), SyncAccountStore(File(dir, "sync.json")))

		assertEquals(SyncOutcome.NotConfigured, engine.sync())
		assertTrue(server.requests.isEmpty(), "and must not touch the network")
	}

	@Test
	@DisplayName("the payload carries tombstones, which a backup deliberately does not")
	fun payloadCarriesTombstones(@TempDir dir: File) = runBlocking {
		// The whole reason `deleted_at` is in the schema. A sync payload that omitted deletions
		// would have every other device push the deleted rows straight back on the next round.
		populate()
		db.historyDao().markDeleted(100L, now = 900_000_000_000L)
		server.on("/resource/history", 204, "")
		server.on("/resource/favourites", 204, "")
		val (engine, _) = engine(dir, account(token = "t"))

		engine.sync()

		val sent = json.decodeFromString<SyncDto>(server.requestsTo("/resource/history").single().body)
		assertEquals(2, sent.history?.size, "the deleted row is still sent")
		val deleted = sent.history?.single { it.mangaId == 100L }
		assertEquals(900_000_000_000L, deleted?.deletedAt, "and it is sent as a tombstone")
		assertEquals(0L, sent.history?.single { it.mangaId == 200L }?.deletedAt)
	}

	@Test
	@DisplayName("the payload is the wire format, field for field")
	fun payloadShape(@TempDir dir: File) = runBlocking {
		// Written against the Android client's DTOs, which are the only specification. A wrong
		// key here is a field the server silently ignores.
		populate()
		server.on("/resource/history", 204, "")
		server.on("/resource/favourites", 204, "")
		val (engine, _) = engine(dir, account(token = "t"))

		engine.sync()

		val history = server.requestsTo("/resource/history").single()
		assertEquals("Bearer t", history.header("Authorization"))
		assertNotNull(history.header("X-App-Version"))
		assertNotNull(history.header("X-Db-Version"), "the server may refuse a schema it cannot reconcile")

		// `manga_id`, not `id` -- the sync format and the backup format disagree on this one key
		// and there is no way to discover it except by reading both.
		assertTrue(history.body.contains("\"manga_id\""), history.body.take(200))
		assertTrue(history.body.contains("\"deleted_at\""))
		assertTrue(history.body.contains("\"timestamp\""))

		val favourites = server.requestsTo("/resource/favourites").single()
		val sent = json.decodeFromString<SyncDto>(favourites.body)
		assertEquals(1, sent.favourites?.size)
		assertEquals(1, sent.categories?.size)
		assertNull(sent.history, "favourites and history are separate exchanges")
		assertEquals("Reading", sent.categories?.single()?.title)
		assertEquals(11L, sent.favourites?.single()?.manga?.tags?.map { it.id }?.single())
	}

	@Test
	@DisplayName("what the server sends back is merged, tombstones included")
	fun mergesServerPayload(@TempDir dir: File) = runBlocking {
		// The other half: a row deleted on another device arrives here as a tombstone, and must
		// take effect rather than being treated as an ordinary entry.
		populate()
		val answer = SyncDto(
			history = listOf(
				historyDto(mangaId = 300L, title = "From another device", deletedAt = 0L),
				// This one was deleted elsewhere. It must not appear in the reading history.
				historyDto(mangaId = 200L, title = "Berserk", deletedAt = 950_000_000_000L),
			),
			timestamp = 1L,
		)
		server.on("/resource/history", 200, json.encodeToString(answer))
		server.on("/resource/favourites", 204, "")
		val (engine, _) = engine(dir, account(token = "t"))

		val outcome = engine.sync()

		assertTrue(outcome is SyncOutcome.Success, outcome.describe())
		assertEquals("From another device", db.mangaDao().find(300L)?.title, "a new manga arrived")
		assertNotNull(db.historyDao().find(300L))
		assertNull(db.historyDao().find(200L), "a deletion from elsewhere took effect")
		// The tombstone is still on the row, not gone: it has to be re-sent until it expires.
		assertEquals(2, db.historyDao().countIncludingDeleted())
	}

	@Test
	@DisplayName("204 means nothing to merge, and is not an empty payload")
	fun noContentIsNotEmpty(@TempDir dir: File) = runBlocking {
		populate()
		server.on("/resource/history", 204, "")
		server.on("/resource/favourites", 204, "")
		val (engine, _) = engine(dir, account(token = "t"))

		val outcome = engine.sync()

		assertTrue(outcome is SyncOutcome.Success, outcome.describe())
		// Untouched: a 204 applied as an empty payload would be indistinguishable here today,
		// which is exactly why the distinction is worth pinning before it stops being.
		assertNotNull(db.historyDao().find(100L))
		assertNotNull(db.historyDao().find(200L))
	}

	@Test
	@DisplayName("an expired token is refreshed once, and the retry carries the new one")
	fun refreshesExpiredToken(@TempDir dir: File) = runBlocking {
		// The token expires and the server answers 401. The Android client re-authenticates
		// through an OkHttp Authenticator; this does it explicitly, and the point of the test is
		// that the *retry* carries the fresh token rather than repeating the stale one.
		populate()
		server.on("/auth", 200, """{"token":"fresh-token"}""")
		server.on("/resource/history") { request ->
			if (request.header("Authorization") == "Bearer stale") {
				FakeSyncServer.Canned(401, "\"token expired\"")
			} else {
				FakeSyncServer.Canned(204, "")
			}
		}
		server.on("/resource/favourites", 204, "")
		val (engine, store) = engine(dir, account(token = "stale", password = "hunter2"))

		val outcome = engine.sync()

		assertTrue(outcome is SyncOutcome.Success, outcome.describe())
		val attempts = server.requestsTo("/resource/history")
		assertEquals(2, attempts.size, "one rejected, one retried")
		assertEquals("Bearer stale", attempts[0].header("Authorization"))
		assertEquals("Bearer fresh-token", attempts[1].header("Authorization"))
		assertEquals("fresh-token", store.load()?.token, "and the fresh token is kept")

		val auth = server.requestsTo("/auth").single()
		assertTrue(auth.body.contains("\"email\""), auth.body)
		assertTrue(auth.body.contains("hunter2"), "the password is what refreshes the token")
	}

	@Test
	@DisplayName("without a stored password an expired token asks the user, rather than looping")
	fun expiredTokenWithoutPassword(@TempDir dir: File) = runBlocking {
		populate()
		server.on("/resource/history", 401, "\"token expired\"")
		server.on("/resource/favourites", 401, "\"token expired\"")
		val (engine, _) = engine(dir, account(token = "stale", password = null))

		val outcome = engine.sync()

		val failed = outcome as SyncOutcome.Failed
		assertEquals(401, failed.code)
		assertTrue(failed.reason.contains("Settings"), failed.reason)
		assertTrue(server.requestsTo("/auth").isEmpty(), "nothing to authenticate with")
	}

	@Test
	@DisplayName("a rejected refresh fails rather than retrying into a lockout")
	fun refreshRejectedOnce(@TempDir dir: File) = runBlocking {
		populate()
		server.on("/auth", 200, """{"token":"also-bad"}""")
		server.on("/resource/history", 401, "\"nope\"")
		server.on("/resource/favourites", 204, "")
		val (engine, _) = engine(dir, account(token = "stale", password = "hunter2"))

		val outcome = engine.sync()

		assertTrue(outcome is SyncOutcome.Failed, outcome.describe())
		assertEquals(2, server.requestsTo("/resource/history").size, "one retry, not a loop")
	}

	@Test
	@DisplayName("an unreachable server is a rendered outcome, not an exception")
	fun unreachableServer(@TempDir dir: File) = runBlocking {
		// A self-hosted server on a laptop that changes networks is unreachable routinely.
		populate()
		server.close()
		val (engine, _) = engine(dir, account(token = "t"))

		val outcome = engine.sync()

		assertTrue(outcome is SyncOutcome.Failed, outcome.describe())
		assertNull((outcome as SyncOutcome.Failed).code, "a transport failure has no HTTP status")
	}

	@Test
	@DisplayName("expired tombstones are collected, recent ones are kept")
	fun tombstoneCollection(@TempDir dir: File) = runBlocking {
		// A tombstone must outlive the slowest device that might still hold the row, then go --
		// the protocol re-sends everything every time, so keeping them forever grows the payload
		// without bound.
		populate()
		val now = 1_000_000_000_000L
		val old = now - SyncEngine.TOMBSTONE_RETENTION_MS - 1
		val recent = now - 1000L
		db.historyDao().markDeleted(100L, now = old)
		db.historyDao().markDeleted(200L, now = recent)
		server.on("/resource/history", 204, "")
		server.on("/resource/favourites", 204, "")
		val (engine, _) = engine(dir, account(token = "t"), now = now)

		val outcome = engine.sync() as SyncOutcome.Success

		assertEquals(1, outcome.report.tombstonesCollected)
		assertEquals(1, db.historyDao().countIncludingDeleted(), "the recent tombstone survives")
		// And the expired one was still *sent* before being collected: collecting first would
		// delete the deletion, and the next sync from another device would restore the row.
		val sent = json.decodeFromString<SyncDto>(server.requestsTo("/resource/history").single().body)
		assertEquals(2, sent.history?.size)
	}

	@Test
	@DisplayName("a favourite for an unknown category is dropped and reported, not fatal")
	fun unknownCategoryIsDropped(@TempDir dir: File) = runBlocking {
		// The foreign key would abort the whole transaction. A sync that fails wholesale over one
		// row is a sync that never completes again.
		populate()
		val answer = SyncDto(
			favourites = listOf(favouriteDto(mangaId = 400L, categoryId = 99, title = "Orphan")),
			categories = emptyList(),
			timestamp = 1L,
		)
		server.on("/resource/history", 204, "")
		server.on("/resource/favourites", 200, json.encodeToString(answer))
		val (engine, _) = engine(dir, account(token = "t"))

		val outcome = engine.sync() as SyncOutcome.Success

		assertEquals(1, outcome.report.dropped.size)
		assertTrue(outcome.report.dropped.single().contains("99"), outcome.report.dropped.single())
		assertNull(db.mangaDao().find(400L), "and nothing half-written was left behind")
	}

	@Test
	@DisplayName("a reply Ageha cannot read is reported as such, not as a crash")
	fun malformedReply(@TempDir dir: File) = runBlocking {
		populate()
		server.on("/resource/history", 200, "<html>gateway timeout</html>")
		server.on("/resource/favourites", 204, "")
		val (engine, _) = engine(dir, account(token = "t"))

		val outcome = engine.sync()

		assertTrue(outcome is SyncOutcome.Failed, outcome.describe())
		assertTrue((outcome as SyncOutcome.Failed).reason.contains("could not read"), outcome.reason)
	}

	private fun account(token: String? = null, password: String? = "hunter2") = SyncAccount(
		syncUrl = server.baseUrl,
		email = "reader@example.org",
		token = token,
		password = password,
	)

	private fun historyDto(mangaId: Long, title: String, deletedAt: Long) = HistorySyncDto(
		mangaId = mangaId,
		createdAt = 1000L,
		updatedAt = 2000L,
		chapterId = 99L,
		page = 3,
		scroll = 0.25f,
		percent = 0.5f,
		deletedAt = deletedAt,
		chaptersCount = 10,
		manga = mangaDto(mangaId, title),
	)

	private fun favouriteDto(mangaId: Long, categoryId: Int, title: String) = FavouriteSyncDto(
		mangaId = mangaId,
		manga = mangaDto(mangaId, title),
		categoryId = categoryId,
		sortKey = 0,
		pinned = false,
		createdAt = 1500L,
		deletedAt = 0L,
	)

	private fun mangaDto(id: Long, title: String) = MangaSyncDto(
		id = id,
		title = title,
		url = "/manga/" + id,
		publicUrl = "https://mangadex.org/title/" + id,
		rating = -1f,
		coverUrl = "https://example.org/" + id + ".jpg",
		source = "MANGADEX",
	)

	private suspend fun populate() {
		db.restoreDao().restore(
			RestorePayload(
				categories = listOf(
					FavouriteCategoryEntity(1, 1000L, 0, "Reading", "NEWEST", track = true, isVisibleInLibrary = true, deletedAt = 0L),
				),
				manga = listOf(manga(100L, "Frieren"), manga(200L, "Berserk")),
				tags = listOf(TagEntity(11L, "Fantasy", "fantasy", "MANGADEX", isPinned = true)),
				tagLinks = listOf(MangaTagsEntity(100L, 11L)),
				history = listOf(history(100L), history(200L)),
				favourites = listOf(
					FavouriteEntity(100L, categoryId = 1, sortKey = 0, createdAt = 1500L, deletedAt = 0L, isPinned = false),
				),
			),
		)
	}

	private fun manga(id: Long, title: String) = MangaEntity(
		id = id,
		title = title,
		altTitles = null,
		url = "/manga/" + id,
		publicUrl = "https://mangadex.org/title/" + id,
		rating = -1f,
		isNsfw = false,
		contentRating = "SAFE",
		coverUrl = "https://example.org/" + id + ".jpg",
		largeCoverUrl = null,
		state = "ONGOING",
		authors = "Someone",
		source = "MANGADEX",
	)

	private fun history(mangaId: Long) = HistoryEntity(
		mangaId = mangaId,
		createdAt = 1000L,
		updatedAt = 2000L,
		chapterId = 99L,
		page = 17,
		pageCount = 24,
		scroll = 0.3456789f,
		percent = 0.35f,
		deletedAt = 0L,
		chaptersCount = 120,
	)
}
