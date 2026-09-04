package app.ageha.cli

import app.ageha.core.model.AgehaFilter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaSortOrder
import app.ageha.core.model.SourceFailure
import app.ageha.core.backup.BackupExportException
import app.ageha.core.backup.BackupExporter
import app.ageha.core.backup.BackupImportException
import app.ageha.core.backup.BackupImporter
import app.ageha.core.backup.defaultBackupFileName
import app.ageha.core.sync.SyncAccount
import app.ageha.core.sync.SyncAccountStore
import app.ageha.core.sync.SyncApi
import app.ageha.core.sync.SyncApiException
import app.ageha.core.sync.SyncEngine
import app.ageha.core.database.AgehaDatabaseFactory
import app.ageha.core.network.AgehaHttpClient
import app.ageha.core.network.AgehaPaths
import app.ageha.core.network.PersistentCookieJar
import app.ageha.core.js.RhinoJsRuntime
import app.ageha.core.parsers.Ageha
import app.ageha.core.parsers.ParsersUpdateService
import app.ageha.core.parsers.UpdateOutcome
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.parsers.SourceStack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * A headless proof that the parsers work on desktop.
 *
 * This is the Milestone 2 deliverable, and it exists to answer one question before any UI is
 * built: does a JVM host really drive this library, over real HTTP, against a real site?
 * Everything after this milestone assumes the answer is yes, so it is worth being able to re-run
 * in one command.
 *
 * It is also the first demonstration that the wall holds. This file imports nothing from the
 * parsers library -- the build fails if it tries -- yet it can search any of 1360 sources.
 */
fun main(args: Array<String>) {
	forceUtf8Console()
	if (args.isEmpty()) {
		printUsage()
		exitProcess(2)
	}
	val stack = Ageha.createSourceStack(jsRuntime = RhinoJsRuntime())
	try {
		runBlocking {
			when (val command = args[0]) {
				"sources" -> listSources(stack, args.getOrNull(1))
				"parsers" -> parsers(stack, args.getOrNull(1))
				"import" -> requireArgs(args, 2) { importBackup(args[1]) }
				"export" -> exportBackup(args.getOrNull(1))
				"sync" -> sync(stack, args.getOrNull(1), args.drop(2))
				"library" -> library()
				"smoke" -> smokeTest(
					stack = stack,
					sampleSize = flag(args, "--sample")?.toIntOrNull() ?: 15,
					// Seeded so a failing nightly run can be reproduced exactly. Without a seed
					// the sample differs every time and a failure cannot be looked at twice.
					seed = flag(args, "--seed")?.toLongOrNull() ?: System.currentTimeMillis(),
				)
				"search" -> requireArgs(args, 3) { search(stack, args[1], args[2]) }
				"details" -> requireArgs(args, 3) {
					details(stack, args[1], args[2], args.getOrNull(3)?.toIntOrNull() ?: 0)
				}

				"pages" -> requireArgs(args, 3) {
					pages(stack, args[1], args[2], args.getOrNull(3)?.toIntOrNull() ?: 0)
				}
				else -> {
					System.err.println("Unknown command: " + command)
					printUsage()
					exitProcess(2)
				}
			}
		}
	} catch (e: SourceFailure) {
		reportFailure(e)
		exitProcess(1)
	} catch (e: IllegalStateException) {
		// Bad input to the CLI itself -- no results, index out of range. A stack trace here would
		// suggest a defect when the user simply asked for something that is not there.
		System.err.println()
		System.err.println(e.message)
		exitProcess(1)
	} finally {
		runBlocking { stack.close() }
	}
}

/**
 * Print UTF-8 regardless of the console's codepage.
 *
 * Not cosmetic. Manga titles are routinely Japanese, Korean and Chinese, and a Windows console
 * defaults to a legacy codepage that renders every one of them as replacement characters. That
 * looks exactly like a parser bug, which is the last thing this milestone should be ambiguous
 * about.
 */
private fun forceUtf8Console() {
	System.setOut(PrintStream(System.out, true, Charsets.UTF_8))
	System.setErr(PrintStream(System.err, true, Charsets.UTF_8))
}

