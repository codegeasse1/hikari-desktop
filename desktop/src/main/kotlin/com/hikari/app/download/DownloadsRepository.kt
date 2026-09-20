package com.hikari.app.download

import com.hikari.app.HikariApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single source of truth for the download queue. In-memory state is a
 * [StateFlow] the Downloads screen collects; it is mirrored into
 * `downloads.json` ([DownloadStore]) on every status change and throttled
 * during progress.
 *
 * Android ran the queue inside a foreground service so it survived the UI being
 * closed; a desktop window IS the app, so the queue simply lives here and
 * [startPump] keeps as many tasks in flight as the user allows — including
 * while the user is browsing a different screen.
 *
 * Concurrency model: [claimNextQueued] claims the next QUEUED task ATOMICALLY
 * under a StateFlow update and flips it to RUNNING, so two pumps — the one the
 * queue started, plus one started by a fresh enqueue — can never run the same
 * task twice.
 */
object DownloadsRepository {

    private const val DEFAULT_CONCURRENCY = 3
    private const val MIN_CONCURRENCY = 1
    private const val MAX_CONCURRENCY = 10

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()

    /** How many downloads run at once — the user setting, clamped to 1–10. */
    @Volatile
    private var maxConcurrent: Int = DEFAULT_CONCURRENCY

    @Volatile
    private var loaded = false

    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    private val pumpLock = Any()
    private var pumpJob: Job? = null

    /** Called (off the FX thread) whenever a task reaches a terminal state, so
     *  the UI can raise a toast. Set by the shell. */
    @Volatile
    var onTaskFinished: ((DownloadTask) -> Unit)? = null

    private val dir: File get() = HikariApp.instance.filesDir

    fun workDirFor(id: String): File =
        File(File(dir, "downloads"), id.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    /** Where EXPORT copies land: the user's own Downloads folder, so a finished
     *  download is where anyone would look for it. */
    fun exportDir(): File =
        File(File(System.getProperty("user.home"), "Downloads"), "Hikari")

    fun setMaxConcurrent(n: Int) {
        maxConcurrent = n.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)
    }

    fun maxConcurrent(): Int = maxConcurrent

    suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            val stored = runCatching { DownloadStore.load(dir) }.getOrDefault(emptyList())
            _tasks.value = stored.map {
                // A task that was mid-download when the app closed is not
                // running any more — surface it as paused so the user can resume.
                if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.CONVERTING) {
                    it.copy(status = DownloadStatus.PAUSED, bytesPerSec = 0L)
                } else {
                    it
                }
            }
            maxConcurrent = runCatching { HikariApp.instance.store.downloadConcurrency() }
                .getOrDefault(DEFAULT_CONCURRENCY)
                .coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)
            loaded = true
        }
    }

    fun snapshot(): List<DownloadTask> = _tasks.value

    /** The flag the engine polls; set true to stop the in-flight task. */
    fun cancelFlag(id: String): AtomicBoolean =
        cancelFlags.getOrPut(id) { AtomicBoolean(false) }

    private var lastFlush = 0L

    private suspend fun flush(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlush < 2000L) return
        lastFlush = now
        runCatching { DownloadStore.save(dir, _tasks.value) }
    }

    private suspend fun update(id: String, force: Boolean, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
        flush(force)
    }

    fun enqueue(task: DownloadTask) {
        cancelFlag(task.id).set(false)
        lastFlush = 0L
        scope.launch {
            // Load the persisted queue FIRST — otherwise a download started
            // before the Downloads tab was ever opened would replace the stored
            // task list with just this one task.
            ensureLoaded()
            _tasks.update { list -> list.filterNot { it.id == task.id } + task }
            flush(force = true)
            startPump()
        }
    }

    fun pause(id: String) {
        // Flag immediately so a running engine stops now; the status write follows.
        cancelFlag(id).set(true)
        scope.launch {
            ensureLoaded()
            update(id, force = true) { it.copy(status = DownloadStatus.PAUSED, bytesPerSec = 0L) }
        }
    }

    fun resume(id: String) {
        cancelFlag(id).set(false)
        scope.launch {
            ensureLoaded()
            update(id, force = true) {
                it.copy(
                    status = DownloadStatus.QUEUED,
                    error = null,
                    resumePartial = true,
                    bytesPerSec = 0L,
                )
            }
            startPump()
        }
    }

    /** Removes the task and its on-disk copy. An EXPORTED file already in the
     *  user's Downloads folder is left alone — it belongs to the user now. */
    fun remove(id: String) {
        cancelFlag(id).set(true)
        cancelFlags.remove(id)
        scope.launch {
            ensureLoaded()
            val t = _tasks.value.firstOrNull { it.id == id }
            val path = t?.localPath
            if (path != null) {
                runCatching { File(path).parentFile?.deleteRecursively() }
            }
            runCatching { workDirFor(id).deleteRecursively() }
            _tasks.value = _tasks.value.filterNot { it.id == id }
            flush(force = true)
        }
    }

    /** Drops every finished (or failed) task from the list without touching the
     *  exported files. */
    fun clearFinished() {
        scope.launch {
            ensureLoaded()
            _tasks.value = _tasks.value.filter {
                it.status != DownloadStatus.DONE && it.status != DownloadStatus.FAILED
            }
            flush(force = true)
        }
    }

    /** Atomically takes the next QUEUED task, marking it RUNNING so no other
     *  pump can claim it too — but only while fewer than [maxConcurrent] tasks
     *  are already running. Null when the queue is drained or at capacity. */
    private fun claimNextQueued(): DownloadTask? {
        var claimed: DownloadTask? = null
        _tasks.update { list ->
            val limit = maxConcurrent.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)
            // A task that is converting is still occupying its slot, so it
            // counts against the concurrency limit.
            val running = list.count {
                it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.CONVERTING
            }
            val idx = if (running < limit) list.indexOfFirst { it.status == DownloadStatus.QUEUED } else -1
            if (idx < 0) {
                claimed = null
                list
            } else {
                val taken = list[idx].copy(status = DownloadStatus.RUNNING, error = null)
                claimed = taken
                list.toMutableList().also { it[idx] = taken }
            }
        }
        return claimed
    }

    /** Starts the pump unless one is already running. Safe to call repeatedly
     *  (every enqueue/resume does). */
    fun startPump() {
        synchronized(pumpLock) {
            if (pumpJob?.isActive == true) return
            pumpJob = scope.launch { runCatching { pump() } }
        }
    }

    /** Runs the queue until it is drained, keeping up to [maxConcurrent] tasks
     *  in flight. Re-reads the limit each pass, so changing it in Settings takes
     *  effect on the next queued task. */
    private suspend fun pump() {
        ensureLoaded()
        coroutineScope {
            val jobs = ArrayList<Job>()
            while (true) {
                jobs.removeAll { it.isCompleted }
                val task = claimNextQueued()
                if (task != null) {
                    jobs += launch { runTask(task) }
                    continue
                }
                if (jobs.isEmpty()) break
                // Queue drained or concurrency limit reached — wait for a slot.
                delay(200)
            }
            jobs.forEach { it.join() }
        }
    }

    private suspend fun runTask(task: DownloadTask) {
        flush(force = true)
        val flag = cancelFlag(task.id)
        val workDir = workDirFor(task.id)
        var lastBytes = 0L
        var lastSample = System.currentTimeMillis()
        var speedEma = 0.0
        try {
            val result = DownloadEngine.run(
                task = task,
                workDir = workDir,
                exportDir = exportDir(),
                onProgress = { p ->
                    // Smoothed (EMA) transfer rate from the byte deltas between
                    // progress callbacks, so the row can show a live speed.
                    val now = System.currentTimeMillis()
                    val dt = now - lastSample
                    if (dt >= 200L) {
                        val delta = p.doneBytes - lastBytes
                        if (delta > 0L) {
                            val instant = delta.toDouble() * 1000.0 / dt.toDouble()
                            speedEma = if (speedEma <= 0.0) instant else speedEma * 0.6 + instant * 0.4
                        }
                        lastBytes = p.doneBytes
                        lastSample = now
                    }
                    update(task.id, force = false) {
                        it.copy(
                            bytesDone = p.doneBytes,
                            bytesTotal = if (p.totalBytes > 0) p.totalBytes else it.bytesTotal,
                            durationMs = if (p.durationMs > 0) p.durationMs else it.durationMs,
                            doneDurationMs = if (p.doneDurationMs > 0) p.doneDurationMs else it.doneDurationMs,
                            bytesPerSec = speedEma.toLong(),
                        )
                    }
                },
                onConverting = {
                    update(task.id, force = true) {
                        it.copy(status = DownloadStatus.CONVERTING, bytesPerSec = 0L)
                    }
                },
                isCancelled = { flag.get() },
            )
            update(task.id, force = true) {
                it.copy(
                    status = DownloadStatus.DONE,
                    localPath = result.localPath,
                    savedUri = result.savedUri,
                    error = null,
                    resumePartial = false,
                    bytesPerSec = 0L,
                    bytesDone = if (it.bytesTotal > 0) it.bytesTotal else it.bytesDone,
                    doneDurationMs = if (it.durationMs > 0) it.durationMs else it.doneDurationMs,
                )
            }
            finishNotification(task.id)
        } catch (c: DownloadCancelledException) {
            update(task.id, force = true) {
                it.copy(status = DownloadStatus.PAUSED, error = null, bytesPerSec = 0L)
            }
        } catch (t: Throwable) {
            if (flag.get()) {
                update(task.id, force = true) {
                    it.copy(status = DownloadStatus.PAUSED, error = null, bytesPerSec = 0L)
                }
            } else {
                update(task.id, force = true) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        error = t.message ?: t.javaClass.simpleName,
                        bytesPerSec = 0L,
                    )
                }
                finishNotification(task.id)
            }
        }
    }

    private fun finishNotification(id: String) {
        val cb = onTaskFinished ?: return
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        runCatching { cb(task) }
    }
}
