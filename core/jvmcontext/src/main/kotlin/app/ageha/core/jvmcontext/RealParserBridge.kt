package app.ageha.core.jvmcontext

import app.ageha.core.js.JsRuntime
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.model.SourceFailure
import app.ageha.core.network.PersistentCookieJar
import okhttp3.OkHttpClient
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.source.ParserBridge
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.concurrent.ConcurrentHashMap

/**
 * The child side of the classloader boundary.
 *
 * Everything in this class that touches the parsers library stays on this side of the wall.
 * Nothing that leaves it is a parsers type: sources come out as [SourceDescriptor], manga as
 * Ageha models, failures as [SourceFailure].
 *
 * ## The constructor is an ABI
 *
 * This class is instantiated reflectively by `ParsersClassLoader`, from a classloader that has no
 * compile-time relationship to the caller. Nothing checks the constructor signature at build time,
 * so changing it breaks loading in a way no compiler will notice. `CompatibilityGate` asserts it
 * before activating any build, and [BRIDGE_CLASS_NAME] names this class for that purpose.
 *
 * Every parameter type must be one the loader delegates to the parent, or the reflective lookup
 * finds a signature it cannot satisfy.
 *
 * ## On MangaParserSource
 *
 * The enum is read by `entries`, and `name`, `title`, `locale`, `contentType` and `isBroken` are
 * read off each constant -- all of which exist on the generated enum whatever it contains. No
 * constant is ever named. It is KSP-generated at build time, so its constants differ between
 * builds; naming one would become a `NoSuchFieldError` the day upstream renames that source
 * (docs/FINDINGS.md 5).
 *
 * Because this class is recompiled and reloaded alongside the parsers JAR it talks to, none of
 * that has to be done reflectively. It links against whatever build it was loaded with.
 */
class RealParserBridge(
	baseHttpClient: OkHttpClient,
	cookieJar: PersistentCookieJar,
	jsRuntime: JsRuntime,
	override val parsersVersion: String,
) : ParserBridge {

	private val configStore = SourceConfigStore()

	private val context: AgehaMangaLoaderContext

	init {
		// The cycle to break: the context needs an HTTP client, the client needs an interceptor
		// that dispatches to parsers, and parsers are created by the context. Broken with a
		// function reference rather than an object reference -- nothing calls the lambda until the
		// first request, by which point `this` is fully constructed.
		context = AgehaMangaLoaderContext(
			cookieJar = cookieJar,
			jsRuntime = jsRuntime,
			configStore = configStore,
			parserForSource = ::parserForTag,
			baseHttpClient = baseHttpClient,
		)
	}

	private val descriptors: Map<String, SourceDescriptor> by lazy {
		MangaParserSource.entries.associate { it.name to ParserModelMapper.descriptor(it) }
	}

	private val sourcesByName: Map<String, MangaParserSource> by lazy {
		MangaParserSource.entries.associateBy { it.name }
	}

	/**
	 * Parser instances are cached because constructing one is not free -- it builds a web client
	 * and resolves configuration -- and because the HTTP interceptor looks one up on every request.
	 */
	private val parsers = ConcurrentHashMap<String, MangaParser>()

	private val clients = ConcurrentHashMap<String, MangaSourceClient>()

	override fun sourceDescriptors(): List<SourceDescriptor> =
		descriptors.values.sortedBy { it.title.lowercase() }

	override fun clientFor(name: String): MangaSourceClient {
		val descriptor = descriptors[name] ?: throw SourceFailure.UnknownSource(name)
		return clients.computeIfAbsent(name) {
			ParserMangaSourceClient(descriptor) { parserFor(name) }
		}
	}

	override fun selfCheck(sampleSize: Int): String? {
		val all = catchingParserFailure { MangaParserSource.entries }
			.getOrElse { return "MangaParserSource could not be read: " + it }
		if (all.isEmpty()) {
			return "MangaParserSource is empty -- the KSP-generated source list is missing from this build."
		}

		// Deterministic sample, so a rejection is reproducible rather than depending on which
		// sources happened to be picked.
		val sample = all.filterNot { it.isBroken }.take(sampleSize)
		val failures = mutableListOf<String>()
		for (source in sample) {
			val problem = catchingParserFailure {
				val parser = context.newParserInstance(source)
				when {
					parser.domain.isBlank() -> "reported no domain"
					parser.availableSortOrders.isEmpty() -> "reported no sort orders"
					else -> null
				}
			}.getOrElse { "could not be constructed: " + it }
			if (problem != null) {
				failures += source.name + " " + problem
			}
		}

		// One dead source is normal; the whole sample failing means the build is unusable. The
		// threshold matters: too strict and every routine update is rejected over one bad site.
		val tolerated = (sample.size * SELF_CHECK_TOLERANCE).toInt()
		return if (failures.size > tolerated) {
			failures.size.toString() + " of " + sample.size + " sampled parsers failed: " +
				failures.take(5).joinToString("; ")
		} else {
			null
		}
	}

	override fun close() {
		parsers.clear()
		clients.clear()
		context.close()
	}

	/**
	 * Resolve the parser for a request's source tag.
	 *
	 * On the hot path of every HTTP request, so it must not throw: an unknown source simply means
	 * no parser interception for that request.
	 */
	private fun parserForTag(source: MangaSource): MangaParser? =
		sourcesByName[source.name]?.let { parserFor(it.name) }

	private fun parserFor(name: String): MangaParser = parsers.computeIfAbsent(name) {
		context.newParserInstance(sourcesByName.getValue(name))
	}

	/**
	 * Like `runCatching`, but never swallows a [LinkageError].
	 *
	 * `runCatching` catches `Throwable`, which means it catches `Error` too. That is the wrong
	 * behaviour here and it hid a real distinction: when this build and Ageha no longer agree on a
	 * signature, *every* parser fails with `NoSuchMethodError`, and reporting that as "25 sources
	 * are broken" points the reader at the sources when the problem is the build. Letting linkage
	 * failures through means the gate classifies them as an incompatibility and tells the user the
	 * truthful thing: this needs a newer Ageha.
	 *
	 * A genuinely broken individual source still throws an ordinary exception and is still counted.
	 */
	private inline fun <T> catchingParserFailure(block: () -> T): Result<T> = try {
		Result.success(block())
	} catch (e: LinkageError) {
		throw e
	} catch (e: Exception) {
		Result.failure(e)
	}

	companion object {

		/** Where the loader looks for this class. Asserted by the compatibility gate. */
		const val BRIDGE_CLASS_NAME = "app.ageha.core.jvmcontext.RealParserBridge"

		/** Fraction of a self-check sample allowed to fail before a build is rejected. */
		private const val SELF_CHECK_TOLERANCE = 0.2
	}
}
