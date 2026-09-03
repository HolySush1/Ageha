package app.ageha.cli

import app.ageha.core.model.AgehaFilter
import app.ageha.core.model.AgehaSortOrder
import app.ageha.core.model.SourceFailure
import app.ageha.core.parsers.SourceStack
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

/** What happened when one source was exercised. */
private data class SmokeResult(
	val source: String,
	val stage: String,
	val ok: Boolean,
	val detail: String,
	val itemCount: Int = 0,
)

/**
 * Exercise a sample of sources against the live internet.
 *
 * This is the check no unit test can make. The suite proves Ageha still compiles and behaves
 * against a parsers build; only this proves that build can still reach a website and understand
 * what comes back. It is how a dead source is discovered before a user reports it.
 *
 * Three properties make it usable as a CI signal rather than noise:
 *
 *  - **Sampling is seeded and reported.** A different random sample each night would make a
 *    failure impossible to reproduce; printing the seed makes the exact run repeatable.
 *  - **Failures are classified, not just counted.** A handful of dead sources is a normal Tuesday.
 *    The command exits non-zero only when the failures are both numerous *and alike*, which is
 *    what a broken Ageha looks like and what a broken internet does not.
 *  - **Concurrency is bounded and each source is timed out.** A smoke test that hangs on one slow
 *    site tells you nothing and burns a CI runner for six hours.
 */
suspend fun smokeTest(stack: SourceStack, sampleSize: Int, seed: Long) {
	val all = stack.registry.availableSources()
	// Sources upstream already flags as broken are excluded. Including them would guarantee
	// failures that say nothing about whether *Ageha* works.
	val candidates = all.filterNot { it.isBroken }
	val sample = candidates.shuffled(Random(seed)).take(sampleSize.coerceAtMost(candidates.size))

	println("Smoke test of ${sample.size} sources (seed $seed, ${all.size} available)")
	println("Parsers build: ${stack.parsersVersion}")
	println()

	val limiter = Semaphore(CONCURRENCY)
	val results = coroutineScope {
		sample.map { descriptor ->
			async {
				limiter.withPermit { exercise(stack, descriptor.name, descriptor.title) }
			}
		}.awaitAll()
	}

	for (result in results.sortedBy { it.source }) {
		val mark = if (result.ok) "ok  " else "FAIL"
		println("$mark ${result.source.padEnd(24)} ${result.stage.padEnd(10)} ${result.detail}")
	}

	val failed = results.filter { !it.ok }
	println()
	println("${results.size - failed.size} of ${results.size} sources responded.")

	// Grouped by kind, because the kind is the diagnosis. A spread of dead domains, 403s and
	// empty listings is the ordinary state of the scanlation web; a *single* kind accounting for
	// everything points at Ageha instead.
	if (failed.isNotEmpty()) {
		val byStage = failed.groupingBy { it.stage }.eachCount().entries.sortedByDescending { it.value }
		println("Failures by kind: " + byStage.joinToString(", ") { "${it.key} ${it.value}" })
	}

	println()
	println(verdict(results, failed))
	if (looksLikeAgehaBroke(results, failed)) {
		error("smoke test suggests a problem on Ageha's side, not the sites'")
	}
}

/**
 * Whether the failures look like Ageha's fault rather than the internet's.
 *
 * Two conditions, and both must hold. The rate alone is not enough: a first version of this gated
 * on rate only, and a six-source sample that happened to draw a dead domain, a 403 and an empty
 * listing tripped it -- which is a completely normal night, not a regression. Small samples have
 * enormous variance, so the gate needs a sample big enough for a rate to mean anything, *and* a
 * pattern that a scattering of broken websites does not produce.
 */
private fun looksLikeAgehaBroke(results: List<SmokeResult>, failed: List<SmokeResult>): Boolean {
	if (results.size < MIN_SAMPLE_FOR_GATE) return false
	if (failed.size.toDouble() / results.size <= FAILURE_THRESHOLD) return false
	// Sites fail in different ways from each other. Ageha failing -- a broken HTTP stack, a
	// parsers build whose context contract moved -- fails every source the same way.
	val dominantShare = failed.groupingBy { it.stage }.eachCount().values.max().toDouble() / failed.size
	return dominantShare >= DOMINANT_KIND_SHARE
}

