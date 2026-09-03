package app.ageha.core.backup

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds backup archives for the import tests.
 *
 * Synthesised rather than checked in as a fixture file, so a test can state exactly the shape it
 * is testing -- a missing section, an unknown section, a dangling category reference -- instead of
 * hoping one sample happens to contain it. The JSON here is written by hand against the Android
 * app's serialiser output, so it also serves as a record of the format.
 */
class BackupArchiveBuilder {

	private val entries = LinkedHashMap<String, String>()

	fun section(section: BackupSection, json: String) = apply {
		entries[section.entryName] = json
	}

	/** An entry name Ageha does not recognise, as a newer Android app would write. */
	fun rawEntry(name: String, json: String) = apply { entries[name] = json }

	fun writeTo(file: File): File {
		file.parentFile?.mkdirs()
		ZipOutputStream(file.outputStream()).use { zip ->
			entries.forEach { (name, content) ->
				zip.putNextEntry(ZipEntry(name))
				zip.write(content.toByteArray())
				zip.closeEntry()
			}
		}
		return file
	}

	companion object {

		fun index(appVersion: Int = 1234) =
			"""{"app_id":"org.koitharu.kotatsu","app_version":$appVersion,"created_at":1756900000000}"""

		/**
		 * One manga, as the Android app serialises it inside a history or favourite entry.
		 *
		 * Note `"rating":-1.0` and the comma-joined `author` -- both are the archive's real shape,
		 * not simplifications.
		 */
		fun manga(id: Long, title: String, tagIds: List<Long> = listOf(10L)): String {
			val tags = tagIds.joinToString(",") { tagId ->
				"""{"id":$tagId,"title":"Tag $tagId","key":"tag-$tagId","source":"MANGADEX","pinned":false}"""
			}
			return """{"id":$id,"title":"$title","alt_title":null,"url":"/manga/$id",""" +
				""""public_url":"https://mangadex.org/title/$id","rating":-1.0,"nsfw":false,""" +
				""""content_rating":"SAFE","cover_url":"https://example.org/$id.jpg",""" +
				""""large_cover_url":null,"state":"ONGOING","author":"Author One, Author Two",""" +
				""""source":"MANGADEX","tags":[$tags]}"""
		}

		fun history(mangaId: Long, title: String, page: Int = 12, scroll: Float = 0.5f) =
			"""{"manga_id":$mangaId,"created_at":1000,"updated_at":2000,"chapter_id":99,""" +
				""""page":$page,"scroll":$scroll,"percent":0.35,"chapters":120,""" +
				""""manga":${manga(mangaId, title)}}"""

		fun category(id: Int, title: String) =
			"""{"category_id":$id,"created_at":1000,"sort_key":0,"title":"$title",""" +
				""""order":"NEWEST","track":true,"show_in_lib":true}"""

		/** `category_id` is a Long here, and an Int in the column. That mismatch is real. */
		fun favourite(mangaId: Long, categoryId: Long, title: String) =
			"""{"manga_id":$mangaId,"category_id":$categoryId,"sort_key":0,"pinned":false,""" +
				""""created_at":1500,"manga":${manga(mangaId, title)}}"""

		fun source(name: String, sortKey: Int = 0) =
			"""{"source":"$name","sort_key":$sortKey,"used_at":9000,"added_in":28,"pinned":false,"enabled":true}"""
	}
}
