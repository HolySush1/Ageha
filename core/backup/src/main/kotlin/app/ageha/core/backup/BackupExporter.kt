package app.ageha.core.backup

import app.ageha.core.database.AgehaDatabase
import app.ageha.core.database.dao.ExportDao
import app.ageha.core.database.entity.MangaEntity
import app.ageha.core.model.AgehaVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Writes Ageha's library as a Kotatsu-Redo backup archive.
 *
 * The inverse of [BackupImporter], and the reason it exists is not symmetry. Until now Ageha could
 * read a library in and never write one out: a user's history, favourites and categories lived in
 * exactly one file on one disk with no supported way to copy them anywhere. That is a data-loss
 * hole, not a missing feature.
 *
 * ## The format is upstream's, not Ageha's own
 *
 * What this writes is the same archive the Android app's `BackupRepository` produces, which buys
 * three things for the price of matching someone else's JSON:
 *
 *  - Ageha's backups restore **onto Android**, so the migration runs both ways and moving to the
 *    desktop is not a one-way door.
 *  - They restore through [BackupImporter] with no second code path, so the format has exactly
 *    one reader and the round trip is testable end to end.
 *  - A user holding archives from both apps has one kind of file, not two.
 *
 * Matching it means matching the parts that look like mistakes, and one in particular: **every
 * section is a JSON array, `index` included**, where it holds a single object wrapped in `[ ]`.
 * That is what upstream emits -- its index goes through the same `writeJsonArray` as every other
 * section -- and writing a bare object there would produce an archive neither app reads properly.
 * [BackupImporter.decodeIndex] carries the other half of that story.
 *
 * ## What is written, and what is deliberately absent
 *
 * `index`, `history`, `categories`, `favourites` and `sources`: the sections Ageha holds data for,
 * which is the same set [BackupSection.supportedByAgeha] marks restorable. The rest --
 * `bookmarks`, `settings`, `scrobbling`, `statistics`, `saved_filters` -- are **not written at
 * all** rather than written empty. An absent section reads as "this app had nothing to say about
 * that"; an empty one reads as "this app had none of those", which for settings is a falsehood a
 * restoring Android app could act on.
 *
 * ## Streaming, and why that is not a premature optimisation
 *
 * A backup embeds the full manga record with its tags inside *every* history row, and again inside
 * every favourite row. A large library is several times its own size once expanded, so building
 * the archive in memory before writing a byte is the difference between an export that works on a
 * real library and one that works on the developer's. Rows are read in windows through
 * [ExportDao], each window's manga and tags fetched in two queries, and each entry serialised and
 * written before the next row is read.
 *
 * ## The file appears only once it is complete
 *
 * Written to a sibling `.part` and renamed on success -- the discipline `ChapterDownloader` uses
 * for chapters, for the same reason. A half-written archive that looks like a backup is worse than
 * no backup, because it is discovered at restore time, which is the one moment the user has
 * nothing else left.
 */
