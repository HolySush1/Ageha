package app.ageha.core.parsers

import app.ageha.core.js.JsRuntime
import app.ageha.core.js.NoJsRuntime
import app.ageha.core.jvmcontext.AgehaMangaLoaderContext
import app.ageha.core.jvmcontext.SourceConfigStore
import app.ageha.core.network.AgehaPaths
import app.ageha.core.network.PersistentCookieJar
import app.ageha.core.parsers.internal.StaticMangaSourceRegistry
import java.io.File

/**
 * Builds a working source stack.
 *
 * There is a circular dependency to break here, and it is worth naming because it is not obvious.
 * The loader context needs an OkHttp client; the client needs an interceptor that dispatches to
 * parsers; parsers are created *by* the loader context. Constructing that naively either
 * stack-overflows or leaves a half-built client.
 *
 * It is broken with a function reference rather than an object reference: the context is handed a
 * lambda that resolves a parser, and the registry that lambda points at is created afterwards.
 * Nothing calls the lambda until the first HTTP request, by which point both exist.
 *
 * Milestone 4 replaces this with Koin. The wiring stays the same shape -- Koin is a way of writing
 * this down, not a different design -- and at six objects it is not yet earning its keep.
 */
object Ageha {

	/**
	 * The parsers build compiled into this app.
	 *
	 * Upstream publishes no version tags at all, so this is a commit SHA on
	 * Kotatsu-Redo/kotatsu-parsers-redo (docs/FINDINGS.md 1). Keep it in step with
	 * `parsers` in gradle/libs.versions.toml.
	 */
	const val BUNDLED_PARSERS_VERSION = "434030d481"

	fun createSourceStack(
		jsRuntime: JsRuntime = NoJsRuntime,
		cookieFile: File = AgehaPaths.cookieFile,
	): SourceStack {
		val cookieJar = PersistentCookieJar(cookieFile)
		// Not a parameter: SourceConfigStore is a :core:jvmcontext type, and exposing it here
		// would put a module-private type in the facade's public signature.
		val configStore = SourceConfigStore()

		// Late-bound so the context can be constructed before the registry that it feeds.
		lateinit var registry: StaticMangaSourceRegistry

		val context = AgehaMangaLoaderContext(
			cookieJar = cookieJar,
			jsRuntime = jsRuntime,
			configStore = configStore,
			parserForSource = { source -> registry.parserForTag(source) },
		)

		registry = StaticMangaSourceRegistry(
			contextProvider = { context },
			parsersVersion = BUNDLED_PARSERS_VERSION,
		)

		return SourceStack(
			registry = registry,
			cookieJar = cookieJar,
			jsRuntime = jsRuntime,
		)
	}
}

/**
 * A constructed source stack and the handles a host needs to shut it down cleanly.
 */
class SourceStack internal constructor(
	val registry: MangaSourceRegistry,
	private val cookieJar: PersistentCookieJar,
	private val jsRuntime: JsRuntime,
) {

	/** Flush cookies and release any native JavaScript resources. */
	suspend fun close() {
		cookieJar.persist()
		jsRuntime.close()
	}
}