private fun listSources(stack: SourceStack, filter: String?) {
	val all = stack.registry.availableSources()
	val matching = if (filter.isNullOrBlank()) {
		all
	} else {
		all.filter {
			it.name.contains(filter, ignoreCase = true) || it.title.contains(filter, ignoreCase = true)
		}
	}
	println("Parsers build: " + stack.registry.parsersVersion)
	println("" + matching.size + " of " + all.size + " sources")
	println()
	for (source in matching) {
		val flags = buildList {
			source.locale?.let(::add)
			add(source.contentType.name.lowercase())
			if (source.isBroken) add("BROKEN")
		}.joinToString(", ")
		println("  " + source.name.padEnd(28) + " " + source.title.padEnd(30) + " [" + flags + "]")
	}
}

/**
 * Show what the local database holds.
 *
 * The counterpart to `import`: without a way to look, "restored 4 items" is a claim rather than a
 * fact. It also demonstrates the point of the source-name rule -- a row whose source is not in the
 * loaded parsers build is shown as unavailable rather than hidden or dropped.
 */
private suspend fun library() {
	val database = AgehaDatabaseFactory.open(File(AgehaPaths.dataDir, "ageha.db"))
	try {
		println("Database:  " + File(AgehaPaths.dataDir, "ageha.db"))
		println("Manga:     " + database.mangaDao().count())
		println("Sources:   " + database.sourcesDao().all().size)
		println()

		val categories = database.favouritesDao().categories()
		println("Categories (" + categories.size + "):")
		categories.forEach { category ->
			val items = database.favouritesDao().inCategory(category.categoryId)
			println("  " + category.title + " -- " + items.size + " item(s)")
			items.take(5).forEach { favourite ->
				val manga = database.mangaDao().find(favourite.mangaId)
				println("      " + (manga?.title ?: "(missing manga " + favourite.mangaId + ")"))
			}
		}

		println()
		val history = database.historyDao().observeRecent(10).first()
		println("Recent history (" + history.size + "):")
		history.forEach { entry ->
			val manga = database.mangaDao().find(entry.mangaId)
			val percent = (entry.percent * 100).toInt()
			println(
				"  " + (manga?.title ?: "?") +
					"  chapter " + entry.chapterId + ", page " + entry.page +
					(if (entry.percent >= 0f) "  (" + percent + "%)" else ""),
			)
		}
	} finally {
		database.close()
	}
}

/**
 * Import an Android backup.
 *
 * The migration path, and the reason it exists before any UI: it exercises every column of the
 * schema against data the Android app actually wrote. The report is deliberately detailed --
 * someone moving years of reading history needs to know exactly what did and did not come across,
 * at the moment they do it.
 */
private suspend fun importBackup(path: String) {
	val file = File(path)
	val database = AgehaDatabaseFactory.open(File(AgehaPaths.dataDir, "ageha.db"))
	try {
		println("Importing " + file.name + "...")
		val result = BackupImporter(database).import(file)
		result.index?.let {
			println("Backup written by " + it.appId + " build " + it.appVersion)
		}
		println()
		println(result.describe())
	} catch (e: BackupImportException) {
		System.err.println()
		System.err.println(e.message)
		exitProcess(1)
	} finally {
		database.close()
	}
}

/**
 * Write a backup of the local database.
 *
 * Here as well as in the UI because this is the command someone reaches for when scripting a
 * nightly copy, and because it is the fastest way to see what an export actually contains without
 * opening the app. With no path it writes upstream's dated file name into the working directory,
 * which is what a cron line wants and what an interactive user would have typed anyway.
 */
private suspend fun exportBackup(path: String?) {
	val file = File(path ?: defaultBackupFileName())
	val database = AgehaDatabaseFactory.open(File(AgehaPaths.dataDir, "ageha.db"))
	try {
		println("Exporting to " + file.absolutePath + "...")
		val result = BackupExporter(database).export(file)
		println()
		println(result.describe())
	} catch (e: BackupExportException) {
		System.err.println()
		System.err.println(e.message)
		exitProcess(1)
	} finally {
		database.close()
	}
}