class BackupExporter(
	private val database: AgehaDatabase,
	/** Stamped into the archive index. Injected so a test can assert an exact value. */
	private val appId: String = APP_ID,
	private val appVersion: Int = AgehaVersion.CODE,
	private val now: () -> Long = System::currentTimeMillis,
) {

	suspend fun export(destination: File): BackupExportResult = withContext(Dispatchers.IO) {
		val parent = destination.absoluteFile.parentFile
		if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
			throw BackupExportException("Cannot write to " + parent.path + ": no such directory.")
		}
		val partial = File(parent, destination.name + ".part")
		val dao = database.exportDao()
		val counts = LinkedHashMap<BackupSection, Int>()
		val createdAt = now()

		try {
			ZipOutputStream(partial.outputStream().buffered()).use { zip ->
				zip.section(BackupSection.INDEX) { writer ->
					// One object, array-wrapped. Not a quirk worth ironing out; see above.
					writer.write(json.encodeToString(BackupIndex(appId, appVersion, createdAt)))
					1
				}

				counts[BackupSection.CATEGORIES] = zip.section(BackupSection.CATEGORIES) { writer ->
					val categories = dao.dumpCategories()
					categories.forEach { writer.write(json.encodeToString(it.toBackup())) }
					categories.size
				}

				counts[BackupSection.HISTORY] = zip.section(BackupSection.HISTORY) { writer ->
					pageThrough(dao::dumpHistory) { page ->
						val manga = dao.mangaOf(page.map { it.mangaId })
						var written = 0
						for (entry in page) {
							// A history row whose manga row is missing cannot be represented: the
							// archive embeds the manga. The foreign key should make this
							// unreachable, and skipping still beats inventing one.
							val embedded = manga[entry.mangaId] ?: continue
							writer.write(json.encodeToString(entry.toBackup(embedded)))
							written++
						}
						written
					}
				}

				counts[BackupSection.FAVOURITES] = zip.section(BackupSection.FAVOURITES) { writer ->
					pageThrough(dao::dumpFavourites) { page ->
						val manga = dao.mangaOf(page.map { it.mangaId })
						var written = 0
						for (entry in page) {
							val embedded = manga[entry.mangaId] ?: continue
							writer.write(json.encodeToString(entry.toBackup(embedded)))
							written++
						}
						written
					}
				}

				counts[BackupSection.SOURCES] = zip.section(BackupSection.SOURCES) { writer ->
					pageThrough(dao::dumpEnabledSources) { page ->
						page.forEach { writer.write(json.encodeToString(it.toBackup())) }
						page.size
					}
				}
			}
		} catch (e: Throwable) {
			// Including cancellation. A `.part` left behind by a cancelled export is litter that
			// looks like a crash; the whole point of the suffix is that nothing outlives a failure.
			partial.delete()
			throw e
		}

		// The rename is the commit. Windows will not replace an existing file through `renameTo`,
		// so an overwrite has to remove the old archive first -- and only here, once the
		// replacement is known to be complete on disk.
		if (destination.exists() && !destination.delete()) {
			partial.delete()
			throw BackupExportException(
				"Cannot replace " + destination.path + ": it is open in another program, or read-only.",
			)
		}
		if (!partial.renameTo(destination)) {
			partial.delete()
			throw BackupExportException("Could not finish writing " + destination.path + ".")
		}

		BackupExportResult(
			file = destination,
			createdAt = createdAt,
			written = counts,
			sizeBytes = destination.length(),
		)
	}

	/**
	 * Walk one table in windows, holding at most [WINDOW] rows at a time.
	 *
	 * Termination is a short page rather than an empty one: being handed fewer rows than were
	 * asked for means the table ended inside this window, which saves the extra round trip an
	 * "until empty" loop always makes on the way out.
	 *
	 * The cancellation check is per window rather than per row. An export of a large library is
	 * long enough that quitting the app during one is an ordinary thing to do, and none of the
	 * work between windows suspends on its own.
	 */
	private suspend fun <T> pageThrough(
		fetch: suspend (offset: Int, limit: Int) -> List<T>,
		write: suspend (List<T>) -> Int,
	): Int {
		var offset = 0
		var total = 0
		while (true) {
			coroutineContext.ensureActive()
			val page = fetch(offset, WINDOW)
			if (page.isNotEmpty()) {
				total += write(page)
				offset += page.size
			}
			if (page.size < WINDOW) return total
		}
	}

	/**
	 * Every manga in [ids], as archive records with their tags attached.
	 *
	 * Two queries per window however many rows it holds. The obvious alternative -- one manga
	 * lookup and one tag lookup per row -- is 2N round trips through SQLite for no gain, over the
	 * whole library.
	 */
	private suspend fun ExportDao.mangaOf(ids: List<Long>): Map<Long, MangaBackup> {
		// Distinct because the same manga legitimately appears in several favourite categories,
		// and would otherwise be fetched and mapped once per category it is filed under.
		val distinct = ids.distinct()
		if (distinct.isEmpty()) return emptyMap()
		val tags = tagsByMangaIds(distinct).groupBy({ it.mangaId }, { it.tag.toBackup() })
		return mangaByIds(distinct).associateBy(MangaEntity::id) { entity ->
			entity.toBackup(tags[entity.id].orEmpty().toSet())
		}
	}

	/**
	 * One zip entry holding a JSON array, written element by element.
	 *
	 * The brackets and separators go in by hand because the array is never a list in memory:
	 * elements are produced a window at a time and serialised one at a time, which is the whole
	 * point of the exercise.
	 */
	private suspend fun ZipOutputStream.section(
		section: BackupSection,
		body: suspend (ArrayWriter) -> Int,
	): Int {
		putNextEntry(ZipEntry(section.entryName))
		try {
			write(OPEN)
			val count = body(ArrayWriter(this))
			write(CLOSE)
			return count
		} finally {
			closeEntry()
		}
	}

	/** Writes already-serialised elements into an open array, comma-separating as it goes. */
	private class ArrayWriter(private val out: OutputStream) {

		private var written = 0

		fun write(encoded: String) {
			if (written > 0) out.write(SEPARATOR)
			out.write(encoded.toByteArray(Charsets.UTF_8))
			written++
		}
	}

	private companion object {

		val json = Json {
			// `encodeDefaults` matters, and upstream sets it too: a field omitted because it
			// happened to equal its default is a field a stricter reader will not find. This
			// archive is read by another program, so it states everything.
			encodeDefaults = true
			// A history row can carry a NaN percent from a chapter whose progress was never
			// computed. Without this, serialising it throws in the middle of the archive rather
			// than writing back what SQLite holds.
			allowSpecialFloatingPointValues = true
		}

		/**
		 * Rows per query.
		 *
		 * Upstream uses 10, tuned for a phone paging into a flow that a UI consumes. Ageha writes
		 * the whole archive in one pass on a desktop, where the per-query cost dominates and 64
		 * rows of manga plus tags is a few hundred kilobytes at worst.
		 */
		const val WINDOW = 64

		val OPEN = "[".toByteArray(Charsets.UTF_8)
		val CLOSE = "]".toByteArray(Charsets.UTF_8)
		val SEPARATOR = ",".toByteArray(Charsets.UTF_8)

		/**
		 * What the index says wrote this file.
		 *
		 * Ageha's own id, not `org.koitharu.kotatsu`. The Android app ignores the index when
		 * restoring, so claiming to be it would buy nothing while making a file that came from
		 * here indistinguishable from one that did not -- exactly the question someone holding two
		 * archives and one problem needs answered.
		 */
		const val APP_ID = "app.ageha"

	}
}
