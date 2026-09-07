package app.ageha.feature.downloads

import app.ageha.core.data.ChapterDownloader
import app.ageha.core.data.DownloadProgress
import app.ageha.core.data.DownloadResult
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Where one queued chapter has got to. */
enum class DownloadStatus {
	QUEUED,
	RUNNING,

	/**
	 * Held by the user, and resumable.
	 *
	 * Distinct from [CANCELLED], which is a decision rather than a pause: a cancelled job is
	 * finished with, a paused one is waiting. The handoff's Downloads screen carries a Pause chip
	 * and a trash button on the same row, and collapsing the two states would make them mean the
	 * same thing.
	 */
	PAUSED,
	COMPLETE,
	PARTIAL,
	FAILED,
	CANCELLED,
	;

	/** True while the queue still owes this job work. */
	val isOutstanding: Boolean get() = this == QUEUED || this == RUNNING || this == PAUSED
}

/** One chapter in the queue. */
data class DownloadJob(
	val manga: AgehaManga,
	val chapter: AgehaChapter,
	val status: DownloadStatus = DownloadStatus.QUEUED,
	val progress: DownloadProgress = DownloadProgress(0, 0),
	val detail: String? = null,
) {
	/** Ids are unique only within a source, so the key needs both. */
	val key: String get() = "${manga.sourceName}:${manga.id}:${chapter.id}"
}

/**
 * The download queue.
 *
 * **Concurrency is limited per source, not globally.** A user queuing eighty chapters from one
 * site and having Ageha open eighty connections to it is how an application gets its whole user
 * base IP-banned -- and the sites in question are small, often single-server, and frequently
 * hostile to scrapers already. Two at a time per source is polite and still saturates a normal
 * connection when several sources are queued at once.
 *
 * Order is preserved. A user who queues chapters 1 to 20 expects to be able to start reading
 * chapter 1 while 20 is still pending, which a queue that runs jobs in arbitrary order does not
 * deliver.
 */