/**
 * Sync against a kotatsu-syncserver.
 *
 * `login` deliberately does **not** take the password as an argument. A password on a command line
 * lands in shell history, in the process table and in any terminal recording, and the whole point
 * of this feature is that a credential is being handled carefully. It is read from the console
 * with echo off instead, and refused outright when there is no console -- a piped stdin cannot be
 * read without echo, so accepting one would silently downgrade exactly the protection this is for.
 */
private suspend fun sync(stack: SourceStack, command: String?, args: List<String>) {
	val accounts = SyncAccountStore()
	when (command) {
		null, "run" -> {
			val database = AgehaDatabaseFactory.open(File(AgehaPaths.dataDir, "ageha.db"))
			try {
				val engine = SyncEngine(database, SyncApi(stack.httpClient), accounts)
				println(engine.sync().describe())
			} finally {
				database.close()
			}
		}

		"status" -> {
			val account = accounts.load()
			if (account == null) {
				println("No sync account is set up. Run 'cli sync login <url> <email>'.")
			} else {
				println("Signed in as " + account.email)
				println("Server:   " + account.syncUrl)
				println("Password: " + if (account.isPasswordStored) "stored on this machine" else "not stored")
			}
		}

		"login" -> {
			if (args.size < 2) {
				System.err.println("Usage: cli sync login <url> <email>")
				exitProcess(2)
			}
			val console = System.console()
			if (console == null) {
				System.err.println(
					"No console available, so the password cannot be read without echoing it. " +
						"Run this from a terminal.",
				)
				exitProcess(2)
			}
			val password = String(console.readPassword("Password for %s: ", args[1]))
			val url = args[0].trimEnd('/').let {
				if (it.startsWith("http://") || it.startsWith("https://")) it else "https://" + it
			}
			try {
				val token = SyncApi(stack.httpClient).authenticate(url, args[1], password)
				accounts.save(SyncAccount(syncUrl = url, email = args[1], token = token, password = password))
				println("Signed in to " + url + " as " + args[1] + ".")
			} catch (e: SyncApiException) {
				System.err.println("Sign-in failed: " + e.message)
				exitProcess(1)
			}
		}

		"logout" -> {
			accounts.clear()
			println("Signed out. The stored account file has been removed.")
		}

		else -> {
			System.err.println("Unknown sync command: " + command)
			exitProcess(2)
		}
	}
}

/**
 * Inspect and update the loaded parsers build.
 *
 * The point of showing this in a CLI is that Layer 1 is otherwise invisible until it goes wrong.
 * A rejected build is a normal event -- the host contract does occasionally move -- and being able
 * to see which build is live, what was refused and why, is what makes that legible rather than
 * mysterious.
 */
private suspend fun parsers(stack: SourceStack, command: String?) {
	val installation = stack.installation
	val state = installation.read()

    when (command) {
		null, "status" -> {
			println("Active build:    " + stack.parsersVersion)
			println("Bundled build:   " + Ageha.BUNDLED_PARSERS_VERSION)
			println("Sources:         " + stack.registry.availableSources().size)
			state.pinnedVersion?.let { println("Pinned to:       " + it) }
			state.lastKnownGoodVersion?.let { println("Roll back to:    " + it) }
			if (state.rejected.isNotEmpty()) {
				println()
				println("Refused builds (never retried):")
				state.rejected.forEach { (version, reason) ->
					println("  " + version + " -- " + reason)
				}
			}
		}

		"check" -> {
			val cookieJar = PersistentCookieJar(File(AgehaPaths.dataDir, "update-cookies.json"))
			val service = ParsersUpdateService(
				httpClient = AgehaHttpClient.build(cookieJar),
				installation = installation,
				cookieJar = cookieJar,
			)
			println("Checking " + "Kotatsu-Redo/kotatsu-parsers-redo" + " for a newer build...")
			when (val outcome = service.checkForUpdate()) {
				is UpdateOutcome.UpToDate -> println("Up to date on " + outcome.version + ".")
				is UpdateOutcome.Pinned -> println("Pinned to " + outcome.version + "; not checking.")
				is UpdateOutcome.Ready -> {
					println("Build " + outcome.version + " passed the compatibility gate.")
					println("  " + outcome.sourceCount + " sources, sha256 " + outcome.sha256.take(16) + "...")
					println("  Run 'parsers activate " + outcome.version + "' to use it.")
				}

				is UpdateOutcome.Rejected -> {
					println(outcome.userMessage)
					println("  Reason: " + outcome.reason)
				}

				is UpdateOutcome.PreviouslyRejected ->
					println("Build " + outcome.version + " was refused earlier: " + outcome.reason)

				is UpdateOutcome.CheckFailed -> println("Could not check: " + outcome.reason)
			}
		}

		"rollback" -> {
			val target = installation.rollBack()
			if (target == null) {
				println("Nothing to roll back to.")
			} else {
				println("Rolled back to " + target + ". It loads on next start.")
			}
		}

		else -> {
			System.err.println("Unknown parsers command: " + command)
			System.err.println("Try: parsers [status|check|rollback]")
			exitProcess(2)
		}
	}
}

