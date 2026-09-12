package app.ageha.core.jvmcontext

import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-source settings: which mirror domain to use, whether to show suspicious content, a
 * source-specific user agent, and so on.
 *
 * [MangaSourceConfig] is a single-method interface -- `operator fun <T> get(key: ConfigKey<T>): T`
 * -- so a source's configuration is entirely defined by the [ConfigKey]s its parser declares in
 * `onCreateConfig`. We do not need to know the key set in advance, which is exactly the property
 * that lets a new parser build introduce new settings without an Ageha release.
 *
 * Values are stored as strings against `ConfigKey.key` and coerced on read, so an unrecognised or
 * corrupt value falls back to the parser's own default rather than throwing.
 *
 * ## Persistence
 *
 * On disk, because the alternative was what Ageha shipped. This store was consulted on every
 * request and nothing ever wrote to it, so every one of the ~1,360 sources ran on its parser's
 * defaults forever -- and 258 of them declare a `ConfigKey.Domain` whose whole purpose is to be
 * changed when a site moves. A mirror the user picks has to still be picked after a restart or it
 * is not a remedy for anything.
 *
 * A `Properties` file rather than JSON: the content is a flat map of string to string, this module
 * has no serialization dependency and does not need one for that, and the file stays readable and
 * hand-editable if someone has to unstick a source without launching the app. Keys are
 * `<source>/<configKey>`; source names are enum constants and parser keys are lower-case
 * identifiers, so neither can contain the separator.
 */
class SourceConfigStore(
	/**
	 * Where the settings live, or null to keep them in memory only.
	 *
	 * Null is for tests, which want a store without a profile directory behind it. The real stack
	 * always passes a file -- including the second stack the compatibility gate builds to vet a
	 * candidate parsers build, which is deliberate: a build should be self-checked against the
	 * domains the user has actually chosen, not the ones its parsers default to. The gate only
	 * ever reads; nothing but [put] writes.
	 */
	private val storageFile: File? = null,
) {

	private val values = ConcurrentHashMap<String, MutableMap<String, String>>()

	init {
		load()
	}

	fun configFor(source: MangaSource): MangaSourceConfig = SourceConfig(source.name)

	fun put(sourceName: String, key: String, value: String?) {
		val forSource = values.computeIfAbsent(sourceName) { ConcurrentHashMap() }
		if (value == null) forSource.remove(key) else forSource[key] = value
		// Dropping the last override for a source leaves an empty map rather than removing the
		// entry. Harmless in memory, and `save` skips empties so it does not reach the file.
		save()
	}

	fun snapshot(sourceName: String): Map<String, String> =
		values[sourceName]?.toMap().orEmpty()

	/** Every source that has at least one override. For diagnostics and for backup. */
	fun configuredSources(): Set<String> =
		values.filterValues { it.isNotEmpty() }.keys.toSet()

	private fun load() {
		val file = storageFile?.takeIf { it.isFile } ?: return
		// A settings file that will not parse must not stop the app starting. Losing an override
		// costs the user one mirror choice; refusing to launch costs them the application.
		runCatching {
			val properties = Properties()
			file.inputStream().buffered().use(properties::load)
			for ((rawKey, rawValue) in properties.entries) {
				val name = rawKey as? String ?: continue
				val value = rawValue as? String ?: continue
				val separator = name.indexOf(SEPARATOR)
				if (separator <= 0 || separator == name.length - 1) continue
				values.computeIfAbsent(name.substring(0, separator)) { ConcurrentHashMap() }[
					name.substring(separator + 1),
				] = value
			}
		}
	}

	private fun save() {
		val file = storageFile ?: return
		runCatching {
			val properties = Properties()
			for ((sourceName, settings) in values) {
				for ((key, value) in settings) {
					properties.setProperty("$sourceName$SEPARATOR$key", value)
				}
			}
			file.parentFile?.mkdirs()
			// Written aside and renamed, so a crash mid-write cannot leave a half-file that the
			// next launch would read as "this source has no overrides".
			val tmp = File(file.parentFile, file.name + ".tmp")
			tmp.outputStream().buffered().use { out ->
				properties.store(out, "Ageha per-source settings. <source>/<key>=<value>.")
			}
			if (!tmp.renameTo(file)) {
				file.delete()
				tmp.renameTo(file)
			}
		}
	}

	private inner class SourceConfig(private val sourceName: String) : MangaSourceConfig {

		@Suppress("UNCHECKED_CAST")
		override fun <T> get(key: ConfigKey<T>): T {
			val raw = values[sourceName]?.get(key.key) ?: return key.defaultValue
			// The cast is unavoidable: ConfigKey<T> is a sealed hierarchy whose type argument is
			// only knowable per subclass, and the interface hands us no witness for T. Coercing
			// against the default's runtime type is the safest reading available, and anything we
			// cannot coerce degrades to the parser's own default.
			return when (val default = key.defaultValue) {
				is Boolean -> (raw.toBooleanStrictOrNull() ?: default) as T
				is Int -> (raw.toIntOrNull() ?: default) as T
				is Long -> (raw.toLongOrNull() ?: default) as T
				is String -> raw as T
				null -> raw as T
				else -> default
			}
		}
	}

	private companion object {
		const val SEPARATOR = '/'
	}
}
