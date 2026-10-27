package app.haul.download

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import app.haul.data.DownloadDao
import app.haul.data.DownloadEntity
import app.haul.data.ErrorKind
import app.haul.data.MediaType
import app.haul.data.SettingsRepository
import app.haul.data.Status
import app.haul.data.Storage
import app.haul.engine.DownloadRequest
import app.haul.engine.EngineException
import app.haul.engine.Errors
import app.haul.engine.MediaInfo
import app.haul.engine.YtDlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the download queue. Rows live in Room; this class starts as many as the
 * "Downloads at the same time" setting allows, and keeps the foreground service alive while
 * anything is running or waiting.
 */
class DownloadQueue(
    private val context: Context,
    private val dao: DownloadDao,
    private val settings: SettingsRepository,
    private val engine: YtDlp,
    private val storage: Storage,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    private val running = ConcurrentHashMap<Long, Job>()
    /** Why a running job is being stopped, so the failure path knows it was on purpose. */
    private val stopping = ConcurrentHashMap<Long, Status?>()
    private val lock = Mutex()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun start() {
        scope.launch {
            dao.requeueInterrupted()
            // Waiting retries survive a restart: re-arm them, due ones at once.
            val now = System.currentTimeMillis()
            dao.retrying().forEach { RetryWorker.schedule(context, it.id, (it.retryAt - now).coerceAtLeast(0)) }
            pump()
        }
        scope.launch {
            network.state.map { it }.distinctUntilChanged().collect { pump() }
        }
        scope.launch {
            settings.settings.map { it.concurrent to it.mobileData }.distinctUntilChanged().collect { pump() }
        }
    }

    private fun workDir(id: Long) = File(context.cacheDir, "downloads/$id")

    suspend fun enqueue(info: MediaInfo?, url: String, req: DownloadRequest): Long {
        val pos = dao.maxPosition() + 1
        val id = dao.insert(
            DownloadEntity(
                url = info?.url ?: url,
                title = info?.title ?: "",
                uploader = info?.uploader ?: "",
                site = info?.host ?: MediaInfo.hostOf(url),
                thumbnailUrl = info?.thumbnail,
                durationSec = info?.durationSec ?: 0,
                mediaType = req.mediaType,
                formatLabel = req.formatLabel,
                formatSelector = req.selector,
                container = req.container,
                audioFormat = req.audioFormat,
                audioBitrate = req.audioBitrate,
                embedCover = req.embedCover,
                metaTitle = req.metaTitle,
                metaArtist = req.metaArtist,
                estimatedBytes = req.estimatedBytes,
                codecs = req.codecs,
                subtitleLang = req.subtitleLang,
                subtitleMode = if (req.subtitleLang.isEmpty()) "" else req.subtitleMode.name,
                status = Status.QUEUED,
                position = pos,
            )
        )
        pump()
        return id
    }

    fun pause(id: Long) = scope.launch { stop(id, Status.PAUSED) }

    fun resume(id: Long) = scope.launch {
        dao.setStatus(id, Status.QUEUED)
        pump()
    }

    fun cancel(id: Long) = scope.launch {
        RetryWorker.cancel(context, id)
        stop(id, null)
        dao.delete(id)
        workDir(id).deleteRecursively()
        Notifications.cancelItem(context, id)
    }

    fun retry(id: Long) = scope.launch {
        val e = dao.get(id) ?: return@launch
        RetryWorker.cancel(context, id)
        dao.update(e.copy(status = Status.QUEUED, errorKind = null, errorLog = null, retryCount = 0, retryAt = 0))
        pump()
    }

    /** Called by [RetryWorker] when a row's wait is over and there is a connection. */
    suspend fun retryDue(id: Long) {
        dao.releaseRetry(id)
        pump()
    }

    fun pauseAll() = scope.launch {
        dao.pending().forEach { if (it.status != Status.PROCESSING) stop(it.id, Status.PAUSED) }
        dao.retrying().forEach {
            RetryWorker.cancel(context, it.id)
            dao.setStatus(it.id, Status.PAUSED)
        }
    }

    fun resumeAll() = scope.launch {
        dao.pausedIds().forEach { dao.setStatus(it, Status.QUEUED) }
        pump()
    }

    /** "Start next": moves a waiting row to the front of the waiting list. */
    fun moveToFront(id: Long) = scope.launch {
        dao.setPosition(id, dao.minWaitingPosition() - 1)
        pump()
    }

    private suspend fun stop(id: Long, newStatus: Status?) {
        val job = running[id]
        if (job != null) {
            stopping[id] = newStatus
            engine.cancel(processId(id))
            job.join()
        }
        if (newStatus != null) dao.setStatus(id, newStatus)
        pump()
    }

    /** Starts what can start, and holds the rest until the network allows. */
    suspend fun pump() = lock.withLock {
        val s = settings.current()
        val net = network.read()
        val allowed = net.online && (s.mobileData || net.unmetered)
        if (!allowed) {
            dao.holdQueued()
        } else {
            dao.releaseWaiting()
            val slots = s.concurrent - running.size
            if (slots > 0) {
                dao.queued().filter { !running.containsKey(it.id) }.take(slots).forEach { launchJob(it) }
            }
        }
        val pending = dao.pending()
        val active = running.isNotEmpty() || pending.isNotEmpty()
        _busy.value = active
        if (active) startService()
    }

    private fun launchJob(item: DownloadEntity) {
        val job = scope.launch { runJob(item) }
        running[item.id] = job
        job.invokeOnCompletion {
            running.remove(item.id)
            stopping.remove(item.id)
            scope.launch { pump() }
        }
    }

    private suspend fun runJob(start: DownloadEntity) {
        var item = start.copy(status = Status.DOWNLOADING, errorKind = null, errorLog = null)
        dao.update(item)
        val s = settings.current()
        try {
            // Rows queued offline or from a share have no metadata yet.
            if (item.title.isBlank()) {
                runCatching { engine.fetch(item.url, processId(item.id)) }.getOrNull()?.let { info ->
                    item = item.copy(
                        url = info.url, title = info.title, uploader = info.uploader, site = info.host,
                        thumbnailUrl = info.thumbnail, durationSec = info.durationSec,
                        estimatedBytes = if (item.estimatedBytes > 0) item.estimatedBytes else estimate(item, info),
                    )
                    dao.update(item)
                }
                if (stopping.containsKey(item.id)) return
            }
            val result = engine.download(item, workDir(item.id), s.aria2c, processId(item.id)) { p ->
                scope.launch {
                    val st = if (p.processing) Status.PROCESSING else Status.DOWNLOADING
                    dao.setProgress(item.id, p.progress, p.downloaded, p.total, p.speedBps, p.etaSec, st)
                }
            }
            dao.setStatus(item.id, Status.PROCESSING)
            val saved = storage.save(result.file, s)
            // Subtitle files go next to the video, with the same name, so players pick them up.
            result.extras.forEach { f -> runCatching { storage.save(f, s) }.onFailure { Log.w(TAG, "could not save ${f.name}", it) } }
            val done = (dao.get(item.id) ?: item).copy(
                status = Status.DONE,
                progress = 1f,
                fileUri = saved.uri,
                fileName = saved.name,
                fileSize = saved.size,
                mimeType = saved.mime,
                folderLabel = saved.folderLabel,
                title = item.title.ifBlank { saved.name.substringBeforeLast('.') },
                finishedAt = System.currentTimeMillis(),
                speedBps = 0,
                etaSec = -1,
                retryCount = 0,
                retryAt = 0,
            )
            dao.update(done)
            workDir(item.id).deleteRecursively()
            Notifications.finished(context, done)
        } catch (e: EngineException) {
            if (stopping.containsKey(item.id) || e.canceled) return
            fail(item, e.log.ifBlank { e.message ?: "" })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "download ${item.id} failed", t)
            if (stopping.containsKey(item.id)) return
            fail(item, t.message ?: t.toString())
        }
    }

    private suspend fun fail(item: DownloadEntity, log: String) {
        val current = dao.get(item.id) ?: return
        val kind = Errors.classify(log)
        val failed = current.copy(
            status = Status.FAILED,
            errorKind = kind,
            errorLog = log.takeLast(16_000),
            failedAtProgress = current.progress,
            speedBps = 0,
            etaSec = -1,
        )
        when {
            // A network drop goes back to waiting instead of failing.
            kind == ErrorKind.NETWORK && !network.read().online -> dao.update(failed.copy(status = Status.WAITING_NETWORK))
            // Temporary errors try again on their own, waiting longer each time. Partial files are kept.
            Errors.isTransient(log) && current.retryCount < Errors.MAX_AUTO_RETRIES -> {
                val delay = Errors.retryDelayMs(current.retryCount)
                dao.update(
                    failed.copy(status = Status.RETRYING, retryCount = current.retryCount + 1, retryAt = System.currentTimeMillis() + delay),
                )
                RetryWorker.schedule(context, item.id, delay)
            }
            else -> {
                dao.update(failed)
                Notifications.failed(context, failed)
            }
        }
    }

    private fun estimate(item: DownloadEntity, info: MediaInfo): Long = when (item.mediaType) {
        MediaType.AUDIO -> {
            val k = if (item.audioBitrate > 0) item.audioBitrate else 130
            info.durationSec.toLong() * k * 1000 / 8
        }
        MediaType.VIDEO -> {
            val h = Regex("""(\d+)P""").find(item.formatLabel)?.groupValues?.get(1)?.toIntOrNull() ?: 1080
            app.haul.engine.FormatOptions.estimateVideoBytes(h, info.durationSec)
        }
    }

    private fun startService() {
        val intent = Intent(context, DownloadService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        }.onFailure {
            // Starting from the background is not always allowed. The queue keeps its state and
            // continues the next time the app opens.
            Log.w(TAG, "could not start the download service", it)
        }
    }

    companion object {
        private const val TAG = "DownloadQueue"
        fun processId(id: Long) = "dl-$id"
    }
}