private suspend fun search(stack: SourceStack, sourceName: String, query: String) {
	val client = stack.registry.clientFor(sourceName)
	println("Searching " + client.descriptor.title + " (" + client.domain + ") for: " + query)
	println()
	val order = preferredOrder(client, AgehaSortOrder.RELEVANCE)
	val results = client.list(offset = 0, order = order, filter = AgehaFilter.search(query))
	if (results.isEmpty()) {
		println("No results.")
		return
	}
	results.forEachIndexed { index, manga ->
		println((index + 1).toString().padStart(3) + ". " + manga.title)
		manga.authors.takeIf { it.isNotEmpty() }?.let {
			println("      by " + it.joinToString(", "))
		}
		manga.state?.let { println("      " + it) }
		manga.rating?.let { println("      rating " + "%.2f".format(it)) }
		println("      " + manga.publicUrl)
		println("      url=" + manga.url)
	}
}

private suspend fun details(stack: SourceStack, sourceName: String, query: String, index: Int) {
	val client = stack.registry.clientFor(sourceName)
	val full = client.details(pick(client, query, index))

	println(full.title)
	println("=".repeat(full.title.length.coerceAtMost(80)))
	full.altTitles.takeIf { it.isNotEmpty() }?.let { println("Also: " + it.joinToString(" / ")) }
	full.authors.takeIf { it.isNotEmpty() }?.let { println("By: " + it.joinToString(", ")) }
	full.state?.let { println("State: " + it) }
	full.contentRating?.let { println("Rating: " + it) }
	println("Tags: " + full.tags.joinToString(", ") { it.title })
	full.description?.let {
		println()
		println(it.take(400).replace(Regex("<[^>]+>"), "").trim())
	}
	println()

	val chapters = full.chapters.orEmpty()
	val branches = full.chaptersByBranch()
	println("" + chapters.size + " chapters across " + branches.size + " branch(es)")
	chapters.take(10).forEach {
		println("  " + (it.number?.toString() ?: "?").padStart(6) + "  " + (it.title ?: "(untitled)"))
	}
	if (chapters.size > 10) {
		println("  ... " + (chapters.size - 10) + " more")
	}
}

private suspend fun pages(stack: SourceStack, sourceName: String, query: String, index: Int) {
	val client = stack.registry.clientFor(sourceName)
	val full = client.details(pick(client, query, index))
	val chapter = full.chapters.orEmpty().firstOrNull()
		?: error("'" + full.title + "' has no chapters on " + client.descriptor.title + ".")

	val pages = client.pages(chapter)
	println(full.title + " -- chapter " + (chapter.number?.toString() ?: "?"))
	println("" + pages.size + " pages")

	// Resolving a page url is a second request on many sources. A handful is enough to prove the
	// chain end to end without hammering the host.
	pages.take(3).forEach { page ->
		println("  " + client.pageUrl(page))
	}
	if (pages.size > 3) {
		println("  ... " + (pages.size - 3) + " more")
	}
}

/**
 * Search, then take the [index]-th result.
 *
 * Addressing by search rather than by url is deliberate: a source-relative url is meaningless
 * without the listing it came from, and looking one up would mean paging a whole catalogue.
 */
