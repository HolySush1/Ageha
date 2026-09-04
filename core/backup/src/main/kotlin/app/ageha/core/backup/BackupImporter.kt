package app.ageha.core.backup

import app.ageha.core.database.AgehaDatabase
import app.ageha.core.database.dao.RestorePayload
import app.ageha.core.database.entity.MangaTagsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.util.zip.ZipFile

/**
 * Restores a Kotatsu-Redo backup archive into Ageha's database.
 *
 * This is the migration path, and the brief treats it as the difference between "try it" and
 * "switch to it". It was moved ahead of any UI deliberately: it exercises every column of the
 * schema against data the Android app actually wrote, so a divergence surfaces here rather than in
 * front of the first user who tries to move.
 *
 * ## Lenient reading, strict writing
 *
 * Reading is lenient because a backup is an artefact of *another* program's version history:
 * unknown JSON fields are ignored, absent fields fall back to the archive's documented defaults,
 * and a section Ageha has never heard of is reported rather than treated as corruption. A backup
 * from a newer Android app must still import what it can.
 *
 * Writing is strict because a half-restored library is worse than none. The whole import runs in
 * one transaction, and anything that cannot be written is named in
 * [BackupImportResult.droppedRows] rather than silently discarded.
 *
 * ## Ordering is not arbitrary
 *
 * Manga rows are written before the history and favourites that reference them, and categories
 * before favourites, because the schema's foreign keys are real and enforced. Getting this order
 * wrong gives a constraint violation on a real archive and a green test on an empty one.
 */
