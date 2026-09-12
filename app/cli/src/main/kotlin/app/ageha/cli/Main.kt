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
import app.ageha.core.data.DefaultSources
import app.ageha.core.data.SourceRepository
import app.ageha.core.database.AgehaDatabaseFactory
import app.ageha.core.network.AgehaHttpClient
import app.ageha.core.network.AgehaPaths
import app.ageha.core.network.PersistentCookieJar
import app.ageha.core.network.UserAgents
import app.ageha.core.browser.BrowserComponent
import app.ageha.core.browser.BrowserInstallState
import app.ageha.core.browser.JcefJsRuntime
import app.ageha.core.js.CompositeJsRuntime
import app.ageha.core.js.RhinoJsRuntime
import app.ageha.core.source.SiteLinks
import kotlinx.coroutines.launch
import app.ageha.core.parsers.Ageha
import app.ageha.core.parsers.ParsersUpdateService
import app.ageha.core.parsers.UpdateOutcome
import app.ageha.core.source.MangaSourceClient
import app.ageha.core.parsers.SourceStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.PrintStream
import kotlinx.coroutines.CancellationException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.io.IOException
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
	// `--browser` starts the optional Chromium component, downloading it first if this machine
	// has never had it. Off by default because it costs a ~200MB fetch and several seconds of
	// startup, and because the overwhelming majority of what this CLI is used for -- listing
	// sources, searching, reading a chapter list -- never needs a browser at all.
	//
	// It exists mainly so the browser tiers can be exercised without launching the UI: `pages
	// ALLMANGA <url> --browser` is the end-to-end check that the whole path works, from
	// MangaLoaderContext.interceptWebViewRequests down to a real page load.
	val wantsBrowser = args.contains("--browser")
	if (wantsBrowser) {
		// CEF delivers its callbacks on the AWT event thread, whose default handler prints the
		// exception and nothing else -- no stack, no cause. A handler that fails silently there
		// looks exactly like a site that returned nothing, which is the most expensive kind of
		// bug to chase. This makes the difference visible.
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			System.err.println("Uncaught on " + thread.name + ": " + error)
			error.printStackTrace()
		}
	}
	val browser = BrowserComponent(
		installDir = File(AgehaPaths.dataDir, "browser"),
		cacheDir = File(AgehaPaths.cacheDir, "browser"),
	)
	if (wantsBrowser) {
		runBlocking {
			// Progress on stderr, so that piping the command's output somewhere is not polluted
			// by a download that only happens once.
			val reporter = launch {
				browser.state.collect { state ->
					if (state is BrowserInstallState.Working) {
						System.err.println(
							"  " + state.step + (state.fraction?.let { " ${(it * 100).toInt()}%" } ?: ""),
						)
					}
				}
			}
			val outcome = browser.install()
			reporter.cancel()
			if (outcome is BrowserInstallState.Failed) {
				System.err.println("Browser component unavailable: " + outcome.reason)
				exitProcess(1)
			}
			System.err.println("Browser component ready.")
		}
	}
	val jsRuntimeForCli = CompositeJsRuntime(
		script = RhinoJsRuntime(),
		browser = JcefJsRuntime(browser).takeIf { wantsBrowser },
	)
	val stack = Ageha.createSourceStack(
		jsRuntime = CompositeJsRuntime(
			script = RhinoJsRuntime(),
			browser = JcefJsRuntime(browser).takeIf { wantsBrowser },
		),
	)
	try {
		runBlocking {
			when (val command = args[0]) {
				"sources" -> listSources(stack, args.getOrNull(1))
				"defaults" -> defaults(stack, apply = args.contains("--apply"))
				"parsers" -> parsers(stack, args.getOrNull(1), args.getOrNull(2))
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

				// A window onto what a page actually does, for diagnosing a browser-tier source.
				// Loads the url and prints every request matching the pattern, which answers the
				// question a failing parser cannot: is the site making the call at all?
				"intercept" -> requireArgs(args, 3) {
					val captured = jsRuntimeForCli.interceptRequests(
						pageUrl = args[1],
						filterScript = null,
						pageScript = null,
						maxRequests = flag(args, "--max")?.toIntOrNull() ?: 40,
						timeoutMillis = flag(args, "--timeout")?.toLongOrNull() ?: 20_000L,
						urlPattern = args[2].takeIf { it != "*" }?.let(::Regex),
					)
					println("" + captured.size + " request(s) matched")
					captured.forEach { println("  " + it.method + " " + it.url) }
				}

				// The page tier exactly as a parser meets it: load the url, ask the script until it
				// answers or time runs out, print the JSON-encoded answer. For checking what a
				// source's script sees without going through the source.
				"eval" -> requireArgs(args, 3) {
					val answer = jsRuntimeForCli.evaluateInPage(
						baseUrl = args[1],
						script = args[2],
						timeoutMillis = flag(args, "--timeout")?.toLongOrNull() ?: 20_000L,
					)
					when {
						answer == null -> println("(no answer)")
						answer.length > EVAL_PRINT_LIMIT ->
							println(answer.take(EVAL_PRINT_LIMIT) + "… (" + answer.length + " chars)")
						else -> println(answer)
					}
				}

				// A check passed in the browser, as the Cloudflare interceptor passes it: which cookies
				// came back, and how long it took. Names only -- a clearance cookie is a credential.
				"clear" -> requireArgs(args, 2) {
					val started = System.currentTimeMillis()
					val cookies = jsRuntimeForCli.openInteractive(
						url = args[1],
						userAgent = flag(args, "--ua") ?: UserAgents.CHROME_DESKTOP,
					)
					val took = (System.currentTimeMillis() - started) / 1000
					if (cookies == null) {
						println("Not cleared after " + took + "s")
					} else {
						println("Cleared in " + took + "s, " + cookies.size + " cookie(s):")
						cookies.forEach { println("  " + it.name + "  domain=" + it.domain) }
					}
				}

				// A second argument asks the question as that source rather than as the one the
				// library picks, which is what the Add site dialog does once a language is chosen.
				"resolve" -> requireArgs(args, 2) { resolve(stack, args[1], args.getOrNull(2)) }

				"pages" -> requireArgs(args, 3) {
					pages(
						stack = stack,
						sourceName = args[1],
						query = args[2],
						index = args.getOrNull(3)?.toIntOrNull() ?: 0,
						chapterIndex = flag(args, "--chapter")?.toIntOrNull() ?: 0,
					)
				}
				// What a source's own parser lets you change, and changing it. The mirror domain
				// is the one that matters: when a site moves, this is the difference between a
				// dead source and a working one.
				"config" -> requireArgs(args, 2) {
					sourceConfig(stack, args[1], args.getOrNull(2), args.getOrNull(3))
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

/**
 * Show, set or clear one source's own settings.
 *
 * With no [key], every option the source's parser declares is listed, with its current value, the
 * choices it offers, and a marker on the one in force. With a [key] and a [value] the setting is
 * written; with a [key] and `default` as the value the override is removed.
 *
 * This is the remedy for the commonest way a source dies. 258 sources in the bundled build declare
 * the mirrors their site is reachable at, sites move between them constantly, and the domain a
 * parser happens to default to is not always the one that answers.
 */
private fun sourceConfig(stack: SourceStack, sourceName: String, key: String?, value: String?) {
	val descriptor = stack.registry.descriptorFor(sourceName)
		?: error("No source named '" + sourceName + "'. Try: agehacli sources")

	if (key == null) {
		val settings = stack.registry.sourceSettings(sourceName)
		println(descriptor.title + "  (" + descriptor.name + ")")
		if (settings.isEmpty()) {
			println("  nothing configurable")
			return
		}
		for (setting in settings) {
			val origin = if (setting.isOverridden) "set by you" else "parser default"
			println("  " + setting.key + " = " + setting.value + "  [" + origin + "]")
			if (setting.presets.size > 1) {
				for (choice in setting.presets) {
					val marker = if (choice.value == setting.value) " *" else "  "
					val label = if (choice.label == choice.value) "" else "  -- " + choice.label
					println("     " + marker + " " + choice.value + label)
				}
			}
		}
		println()
		println("To change one:   agehacli config " + descriptor.name + " <key> <value>")
		println("To reset one:    agehacli config " + descriptor.name + " <key> default")
		return
	}

	// `default` rather than an empty argument, because a shell makes an empty argument awkward to
	// pass and impossible to see in a scrollback.
	val newValue = value?.takeUnless { it == "default" }
	if (!stack.registry.applySourceSetting(sourceName, key, newValue)) {
		error("Could not change '" + key + "' for " + descriptor.name + ".")
	}
	val now = stack.registry.sourceSettings(sourceName).firstOrNull { it.key == key }
	if (newValue == null) {
		println("Reset " + key + " to the parser default" + (now?.let { ": " + it.value } ?: "") + ".")
	} else {
		println("Set " + key + " to " + newValue + " for " + descriptor.name + ".")
	}
	// The domain is worth reading back: a typo lands silently otherwise, and the next thing the
	// user does is wonder why the source still does not work.
	if (now != null && now.kind == app.ageha.core.model.SourceSetting.Kind.DOMAIN) {
		println("The source now reads " + now.value + ".")
	}
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
 * The default source set, and a way to apply it to an existing installation.
 *
 * The first-run seed only fires on an empty table, which is right -- see
 * `SourceRepository.seedDefaultsOnFirstRun` -- and leaves anyone who installed Ageha earlier
 * without the defaults and with no way to ask for them. This is that way. It is deliberately not
 * a button in Settings: it adds sources the user has never ruled on and touches nothing they have,
 * so it wants to be an explicit, occasional act rather than something sitting next to a switch.
 *
 * Without `--apply` it reports what it would do and writes nothing.
 */
private suspend fun defaults(stack: SourceStack, apply: Boolean) {
	val catalogue = stack.registry.availableSources()
	val defaults = DefaultSources.from(catalogue)
	println("Parsers build: " + stack.registry.parsersVersion)
	println(
		"" + defaults.size + " of " + catalogue.size +
			" sources are English or multi-language, not adult, and not flagged broken.",
	)

	val database = AgehaDatabaseFactory.open(File(AgehaPaths.dataDir, "ageha.db"))
	try {
		val repository = SourceRepository(database.sourcesDao(), stack.registry)
		if (!apply) {
			// Read-only preview, computed the same way the write is, so the two cannot disagree.
			val decided = database.sourcesDao().all().mapTo(mutableSetOf()) { it.source }
			val missing = defaults.filterNot { it.name in decided }
			println("" + (defaults.size - missing.size) + " already have a setting of their own.")
			println()
			if (missing.isEmpty()) {
				println("Nothing to add. Run with --apply to write anyway; it would be a no-op.")
			} else {
				println("Would enable " + missing.size + ":")
				missing.forEach { println("  " + it.name.padEnd(28) + " " + it.title) }
				println()
				println("Run 'defaults --apply' to enable them.")
			}
			return
		}

		val added = repository.enableDefaults()
		if (added.isEmpty()) {
			println()
			println("Nothing to add -- every default source already has a setting of its own.")
		} else {
			println()
			println("Enabled " + added.size + ":")
			added.forEach { println("  " + it) }
		}
	} finally {
		database.close()
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
private suspend fun parsers(stack: SourceStack, command: String?, version: String?) {
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

		// What 'check' tells you to run once a build has passed the gate. It was suggested for a
		// release before it existed, which left a downloaded, verified build with no way in.
		"activate" -> {
			val target = version ?: error("Say which build: parsers activate <version>")
			val cookieJar = PersistentCookieJar(File(AgehaPaths.dataDir, "update-cookies.json"))
			ParsersUpdateService(
				httpClient = AgehaHttpClient.build(cookieJar),
				installation = installation,
				cookieJar = cookieJar,
			).activate(target)
			println("Activated " + target + ". It loads on next start.")
		}

		else -> {
			System.err.println("Unknown parsers command: " + command)
			System.err.println("Try: parsers [status|check|activate <version>|rollback]")
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

/**
 * Which source handles a pasted link, the same question the Add site dialog asks.
 *
 * Exists so the resolver can be exercised without the UI -- and so "does Ageha have this site"
 * has an answer from a terminal, which is where a bug report about a missing site usually starts.
 */
private suspend fun resolve(stack: SourceStack, input: String, asSource: String? = null) {
	val link = SiteLinks.normalise(input) ?: error("'" + input + "' is not a link.")
	val host = SiteLinks.hostOf(link)
	val found = if (asSource == null) {
		stack.registry.resolveLink(link)
	} else {
		stack.registry.resolveLinkAs(link, asSource)
	}
	if (found == null) {
		println("No source in parsers build " + stack.registry.parsersVersion + " handles " + host + ".")
		return
	}
	val descriptor = stack.registry.descriptorFor(found.sourceName)
	println(host + " -> " + (descriptor?.title ?: found.sourceName) + "  [" + found.sourceName + "]")
	if (descriptor?.isBroken == true) println("  flagged broken upstream")
	val manga = found.manga
	if (manga == null) {
		println("  the site itself, not a particular title")
	} else {
		println("  manga: " + manga.title)
		println("         " + manga.publicUrl)
	}
	// The reason this is worth printing: upstream's resolver names the first source in the build's
	// declaration order that serves the host, so a site publishing in 42 languages resolves to
	// whichever sorts first and the rest are invisible from the answer above.
	if (found.alternatives.isEmpty()) return
	println()
	println("  " + found.alternatives.size + " other source(s) serve " + host + ":")
	found.alternatives.forEach { name ->
		val other = stack.registry.descriptorFor(name)
		println("    " + (other?.title ?: name) + "  [" + name + "]")
	}
}

private suspend fun pages(
	stack: SourceStack,
	sourceName: String,
	query: String,
	index: Int,
	chapterIndex: Int,
) {
	val client = stack.registry.clientFor(sourceName)
	val full = client.details(pick(client, query, index))
	val chapters = full.chapters.orEmpty()
	// Selectable rather than always the first, because the first chapter is a bad test subject
	// on a surprising number of sources: it is where prototypes, one-shot prologues and "chapter
	// 0" placeholders live, and several of those legitimately have no pages at all. Debugging a
	// page fetch against one of those attributes the source's emptiness to your own code.
	val chapter = chapters.getOrNull(chapterIndex)
		?: error(
			"'" + full.title + "' has " + chapters.size + " chapter(s) on " +
				client.descriptor.title + "; no index " + chapterIndex + ".",
		)

	val pages = client.pages(chapter)
	println(full.title + " -- chapter " + (chapter.number?.toString() ?: "?"))
	println("" + pages.size + " pages")

	// Resolving a page url is a second request on many sources. A handful is enough to prove the
	// chain end to end without hammering the host.
	val urls = pages.take(3).map { client.pageUrl(it) }
	urls.forEach { println("  " + it) }
	if (pages.size > 3) {
		println("  ... " + (pages.size - 3) + " more")
	}

	// The last link of the chain: the image itself, fetched as the reader fetches it -- same client,
	// same per-source headers. A url that resolves perfectly and an image the CDN refuses print
	// identically until something asks for the bytes. ComicK's did exactly that: three good urls
	// here, and a 403 on every one in the reader.
	urls.firstOrNull()?.let { println("First image: " + fetchImage(stack, client, it)) }
}

private suspend fun fetchImage(stack: SourceStack, client: MangaSourceClient, url: String): String =
	withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(url)
			.apply { client.imageRequestHeaders().forEach { (name, value) -> header(name, value) } }
			.build()
		// The image client, which is what the reader uses: the base client has no parser dispatch,
		// so fetching through it would report a source healthy whose pages the reader cannot
		// decode. That is the failure this command exists to catch.
		//
		// A transport failure is reported, not thrown. A source whose CDN has gone -- a dead host,
		// a timeout, a refused connection -- is the ordinary case this line is here to reveal, and
		// answering it with a forty-frame stack trace buries the one line that matters under
		// OkHttp's internals. The reader shows the same thing as a message on the page.
		runCatching {
			stack.imageHttpClient.newCall(request).execute().use { response ->
				"HTTP " + response.code + ", " + (response.header("Content-Type") ?: "no content type") +
					", " + response.body.bytes().size + " bytes"
			}
		}.getOrElse { failure ->
			when (failure) {
				is CancellationException -> throw failure
				is IOException -> failure.describeTransport()
				else -> throw failure
			}
		}
	}

/** A one-line account of why an image never arrived, in the terms the reader uses. */
private fun IOException.describeTransport(): String = when (this) {
	is UnknownHostException -> "could not be reached -- no such host (" + message + ")"
	is SocketTimeoutException -> "timed out"
	else -> (this::class.simpleName ?: "failed") + ": " + (message ?: "no detail")
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
				// Pointed at the flag rather than at a milestone. This used to say the component
				// "lands in Milestone 8", which stayed true for exactly as long as it was not built
				// and then became the one line in the CLI telling people a working feature was
				// missing.
				System.err.println("  This source needs the optional browser component. Run the")
				System.err.println("  same command again with --browser to download it (about")
				System.err.println("  200MB, once) and use it. Roughly 20 of 1360 sources need it.")
			} else {
				System.err.println("  The bundled script engine should cover this, so seeing it")
				System.err.println("  means the engine failed to start -- a bug worth reporting.")
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
		  config  <SOURCE>              what that source's parser lets you change
		  config  <SOURCE> <key> <value>
		                                change it; 'default' as the value clears the override
		  defaults [--apply]            the default source set; --apply enables the missing ones
		  search  <SOURCE> <query>      search one source
		  details <SOURCE> <query> [n]  details and chapters for search result n (default 0)
		  pages   <SOURCE> <query> [n]  page image urls for the first chapter of result n
		  smoke   [--sample n] [--seed s]
		                                exercise a random sample of sources end to end
		  resolve <link>                which source reads a pasted site or manga link
		  intercept <url> <pattern> --browser
		                                requests a page makes that match the pattern
		  eval    <url> <script> --browser [--timeout ms]
		                                a script's answer in the loaded page, as a parser gets it
		  clear   <url> --browser [--ua agent]
		                                pass a Cloudflare check in the browser; lists cookie names

		SOURCE is a source name from 'sources', for example MANGADEX.

		Every command except 'sources', 'library', 'import' and 'export' hits the live internet.
		""".trimIndent(),
	)
}

/** How much of an `eval` answer is printed. A page's whole HTML is tens of thousands of characters. */
private const val EVAL_PRINT_LIMIT = 2_000

/** Reads `--name value` from the argument list. The CLI has too few options to need a parser. */
private fun flag(args: Array<String>, name: String): String? {
	val index = args.indexOf(name)
	return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
}
