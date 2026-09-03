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
enum class DownloadStatus { QUEUED, RUNNING, COMPLETE, PARTIAL, FAILED, CANCELLED }

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
	private val perSourceConcurrency: Int = DEFAULT_PER_SOURCE_CONCURRENCY,
) {

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
		_jobs.value.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
			.forEach { cancel(it.key) }
	}

	/** Drop finished rows. The queue is a work list, not a history. */
	fun clearFinished() {
		_jobs.update { jobs ->
			jobs.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
		}
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
