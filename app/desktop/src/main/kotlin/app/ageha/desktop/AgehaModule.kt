package app.ageha.desktop

import app.ageha.core.data.CatalogRepository
import app.ageha.core.data.LibraryRepository
import app.ageha.core.data.ReaderRepository
import app.ageha.core.data.SourceRepository
import app.ageha.core.database.AgehaDatabase
import app.ageha.core.database.AgehaDatabaseFactory
import app.ageha.core.image.AgehaImages
import app.ageha.core.js.JsRuntime
import app.ageha.core.js.RhinoJsRuntime
import app.ageha.core.network.AgehaPaths
import app.ageha.core.parsers.Ageha
import app.ageha.core.backup.BackupImporter
import app.ageha.core.data.ChapterDownloader
import app.ageha.core.parsers.ParsersUpdateService
import app.ageha.core.parsers.SourceStack
import app.ageha.core.source.MangaSourceRegistry
import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import java.io.File

/**
 * Everything the application owns, and the order it is torn down in.
 *
 * Koin rather than Hilt or Dagger, as the brief specifies: no annotation processing, no Android
 * coupling, and a graph this size does not need a compile-time container. The graph is declared
 * once here rather than being assembled in `main`, so the CLI and any future headless entry point
 * can reuse it.
 */
val agehaModule = module {
	single { PreferencesStore() }

	/*
	 * The JavaScript engine. Rhino, serving the PLAIN_SCRIPT tier.
	 *
	 * This is what makes the ~257 conditionally-JS sources work: they are ordinary sources until
	 * the site decides to serve an anti-bot interstitial, at which point the parser needs a script
	 * evaluated or it fails. Without a runtime those sources work most days and mysteriously do
	 * not on others. The remaining ~20 that need a real browser still refuse, with an actionable
	 * message rather than a generic error -- see FailureNotice.
	 */
	single<JsRuntime> { RhinoJsRuntime() }

	// One source stack for the process. It owns the OkHttp client, the cookie jar and the
	// classloader holding the parsers build, and it is the only thing allowed to close them --
	// see the single-owner note on SourceStack.
	single { Ageha.createSourceStack(jsRuntime = get()) }
	single<MangaSourceRegistry> { get<SourceStack>().registry }

	single { AgehaDatabaseFactory.open(File(AgehaPaths.dataDir, "ageha.db")) }

	// The image loader shares the source stack's HTTP client deliberately: one cookie jar, one
	// User-Agent, one connection pool. A source that sets a cookie while listing and then checks
	// for it while serving covers works only because these are the same client.
	single<ImageLoader> { AgehaImages.create(get<SourceStack>().httpClient, AgehaPaths.cacheDir) }

	single { LibraryRepository(get<AgehaDatabase>()) }
	single { SourceRepository(get<AgehaDatabase>().sourcesDao(), get<MangaSourceRegistry>()) }
	single { CatalogRepository(get<MangaSourceRegistry>()) }
	single {
		ParsersUpdateService(
			httpClient = get<SourceStack>().httpClient,
			installation = get<SourceStack>().installation,
			cookieJar = get<SourceStack>().cookieJar,
			jsRuntime = get(),
		)
	}
	single { BackupImporter(get<AgehaDatabase>()) }
	single {
		ChapterDownloader(
			catalog = get(),
			root = File(AgehaPaths.dataDir, "downloads"),
			// The downloader fetches images through the *source stack's* client, so a page request
			// carries the same cookies and User-Agent the chapter listing did. A separate client
			// would be a separate identity to the site, and several sources gate images on it.
			fetchImage = { url, headers ->
				val request = okhttp3.Request.Builder().url(url).apply {
					headers.forEach { (name, value) -> header(name, value) }
				}.build()
				val response = get<SourceStack>().httpClient.newCall(request).execute()
				if (response.isSuccessful) response.body.byteStream() else { response.close(); null }
			},
		)
	}
	single {
		ReaderRepository(
			catalog = get(),
			history = get<AgehaDatabase>().historyDao(),
			prefs = get<AgehaDatabase>().mangaPrefsDao(),
		)
	}
}

/**
 * The application's lifetime, as an object that can be closed.
 *
 * Startup order is load-bearing and so is shutdown order: the source stack must outlive anything
 * that might still be talking to it, and the scope must be cancelled before the stack it launched
 * work against goes away. Doing this in `main` with a `try`/`finally` was the alternative and it
 * put the ordering somewhere nobody would look for it.
 */
class AgehaApplication private constructor(
	val scope: CoroutineScope,
    private val koin: org.koin.core.Koin,
) {

	val preferencesStore: PreferencesStore get() = koin.get()
	val library: LibraryRepository get() = koin.get()
	val sources: SourceRepository get() = koin.get()
	val catalog: CatalogRepository get() = koin.get()
	val reader: ReaderRepository get() = koin.get()
	val imageLoader: ImageLoader get() = koin.get()
	val sourceStack: SourceStack get() = koin.get()
	val parsersUpdates: ParsersUpdateService get() = koin.get()
	val backupImporter: BackupImporter get() = koin.get()
	val downloader: ChapterDownloader get() = koin.get()
	val jsRuntime: app.ageha.core.js.JsRuntime get() = koin.get()
	val database: AgehaDatabase get() = koin.get()

	fun close() {
		// Cancel first: in-flight source calls are cancellable and cancelling them is what lets
		// the stack close without waiting on a request to somebody else's slow server.
		scope.cancel()
		runBlocking { runCatching { sourceStack.close() } }
		runCatching { database.close() }
		stopKoin()
	}

	companion object {
		fun start(declaration: KoinAppDeclaration = {}): AgehaApplication {
			val koinApplication = startKoin {
				modules(agehaModule)
				declaration()
			}
			// SupervisorJob so one screen's failed coroutine does not cancel every other screen's.
			// A source blowing up while browsing must not take the library's database subscription
			// down with it.
			return AgehaApplication(CoroutineScope(SupervisorJob()), koinApplication.koin)
		}
	}
}