class BackupImporter(
	private val database: AgehaDatabase,
) {

	suspend fun import(archive: File): BackupImportResult = withContext(Dispatchers.IO) {
		if (!archive.isFile) {
			throw BackupImportException("No such file: " + archive.path)
		}

		val sections = runCatching { readSections(archive) }.getOrElse {
			throw BackupImportException(
				"Could not read '" + archive.name + "' as a backup archive. Kotatsu backups are " +
					"zip files; this may be the wrong file, or an incomplete download.",
				it,
			)
		}

		val present = sections.byName
		val index = present[BackupSection.INDEX]?.let { decodeIndex(it) }
		val skipped = BackupSection.entries.filter { !it.supportedByAgeha && present.containsKey(it) }

		val dropped = mutableListOf<String>()
		val restored = mutableMapOf<BackupSection, Int>()

		// Collected first, written once. Two reasons: the restore is a single transaction, and a
		// real archive embeds the same manga in both the history and the favourites section, so
		// deduplicating before writing avoids thousands of redundant statements.
		val manga = LinkedHashMap<Long, MangaBackup>()

		val sources = present[BackupSection.SOURCES]
			?.let { decode<List<SourceBackup>>(it) }
			.orEmpty()
			.also { if (present.containsKey(BackupSection.SOURCES)) restored[BackupSection.SOURCES] = it.size }

		val categories = present[BackupSection.CATEGORIES]
			?.let { decode<List<CategoryBackup>>(it) }
			.orEmpty()
			.also { if (present.containsKey(BackupSection.CATEGORIES)) restored[BackupSection.CATEGORIES] = it.size }
		val knownCategories = categories.mapTo(mutableSetOf()) { it.categoryId }

		val history = present[BackupSection.HISTORY]
			?.let { decode<List<HistoryBackup>>(it) }
			.orEmpty()
			.onEach { manga[it.manga.id] = it.manga }
			.also { if (present.containsKey(BackupSection.HISTORY)) restored[BackupSection.HISTORY] = it.size }

		val favourites = present[BackupSection.FAVOURITES]
			?.let { decode<List<FavouriteBackup>>(it) }
			.orEmpty()
			.filter { favourite ->
				// A real archive can reference a category its own categories section never
				// defined. The foreign key would reject that row and abort the whole import, so
				// the row is dropped and reported instead.
				val categoryId = favourite.categoryId.toInt()
				if (categoryId in knownCategories) {
					true
				} else {
					dropped += "favourite for manga " + favourite.mangaId +
						" refers to category " + categoryId + ", which this backup does not define"
					false
				}
			}
			.onEach { manga[it.manga.id] = it.manga }
			.also { if (present.containsKey(BackupSection.FAVOURITES)) restored[BackupSection.FAVOURITES] = it.size }

		// Tags are deduplicated across every manga that mentions them: a library of 2000 manga
		// typically shares a few dozen tags per source, and writing them per-manga would multiply
		// the work by three orders of magnitude for no benefit.
		val tags = LinkedHashMap<Long, TagBackup>()
		val tagLinks = LinkedHashSet<MangaTagsEntity>()
		manga.values.forEach { entry ->
			entry.tags.forEach { tag ->
				tags[tag.id] = tag
				tagLinks += MangaTagsEntity(entry.id, tag.id)
			}
		}

		// One transaction for the whole archive. A restore that half-applies leaves the user with
		// a library they cannot trust and no way to tell which half is real.
		database.restoreDao().restore(
			RestorePayload(
				sources = sources.map(SourceBackup::toEntity),
				categories = categories.map(CategoryBackup::toEntity),
				manga = manga.values.map(MangaBackup::toEntity),
				tags = tags.values.map(TagBackup::toEntity),
				tagLinks = tagLinks.toList(),
				history = history.map(HistoryBackup::toEntity),
				favourites = favourites.map(FavouriteBackup::toEntity),
			),
		)

		BackupImportResult(
			index = index,
			restored = restored,
			skippedSections = skipped,
			unknownEntries = sections.unknown,
			droppedRows = dropped,
		)
	}

	private fun readSections(archive: File): Sections {
		val byName = mutableMapOf<BackupSection, String>()
		val unknown = mutableListOf<String>()
		ZipFile(archive).use { zip ->
			val entries = zip.entries()
			while (entries.hasMoreElements()) {
				val entry = entries.nextElement()
				if (entry.isDirectory) continue
				val section = BackupSection.of(entry.name)
				if (section == null) {
					unknown += entry.name
					continue
				}
				byName[section] = zip.getInputStream(entry).bufferedReader().use { it.readText() }
			}
		}
		return Sections(byName, unknown)
	}

	/**
	 * Read the `index` section, which is an array holding one object.
	 *
	 * The wrapping is not a guess and not defensive coding. The Android app writes every section
	 * through one `writeJsonArray` helper, `index` included, so a real archive's index entry is
	 * `[{"app_id":...}]` and never a bare object. Reading it as an object -- which this did --
	 * fails, and the failure was swallowed here, so **every genuine Android backup reported no
	 * index at all** while the tests passed against a fixture that wrote a bare object. The bug
	 * was invisible from inside the project: only upstream's writer shows the real shape.
	 *
	 * Both shapes are accepted now. The array is what upstream and [BackupExporter] write; the
	 * bare object costs one branch and covers any hand-made archive, which is the sort of thing
	 * someone assembling a backup by hand would reasonably produce.
	 *
	 * Still null-on-failure rather than fatal: the index is provenance, not data. An archive whose
	 * index is unreadable still holds a library worth restoring, and refusing the import over a
	 * decorative field would be the wrong trade in the one moment the user is migrating.
	 */
	private fun decodeIndex(text: String): BackupIndex? = runCatching {
		val element = json.parseToJsonElement(text)
		val obj = when (element) {
			is JsonArray -> element.firstOrNull() ?: return null
			else -> element
		}
		json.decodeFromJsonElement<BackupIndex>(obj)
	}.getOrNull()

	private inline fun <reified T> decode(text: String): T = try {
		json.decodeFromString<T>(text)
	} catch (e: Exception) {
		throw BackupImportException("A section of the backup could not be read: " + e.message, e)
	}

	private data class Sections(
		val byName: Map<BackupSection, String>,
		val unknown: List<String>,
	)

	private companion object {
		/**
		 * Lenient by design. `ignoreUnknownKeys` lets a backup from a newer Android app import
		 * what Ageha does understand instead of failing wholesale.
		 */
		val json = Json {
			ignoreUnknownKeys = true
			isLenient = true
		}
	}
}
