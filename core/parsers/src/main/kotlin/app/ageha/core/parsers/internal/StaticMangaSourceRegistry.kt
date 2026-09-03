package app.ageha.core.parsers.internal

import app.ageha.core.model.SourceDescriptor
import app.ageha.core.model.SourceFailure
import app.ageha.core.parsers.MangaSourceClient
import app.ageha.core.parsers.MangaSourceRegistry
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.concurrent.ConcurrentHashMap

/**
 * The registry backed by the parsers artifact compiled into the app.
 *
 * Milestone 3 adds a sibling that reads a dynamically downloaded JAR through reflection. This one
 * is not throwaway scaffolding for that: a bundled known-good build is the fallback the update
 * engine falls back *to*, so both ship.
 *
 * Note what this does and does not do with `MangaParserSource`. It enumerates `entries` and reads
 * `name`, `title`, `locale`, `contentType` and `isBroken` -- all of which exist on the generated
 * enum regardless of which sources are in it. It never names a constant. `MangaParserSource` is
 * KSP-generated at build time, so its constants change between builds, and a compile-time
 * reference to one would become a `NoSuchFieldError` the day upstream renames that source
 * (docs/FINDINGS.md 5).
 */
internal class StaticMangaSourceRegistry(
	private val contextProvider: () -> MangaLoaderContext,
	override val parsersVersion: String,
) : MangaSourceRegistry {

	private val descriptors: Map<String, SourceDescriptor> by lazy {
		MangaParserSource.entries.associate { it.name to ParserModelMapper.descriptor(it) }
	}

	private val sourcesByName: Map<String, MangaParserSource> by lazy {
		MangaParserSource.entries.associateBy { it.name }
	}

	/**
	 * Parser instances are cached because constructing one is not free -- it builds a WebClient and
	 * resolves config -- and because the HTTP interceptor looks one up on every single request.
	 */
	private val parsers = ConcurrentHashMap<String, MangaParser>()

	private val clients = ConcurrentHashMap<String, MangaSourceClient>()

	override fun availableSources(): List<SourceDescriptor> =
		descriptors.values.sortedBy { it.title.lowercase() }

	override fun descriptorFor(name: String): SourceDescriptor? = descriptors[name]

	override fun clientFor(name: String): MangaSourceClient {
		val descriptor = descriptors[name] ?: throw SourceFailure.UnknownSource(name)
		return clients.computeIfAbsent(name) {
			ParserMangaSourceClient(descriptor) { parserFor(name) }
		}
	}

	/**
	 * Resolve the parser for a request's source tag.
	 *
	 * This is what [app.ageha.core.jvmcontext.ParserDispatchInterceptor] calls, so it is on the hot
	 * path of every HTTP request and must not throw: an unknown source simply means "no parser
	 * interception for this request".
	 */
	fun parserForTag(source: MangaSource): MangaParser? =
		sourcesByName[source.name]?.let { parserFor(it.name) }

	private fun parserFor(name: String): MangaParser = parsers.computeIfAbsent(name) {
		val source = sourcesByName.getValue(name)
		contextProvider().newParserInstance(source)
	}
}
