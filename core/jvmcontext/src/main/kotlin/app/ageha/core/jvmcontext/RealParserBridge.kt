package app.ageha.core.jvmcontext

import app.ageha.core.js.JsRuntime
import app.ageha.core.model.SourceDescriptor
import app.ageha.core.model.SourceFailure
import app.ageha.core.network.PersistentCookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.source.ParserBridge
import app.ageha.core.source.ResolvedLink
import kotlinx.coroutines.CancellationException
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

	/**
	 * Which sources serve which domain: preset domain, lowercased, to source names.
	 *
	 * ## Why this has to be built the hard way
	 *
	 * `MangaParserSource` carries `title`, `locale`, `contentType` and `isBroken` and nothing else
	 * -- no domain. A source's domains live on its *parser*, in `configKeyDomain.presetValues`, so
	 * the only way to know who serves a host is to construct every parser and ask. That is exactly
	 * what upstream's own resolver does; it simply stops at the first match, which is the defect
	 * this exists to repair.
	 *
	 * ## Why the instances are thrown away
	 *
	 * Built with `context.newParserInstance` rather than [parserFor], deliberately. [parserFor]
	 * caches, and each parser holds a web client -- keeping 1,360 of them alive so that a dialog
	 * can list 42 would be a leak wearing a cache's clothes. The map of strings is the only thing
	 * worth keeping, and it is built once per process.
	 *
	 * One parser failing to construct costs its own domains and nothing else, which matches how
	 * [selfCheck] already treats a single broken source.
	 */
	private val hostIndex: Map<String, List<String>> by lazy {
		val index = HashMap<String, MutableList<String>>()
		for (source in MangaParserSource.entries) {
			val presets = quietly { context.newParserInstance(source).configKeyDomain.presetValues }
			for (domain in presets.orEmpty()) {
				index.getOrPut(domain.lowercase()) { mutableListOf() }.add(source.name)
			}
		}
		index
	}

	/**
	 * Build the host index now, off the path of the lookup that would otherwise pay for it.
	 *
	 * Constructing 1,360 parsers is the one slow part of resolving a link, and it is the same work
	 * whenever it happens -- so the dialog calls this when it opens and the scan overlaps with the
	 * person pasting. Idempotent: the second caller finds the `lazy` already resolved.
	 */
	override fun warmLinkIndex() {
		hostIndex
	}

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

	/**
	 * Match [url] against every source in this build, using the parsers library's own resolver.
	 *
	 * Upstream's `LinkResolver` rather than a host lookup of Ageha's own, and the reason is
	 * mirrors: each parser declares several domains through `ConfigKey.Domain`, sites move between
	 * them constantly, and the resolver already knows every one. A table built here would be a
	 * second copy of that knowledge, out of date by the next parsers update.
	 *
	 * Every step is allowed to come up empty. A front page is not a manga page, so `getManga`
	 * failing there is the expected outcome and still yields the source on its own.
	 */
	override suspend fun resolveLink(url: String): ResolvedLink? {
		// newLinkResolver parses the string itself and throws on one it cannot read -- "not a
		// link" is an answer for the caller to show, not an exception for it to catch.
		val resolver = catchingParserFailure { context.newLinkResolver(url) }.getOrNull() ?: return null
		val source = quietly { resolver.getSource() } ?: return null
		// A resolver result this bridge cannot open would send the user somewhere that fails. The
		// two lists come from the same enum, so this should never trip; if it ever does, saying
		// "no source handles this" is better than a dead end.
		if (source.name !in descriptors) return null

		val manga = quietly { resolver.getManga() }?.let { found ->
			// The resolver sometimes knows *which* manga a link names without knowing its title,
			// and returns a placeholder called "Unknown manga". Showing that in a dialog reads as a
			// bug, and the details request that fixes it is the one opening the manga would make
			// a moment later anyway. If it fails, the placeholder is still a working link.
			if (found.title == RESOLVER_STUB_TITLE) {
				quietly { parserFor(source.name).getDetails(found) } ?: found
			} else {
				found
			}
		}
		return ResolvedLink(
			sourceName = source.name,
			manga = manga?.let(ParserModelMapper::manga),
			alternatives = alternativesFor(resolver.link, source.name),
		)
	}

	/**
	 * Every *other* source that serves the same site, in the build's own declaration order.
	 *
	 * Looked up by host and by top private domain, the same pair upstream matches against, so this
	 * agrees with the resolver about what "serves this site" means rather than inventing a second
	 * rule. Filtered to sources this bridge can actually open, for the reason [resolveLink] gives
	 * about its own result: an entry that led nowhere would be worse than no entry.
	 */
	private fun alternativesFor(link: HttpUrl, chosen: String): List<String> =
		listOfNotNull(link.host, link.topPrivateDomain())
			.flatMap { hostIndex[it.lowercase()].orEmpty() }
			.distinct()
			.filter { it != chosen && it in descriptors }

	/**
	 * Resolve [url] as [sourceName] reads it, rather than as upstream's resolver chose.
	 *
	 * The link is resolved once for its *shape* -- which manga on the site it names -- and that is
	 * then asked of the chosen source's own parser. Sibling language sources share a domain and a
	 * URL layout, being the same site, so the relative address carries across.
	 *
	 * Where it does not carry across -- a site that encodes its language in the path, and they
	 * exist -- `getDetails` fails or returns nothing, and this answers with the source and no
	 * manga. The dialog then offers the site rather than the title, which is the right way to be
	 * wrong: landing on a front page is a small annoyance, opening the wrong title silently is not.
	 */
	override suspend fun resolveLinkAs(url: String, sourceName: String): ResolvedLink? {
		val source = sourcesByName[sourceName] ?: return null
		if (sourceName !in descriptors) return null
		val resolver = catchingParserFailure { context.newLinkResolver(url) }.getOrNull() ?: return null
		val seed = quietly { resolver.getManga() } ?: return ResolvedLink(sourceName, manga = null)
		val details = quietly { parserFor(sourceName).getDetails(seed.copy(source = source)) }
		return ResolvedLink(sourceName, manga = details?.let(ParserModelMapper::manga))
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

	/**
	 * [catchingParserFailure] for suspending work: null on an ordinary failure.
	 *
	 * A separate helper because the original catches every `Exception`, and in a coroutine that
	 * includes `CancellationException`. The self-check never suspends, so it never mattered
	 * there; here it would. The dialog puts a timeout on link resolution, and a resolver that
	 * swallowed the cancellation would keep the user staring at a spinner after the timeout had
	 * already fired. Linkage errors still propagate, for the reason [catchingParserFailure] gives.
	 */
	private inline fun <T> quietly(block: () -> T): T? = try {
		block()
	} catch (e: CancellationException) {
		throw e
	} catch (e: LinkageError) {
		throw e
	} catch (e: Exception) {
		null
	}

	companion object {

		/** Where the loader looks for this class. Asserted by the compatibility gate. */
		const val BRIDGE_CLASS_NAME = "app.ageha.core.jvmcontext.RealParserBridge"

		/** Fraction of a self-check sample allowed to fail before a build is rejected. */
		private const val SELF_CHECK_TOLERANCE = 0.2

		/**
		 * The placeholder title upstream's `LinkResolver` gives a manga it located but could not
		 * name.
		 *
		 * Copied rather than referenced: upstream declares it `STUB_TITLE` inside a *private*
		 * companion object, so Kotlin refuses the reference even though the JVM field is public.
		 * If upstream ever changes the text, the only consequence is that the dialog shows the
		 * placeholder instead of fetching the real title -- the link itself still opens.
		 */
		private const val RESOLVER_STUB_TITLE = "Unknown manga"
	}
}