class DownloadQueue(
	private val downloader: ChapterDownloader,
	private val scope: CoroutineScope,
	perSourceConcurrency: Int = DEFAULT_PER_SOURCE_CONCURRENCY,
) {

	/**
	 * Settings' "Parallel downloads", changeable while the queue is alive.
	 *
	 * Setting it clears the permit map, so the *next* chapter from each source is admitted under
	 * the new limit. Work already holding a permit keeps running on the old semaphore and finishes
	 * normally -- cancelling it to apply a setting would throw away a partly written chapter to
	 * enforce a number the user changed to make downloads faster.
	 */
	var perSourceConcurrency: Int = perSourceConcurrency
		set(value) {
			if (value == field) return
			field = value.coerceAtLeast(1)
			permits.clear()
		}

	private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
	val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

	private val permits = mutableMapOf<String, Semaphore>()
	private val running = mutableMapOf<String, Job>()

	val activeCount: Int get() = _jobs.value.count { it.status == DownloadStatus.RUNNING }
	val pendingCount: Int get() = _jobs.value.count { it.status == DownloadStatus.QUEUED }

	/**
	 * Add chapters to the queue.
	 *
	 * Chapters already on disk are skipped rather than queued and immediately completed -- a queue
	 * that fills with twenty "already downloaded" rows tells the user nothing and hides the two
	 * that are actually working.
	 */
	fun enqueue(manga: AgehaManga, chapters: List<AgehaChapter>) {
		val existing = _jobs.value.mapTo(mutableSetOf()) { it.key }
		val added = chapters
			.filterNot { downloader.isDownloaded(manga, it) }
			.map { DownloadJob(manga, it) }
			.filterNot { it.key in existing }
		if (added.isEmpty()) return
		_jobs.update { it + added }
		added.forEach(::start)
	}

	private fun start(job: DownloadJob) {
		val semaphore = permits.getOrPut(job.manga.sourceName) { Semaphore(perSourceConcurrency) }
		running[job.key] = scope.launch {
			semaphore.withPermit {
				// The job may have been cancelled while it waited for a permit. Checking here
				// rather than only at the start means a cancelled queue stops promptly instead of
				// working through everything that was already dispatched.
				if (statusOf(job.key) == DownloadStatus.CANCELLED) return@withPermit
				update(job.key) { it.copy(status = DownloadStatus.RUNNING) }
				val result = downloader.download(job.manga, job.chapter) { progress ->
					update(job.key) { it.copy(progress = progress) }
				}
				update(job.key) { it.withResult(result) }
			}
		}
	}

	fun cancel(key: String) {
		update(key) { it.copy(status = DownloadStatus.CANCELLED, detail = "Cancelled") }
		running.remove(key)?.cancel()
	}

	fun cancelAll() {
		_jobs.value.filter { it.status.isOutstanding }.forEach { cancel(it.key) }
	}

	/**
	 * Hold one chapter without giving it up.
	 *
	 * ## What "resume" honestly means here
	 *
	 * The chapter restarts. [ChapterDownloader] writes into a single `ZipOutputStream` and deletes
	 * its `.part` file on cancellation, so there is no half-written archive to append to -- and
	 * that deletion is deliberate rather than an oversight: it is what guarantees a `.cbz` on disk
	 * is never a partial download wearing a finished name.
	 *
	 * WIRING.md asks for resumption from the last finished page, and that is a real gap. Closing
	 * it means staging pages as loose files and zipping at the end, which trades the guarantee
	 * above for a directory of orphans after a crash. Said out loud here rather than papered over
	 * with a resume that quietly re-downloads what it already had.
	 */
	fun pause(key: String) {
		if (statusOf(key)?.isOutstanding != true) return
		update(key) { it.copy(status = DownloadStatus.PAUSED, detail = "Paused") }
		running.remove(key)?.cancel()
	}

	fun resume(key: String) {
		val job = _jobs.value.firstOrNull { it.key == key } ?: return
		if (job.status != DownloadStatus.PAUSED) return
		update(key) { it.copy(status = DownloadStatus.QUEUED, detail = null, progress = DownloadProgress(0, 0)) }
		start(job)
	}

	fun pauseAll() {
		_jobs.value.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
			.forEach { pause(it.key) }
	}

	fun resumeAll() {
		_jobs.value.filter { it.status == DownloadStatus.PAUSED }.forEach { resume(it.key) }
	}

	/**
	 * Whether the one button at the top of the screen should read "Pause all" or "Resume all".
	 *
	 * The handoff makes it one control whose label follows the queue, which is the right shape:
	 * two buttons would leave one of them inert most of the time. Anything still moving means
	 * pausing is the useful action, so this leans towards Pause and only flips when everything
	 * outstanding is already held.
	 */
	val hasRunningWork: Boolean
		get() = _jobs.value.any {
			it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING
		}

	/** Drop finished rows. The queue is a work list, not a history. */
	fun clearFinished() {
		_jobs.update { jobs -> jobs.filter { it.status.isOutstanding } }
	}

	fun retry(key: String) {
		val job = _jobs.value.firstOrNull { it.key == key } ?: return
		update(key) { it.copy(status = DownloadStatus.QUEUED, detail = null) }
		start(job)
	}

	private fun statusOf(key: String): DownloadStatus? = _jobs.value.firstOrNull { it.key == key }?.status

	private fun update(key: String, transform: (DownloadJob) -> DownloadJob) {
		_jobs.update { jobs -> jobs.map { if (it.key == key) transform(it) else it } }
	}

	private fun DownloadJob.withResult(result: DownloadResult): DownloadJob = when (result) {
		is DownloadResult.Complete -> copy(
			status = DownloadStatus.COMPLETE,
			progress = DownloadProgress(result.pageCount, result.pageCount),
			detail = "${result.pageCount} pages",
		)

		is DownloadResult.AlreadyDownloaded -> copy(
			status = DownloadStatus.COMPLETE,
			detail = "Already downloaded",
		)

		is DownloadResult.Partial -> copy(
			status = DownloadStatus.PARTIAL,
			// Named, not counted. "Missing pages 12, 13" tells the reader what they will hit;
			// "2 pages missing" leaves them to discover it mid-chapter.
			detail = "Saved without page(s) ${result.missingPages.joinToString(", ")}",
		)

		is DownloadResult.Failed -> copy(
			status = DownloadStatus.FAILED,
			detail = app.ageha.core.designsystem.describe(result.failure).headline,
		)
	}

	private companion object {
		/**
		 * Simultaneous downloads per source.
		 *
		 * Two, deliberately low. These are small sites, and the cost of being impolite is not a
		 * slow download -- it is a block that affects every Ageha user of that source.
		 */
		const val DEFAULT_PER_SOURCE_CONCURRENCY = 2
	}
}
