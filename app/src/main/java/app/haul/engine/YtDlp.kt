package app.haul.engine

import android.content.Context
import android.util.Log
import app.haul.data.Channel
import app.haul.data.DownloadEntity
import app.haul.data.MediaType
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class EngineException(message: String, val log: String, val canceled: Boolean = false) : Exception(message)

data class DownloadResult(val file: File, val log: String)

/**
 * The on-device download engine: yt-dlp running on the bundled Python, with FFmpeg and aria2c.
 * Wraps youtubedl-android so the rest of the app never touches it directly.
 */
class YtDlp(private val context: Context) {

    sealed interface State {
        data object Starting : State
        data object Ready : State
        data class Broken(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state

    private val initLock = Mutex()
    private val updateLock = Mutex()

    suspend fun ensureReady(): Boolean = initLock.withLock {
        if (_state.value == State.Ready) return@withLock true
        withContext(Dispatchers.IO) {
            try {
                YoutubeDL.init(context)
                FFmpeg.init(context)
                runCatching { Aria2c.init(context) }
                _state.value = State.Ready
                true
            } catch (t: Throwable) {
                Log.e(TAG, "engine init failed", t)
                _state.value = State.Broken(t.message ?: "The download engine could not start.")
                false
            }
        }
    }

    /** The yt-dlp version in use: the updated one if any, else what is bundled in the APK. */
    suspend fun version(): String? = withContext(Dispatchers.IO) {
        YoutubeDL.versionName(context)?.let { normaliseVersion(it) } ?: runVersion()
    }

    /** Set only after an in-app update; null while the bundled yt-dlp is in use. */
    fun updatedVersion(): String? = YoutubeDL.versionName(context)

    /** Runs `yt-dlp --version` against the current binary. */
    suspend fun runVersion(): String? = withContext(Dispatchers.IO) {
        if (!ensureReady()) return@withContext null
        runCatching {
            YoutubeDL.execute(YoutubeDLRequest(emptyList<String>()).addOption("--version"), null, false, null).out.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Reads formats and metadata for a link. Playlists come back flat (entries only). */
    suspend fun fetch(url: String, processId: String): MediaInfo = withContext(Dispatchers.IO) {
        if (!ensureReady()) throw EngineException("The download engine could not start.", "")
        val req = YoutubeDLRequest(url)
            .addOption("--dump-single-json")
            .addOption("--flat-playlist")
            .addOption("--no-warnings")
            .addOption("--socket-timeout", 20)
        try {
            val res = YoutubeDL.execute(req, processId, false, null)
            MediaInfo.parse(res.out, url)
        } catch (e: YoutubeDL.CanceledException) {
            throw EngineException("Canceled", "", canceled = true)
        } catch (e: org.json.JSONException) {
            throw EngineException("Could not read what the site sent back.", e.message ?: "")
        } catch (e: Exception) {
            throw EngineException(e.message ?: "Failed", e.message ?: "")
        }
    }

    fun cancel(processId: String): Boolean = runCatching { YoutubeDL.destroyProcessById(processId) }.getOrDefault(false)

    /**
     * Downloads one queue row into [workDir]. Partial files stay there between attempts,
     * so pausing or retrying picks up where it stopped.
     */
    suspend fun download(
        item: DownloadEntity,
        workDir: File,
        useAria2c: Boolean,
        processId: String,
        onProgress: (ProgressSnapshot) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (!ensureReady()) throw EngineException("The download engine could not start.", "")
        workDir.mkdirs()
        val req = YoutubeDLRequest(item.url)
        req.addOption("--no-playlist")
        req.addOption("--newline")
        req.addOption("--no-mtime")
        req.addOption("--continue")
        req.addOption("--progress-template", ProgressParser.TEMPLATE)
        req.addOption("--socket-timeout", 30)
        req.addOption("--retries", 5)
        req.addOption("--fragment-retries", 10)
        req.addCommands(listOf("--retry-sleep", "exp=1:20", "--retry-sleep", "fragment:exp=1:20"))
        req.addOption("-P", workDir.absolutePath)
        req.addOption("-o", "%(title).150B [%(id)s].%(ext)s")
        req.addOption("-f", item.formatSelector)
        req.addOption("--embed-metadata")

        when (item.mediaType) {
            MediaType.VIDEO -> {
                if (item.container.isNotEmpty()) req.addOption("--merge-output-format", item.container)
            }
            MediaType.AUDIO -> {
                if (item.audioFormat.isNotEmpty()) {
                    req.addOption("-x")
                    req.addOption("--audio-format", item.audioFormat)
                    if (item.audioBitrate > 0) req.addOption("--audio-quality", "${item.audioBitrate}K")
                }
                if (item.embedCover) {
                    req.addOption("--embed-thumbnail")
                    req.addOption("--convert-thumbnails", "jpg")
                }
                item.metaTitle?.takeIf { it.isNotBlank() }?.let {
                    req.addCommands(listOf("--replace-in-metadata", "title", "^.*$", escapeReplacement(it)))
                }
                item.metaArtist?.takeIf { it.isNotBlank() }?.let {
                    req.addOption("--parse-metadata", "%(uploader|)s:%(artist)s")
                    req.addCommands(listOf("--replace-in-metadata", "artist", "^.*$", escapeReplacement(it)))
                }
            }
        }

        if (useAria2c) {
            req.addOption("--downloader", "libaria2c.so")
            req.addOption("--external-downloader-args", "aria2c:-x 8 -s 8 -k 1M")
        } else {
            req.addOption("-N", 4)
        }

        val parser = ProgressParser(item.estimatedBytes)
        val log = StringBuilder()
        var lastEmit = 0L
        try {
            val res = YoutubeDL.execute(req, processId, true) { pct, _, line ->
                if (log.length < 64_000) log.append(line).append('\n')
                val snap = parser.onLine(line, pct) ?: return@execute
                val now = System.nanoTime()
                if (snap.processing || now - lastEmit > TimeUnit.MILLISECONDS.toNanos(400)) {
                    lastEmit = now
                    onProgress(snap)
                }
            }
            val all = log.toString() + res.out
            val file = findOutput(workDir) ?: throw EngineException("The download finished but no file was written.", all)
            DownloadResult(file, all)
        } catch (e: YoutubeDL.CanceledException) {
            throw EngineException("Canceled", log.toString(), canceled = true)
        } catch (e: EngineException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "Failed"
            throw EngineException(msg, (log.toString() + "\n" + msg).trim())
        }
    }

    /** Updates yt-dlp from its own GitHub releases. Returns the new version or null if already current. */
    suspend fun update(channel: Channel): String? = updateLock.withLock {
        withContext(Dispatchers.IO) {
            if (!ensureReady()) throw EngineException("The download engine could not start.", "")
            val ch = if (channel == Channel.NIGHTLY) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
            val status = YoutubeDL.updateYoutubeDL(context, ch)
            if (status == YoutubeDL.UpdateStatus.DONE) version() else null
        }
    }

    /** Puts back the yt-dlp that shipped inside the APK. */
    suspend fun restoreBundled(): String? = updateLock.withLock {
        withContext(Dispatchers.IO) {
            val dir = File(File(context.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)
            dir.deleteRecursively()
            context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE).edit()
                .remove("dlpVersion").remove("dlpVersionName").apply()
            YoutubeDL.init_ytdlp(context, dir)
            runVersion()
        }
    }

    /** Version strings of the other bundled parts, for the engine screen. */
    suspend fun partVersions(): Map<String, String> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, String>()
        val bin = File(context.applicationInfo.nativeLibraryDir)
        val packages = File(File(context.noBackupFilesDir, YoutubeDL.baseName), "packages")
        val env = mapOf(
            "LD_LIBRARY_PATH" to listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").absolutePath },
            "PYTHONHOME" to File(packages, "python/usr").absolutePath,
            "HOME" to File(packages, "python/usr").absolutePath,
        )
        out["FFmpeg"] = runTool(File(bin, "libffmpeg.so"), listOf("-version"), env)
            ?.lineSequence()?.firstOrNull()?.let { Regex("""ffmpeg version (\S+)""").find(it)?.groupValues?.get(1) }
            ?: "Bundled"
        out["Python runtime"] = runTool(File(bin, "libpython.so"), listOf("--version"), env)
            ?.trim()?.removePrefix("Python ")?.takeIf { it.isNotEmpty() } ?: "Bundled"
        out["JavaScript runtime"] = if (File(bin, "libqjs.so").exists()) "QuickJS (bundled)" else "Not bundled"
        out
    }

    private fun runTool(exe: File, args: List<String>, env: Map<String, String>): String? = runCatching {
        if (!exe.exists()) return null
        val pb = ProcessBuilder(listOf(exe.absolutePath) + args).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        text
    }.getOrNull()

    private fun findOutput(dir: File): File? {
        val skip = setOf("part", "ytdl", "jpg", "jpeg", "png", "webp", "json", "temp", "tmp")
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() !in skip && !it.name.contains(".part") && !it.name.contains(".temp.") }
            ?.maxByOrNull { it.length() }
    }

    private fun escapeReplacement(s: String): String = s.replace("\\", "\\\\")

    companion object {
        private const val TAG = "YtDlp"

        /** yt-dlp release names look like "yt-dlp 2026.10.09"; keep the version only. */
        fun normaliseVersion(v: String): String = v.trim().removePrefix("yt-dlp").trim().ifEmpty { v }
    }
}