private suspend fun pick(client: MangaSourceClient, query: String, index: Int): AgehaManga {
	val order = preferredOrder(client, AgehaSortOrder.RELEVANCE)
	val results = client.list(offset = 0, order = order, filter = AgehaFilter.search(query))
	if (results.isEmpty()) {
		error("No results for '" + query + "' on " + client.descriptor.title + ".")
	}
	return results.getOrNull(index)
		?: error(
			"Only " + results.size + " result(s) for '" + query + "'; index " + index + " is out of range.",
		)
}

/** Pick [preferred] if the source supports it, otherwise whatever it does support. */
private fun preferredOrder(client: MangaSourceClient, preferred: AgehaSortOrder): AgehaSortOrder =
	if (preferred in client.availableSortOrders) preferred else client.availableSortOrders.first()

/**
 * Report a failure in terms of what the user can do next.
 *
 * [SourceFailure.MissingJsRuntime] gets its own message deliberately. Ageha ships without a
 * JavaScript backend, so this is the *expected* outcome for about 20 of 1360 sources. It must not
 * read like a crash, and it must not read like the network is down.
 */
private fun reportFailure(failure: SourceFailure) {
	System.err.println()
	when (failure) {
		is SourceFailure.MissingJsRuntime -> {
			System.err.println(
				"'" + failure.sourceName + "' needs JavaScript support that is not installed.",
			)
			System.err.println("  Capability: " + failure.capability)
			if (failure.capability.requiresBrowser) {
				System.err.println("  This source needs the optional browser component.")
				System.err.println("  It is not built yet; it lands in Milestone 8. Roughly 20 of")
				System.err.println("  1360 sources need it. The rest work without it.")
			} else {
				System.err.println("  This one is covered by the bundled script engine, which also")
				System.err.println("  lands in Milestone 8. Until then this source cannot run its")
				System.err.println("  anti-bot fallback path.")
			}
		}

		is SourceFailure.ChallengeRequired -> System.err.println(
			"'" + failure.sourceName + "' is behind a verification challenge at " + failure.url,
		)

		is SourceFailure.Blocked -> {
			System.err.println(
				"'" + failure.sourceName + "' refused the request (HTTP " + failure.statusCode + ").",
			)
			System.err.println("  The server answered, so this is not a connectivity problem.")
			System.err.println("  Usually bot protection. A different mirror may work, or this")
			System.err.println("  source may need the browser component.")
		}

		is SourceFailure.UnknownSource -> {
			System.err.println("No source named '" + failure.sourceName + "' in this parsers build.")
			System.err.println("Run 'sources' to list what is available.")
		}

		is SourceFailure.RateLimited -> {
			val wait = failure.retryAfterMillis?.let { " Retry in " + (it / 1000) + "s." }.orEmpty()
			System.err.println("'" + failure.sourceName + "' is rate limiting us." + wait)
		}

		else -> {
			System.err.println(failure.message)
			failure.cause?.let { System.err.println("  caused by: " + it) }
		}
	}
}

private inline fun requireArgs(args: Array<String>, count: Int, block: () -> Unit) {
	if (args.size < count) {
		System.err.println("'" + args[0] + "' needs " + (count - 1) + " argument(s).")
		printUsage()
		exitProcess(2)
	}
	block()
}

private fun printUsage() {
	System.err.println(
		"""
		Ageha source CLI -- proves the parsers work on desktop.

		  import  <backup.zip>          import a Kotatsu-Redo Android backup
		  export  [backup.zip]          write a backup of the local database
		  sync    [run|status|login|logout]
		                                sync with a kotatsu-syncserver
		  library                       what is in the local database
		  parsers [check|rollback]      show the loaded parsers build, or update it
		  sources [filter]              list sources in the loaded parsers build
		  search  <SOURCE> <query>      search one source
		  details <SOURCE> <query> [n]  details and chapters for search result n (default 0)
		  pages   <SOURCE> <query> [n]  page image urls for the first chapter of result n
		  smoke   [--sample n] [--seed s]
		                                exercise a random sample of sources end to end

		SOURCE is a source name from 'sources', for example MANGADEX.

		Every command except 'sources', 'library', 'import' and 'export' hits the live internet.
		""".trimIndent(),
	)
}

/** Reads `--name value` from the argument list. The CLI has too few options to need a parser. */
private fun flag(args: Array<String>, name: String): String? {
	val index = args.indexOf(name)
	return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
}
