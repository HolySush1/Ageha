package app.ageha.core.jvmcontext

import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
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
 * Milestone 2 keeps this in memory. Milestone 4 backs it with the settings store; the interface
 * does not change.
 */
class SourceConfigStore {

	private val values = ConcurrentHashMap<String, MutableMap<String, String>>()

	fun configFor(source: MangaSource): MangaSourceConfig = SourceConfig(source.name)

	fun put(sourceName: String, key: String, value: String?) {
		val forSource = values.computeIfAbsent(sourceName) { ConcurrentHashMap() }
		if (value == null) forSource.remove(key) else forSource[key] = value
	}

	fun snapshot(sourceName: String): Map<String, String> =
		values[sourceName]?.toMap().orEmpty()

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
}
