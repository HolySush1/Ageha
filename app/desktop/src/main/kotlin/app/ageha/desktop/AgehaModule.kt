package app.ageha.desktop

import app.ageha.core.data.CatalogRepository
import app.ageha.core.data.LibraryRepository
import app.ageha.core.data.ReaderRepository
import app.ageha.core.data.SourceRepository
import app.ageha.core.database.AgehaDatabase
import app.ageha.core.database.AgehaDatabaseFactory
import app.ageha.core.image.AgehaImages
import app.ageha.core.network.AgehaPaths
import app.ageha.core.parsers.Ageha
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

	// One source stack for the process. It owns the OkHttp client, the cookie jar and the
	// classloader holding the parsers build, and it is the only thing allowed to close them --
	// see the single-owner note on SourceStack.
	single { Ageha.createSourceStack() }
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
