package app.ageha.cli

import app.ageha.core.model.AgehaFilter
import app.ageha.core.model.AgehaManga
import app.ageha.core.model.AgehaSortOrder
import app.ageha.core.model.SourceFailure
import app.ageha.core.parsers.Ageha
import app.ageha.core.parsers.MangaSourceClient
import app.ageha.core.parsers.SourceStack
import kotlinx.coroutines.runBlocking
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
	val stack = Ageha.createSourceStack()
	try {
		runBlocking {
			when (val command = args[0]) {
				"sources" -> listSources(stack, args.getOrNull(1))
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

		  sources [filter]              list sources in the bundled parsers build
		  search  <SOURCE> <query>      search one source
		  details <SOURCE> <query> [n]  details and chapters for search result n (default 0)
		  pages   <SOURCE> <query> [n]  page image urls for the first chapter of result n

		SOURCE is a source name from 'sources', for example MANGADEX.

		Every command except 'sources' hits the live internet.
		""".trimIndent(),
	)
}