private fun verdict(results: List<SmokeResult>, failed: List<SmokeResult>): String = when {
	failed.isEmpty() -> "Every source in the sample worked."
	results.size < MIN_SAMPLE_FOR_GATE ->
		"Sample too small to judge. ${failed.size} failed, which at this size is not a signal -- " +
			"run with --sample $MIN_SAMPLE_FOR_GATE or more to gate on it."
	looksLikeAgehaBroke(results, failed) ->
		"Most of the sample failed the same way. That is not a scattering of broken sites; " +
			"suspect the parsers build or Ageha's HTTP stack."
	else ->
		"${failed.size} source(s) failed, in assorted ways. That is the ordinary state of the " +
			"scanlation web and not a regression in Ageha."
}

/**
 * One source, taken as far as it will go: list, then details, then page urls.
 *
 * Stopping at the first stage that fails, and reporting *which*, is what makes the output
 * diagnostic. "MANGADEX failed" says nothing; "MANGADEX failed at pages" says the listing and
 * metadata parsers are fine and the page extractor is not.
 */
private suspend fun exercise(stack: SourceStack, name: String, title: String): SmokeResult {
	val client = try {
		stack.registry.clientFor(name)
	} catch (failure: SourceFailure) {
		return SmokeResult(name, "open", false, failure.message ?: "could not open")
	}

	return try {
		withTimeout(PER_SOURCE_TIMEOUT_MS) {
			val listing = client.list(
				offset = 0,
				order = client.availableSortOrders.firstOrNull() ?: AgehaSortOrder.UPDATED,
				filter = AgehaFilter.EMPTY,
			)
			if (listing.isEmpty()) {
				return@withTimeout SmokeResult(name, "list", false, "$title returned no items")
			}

			val details = client.details(listing.first())
			val chapters = details.chapters.orEmpty()
			if (chapters.isEmpty()) {
				return@withTimeout SmokeResult(name, "details", false, "no chapters for ${details.title}")
			}

			val pages = client.pages(chapters.first())
			if (pages.isEmpty()) {
				return@withTimeout SmokeResult(name, "pages", false, "no pages in ${chapters.first().title}")
			}

			// Resolving one page url is the last thing that can be wrong, and on several sources
			// it is a separate request -- so a listing that works does not imply a chapter that
			// can be read.
			client.pageUrl(pages.first())
			SmokeResult(name, "pages", true, "${listing.size} listed, ${chapters.size} chapters", pages.size)
		}
	} catch (timeout: TimeoutCancellationException) {
		SmokeResult(name, "timeout", false, "no answer in ${PER_SOURCE_TIMEOUT_MS / 1000}s")
	} catch (failure: SourceFailure) {
		SmokeResult(name, failure.stageHint(), false, failure.message ?: failure::class.simpleName.orEmpty())
	}
}

/** A short word for what kind of failure this was, for the aligned output column. */
private fun SourceFailure.stageHint(): String = when (this) {
	is SourceFailure.Network -> "network"
	is SourceFailure.Blocked -> "blocked"
	is SourceFailure.RateLimited -> "throttled"
	is SourceFailure.ChallengeRequired -> "challenge"
	is SourceFailure.MissingJsRuntime -> "needs-js"
	is SourceFailure.Unparseable -> "parse"
	else -> "failed"
}

/**
 * How many sources are exercised at once.
 *
 * Low on purpose. Every one of these is a real request to a small site, and a nightly job that
 * hammers fifteen of them in parallel is exactly the behaviour that gets an application's user
 * agent blocked -- which would break the app for everyone to test whether it works.
 */
private const val CONCURRENCY = 4

private const val PER_SOURCE_TIMEOUT_MS = 45_000L

/** Above this share failing -- *and* failing alike -- the fault is more likely Ageha's. */
private const val FAILURE_THRESHOLD = 0.6

/**
 * Below this many sources, the failure rate is not evidence of anything.
 *
 * Learned from running it: a sample of six drew a dead domain, a 403 and an empty listing, tripped
 * a rate-only gate, and was a perfectly normal night.
 */
private const val MIN_SAMPLE_FOR_GATE = 10

/** How much of the failure has to be one kind before it stops looking like the internet. */
private const val DOMINANT_KIND_SHARE = 0.8
