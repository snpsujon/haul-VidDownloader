package app.haul.ui.grab

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.haul.HaulApp
import app.haul.data.AppSettings
import app.haul.data.AudioFormat
import app.haul.data.MediaType
import app.haul.engine.AudioChoice
import app.haul.engine.AudioTrack
import app.haul.engine.EngineException
import app.haul.engine.Errors
import app.haul.engine.Fmt
import app.haul.engine.FormatOptions
import app.haul.engine.MediaInfo
import app.haul.engine.SubtitleTrack
import app.haul.data.SubtitleMode
import app.haul.engine.VideoChoice
import app.haul.util.Links
import app.haul.util.Pretty
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** State for the Grab tab, the format sheet, All formats and the playlist picker. */
class GrabViewModel(app: Application) : AndroidViewModel(app) {
    private val g = HaulApp.graph(app)

    sealed interface Phase {
        data object Idle : Phase
        data class Fetching(val url: String, val lines: List<String>) : Phase
        data class Offline(val url: String) : Phase
        data class Failed(val url: String, val title: String, val fix: String, val suggestUpdate: Boolean, val log: String) : Phase
        data class Ready(val info: MediaInfo) : Phase
    }

    var link by mutableStateOf("")
        private set
    var linkError by mutableStateOf<String?>(null)
        private set
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set

    // Format sheet
    var sheetOpen by mutableStateOf(false)
    var tab by mutableStateOf(MediaType.VIDEO)
    var container by mutableStateOf("mp4")
        private set
    var videoChoices by mutableStateOf<List<VideoChoice>>(emptyList())
        private set
    var selectedHeight by mutableStateOf<Int?>(null)
    var recommendedHeight by mutableStateOf<Int?>(null)
        private set
    var audioChoices by mutableStateOf<List<AudioChoice>>(emptyList())
        private set
    var audioFormat by mutableStateOf(AudioFormat.MP3)
    var mp3Bitrate by mutableStateOf(320)
    var metaTitle by mutableStateOf("")
    var metaArtist by mutableStateOf("")
    var embedCover by mutableStateOf(true)
    var audioTracks by mutableStateOf<List<AudioTrack>>(emptyList())
        private set
    var audioLanguage by mutableStateOf<String?>(null)
        private set
    var subtitleTracks by mutableStateOf<List<SubtitleTrack>>(emptyList())
        private set
    /** Picked subtitle track code, null for off. */
    var subtitleCode by mutableStateOf<String?>(null)
    var subtitleMode by mutableStateOf(SubtitleMode.EMBED)

    // All formats
    var allFilter by mutableStateOf(0)
    var pickedVideo by mutableStateOf<String?>(null)
    var pickedAudio by mutableStateOf<String?>(null)

    // Playlist
    val playlistSelected = mutableStateListOf<Int>()
    var playlistAudio by mutableStateOf(false)

    private var fetchJob: Job? = null
    private var fetchCounter = 0

    val info: MediaInfo? get() = (phase as? Phase.Ready)?.info

    fun onLinkChange(v: String) {
        link = v
        linkError = null
        if (phase !is Phase.Fetching) phase = Phase.Idle
    }

    fun clear() {
        cancelFetch()
        link = ""
        linkError = null
        phase = Phase.Idle
        sheetOpen = false
    }

    /** Paste, "Use it", a share, or Go on the keyboard. */
    fun submit(text: String = link, onPlaylist: () -> Unit = {}) {
        val url = Links.extract(text)
        if (url == null) {
            link = text
            linkError = "That doesn't look like a link. Paste a full web address."
            return
        }
        link = Pretty.shortUrl(url)
        linkError = null
        if (!g.network.read().online) {
            phase = Phase.Offline(url)
            return
        }
        g.infoCache[url]?.let {
            viewModelScope.launch { onInfo(it, onPlaylist) }
            return
        }
        cancelFetch()
        val pid = "fetch-${++fetchCounter}"
        val short = Pretty.shortUrl(url)
        phase = Phase.Fetching(url, listOf("› resolving $short"))
        fetchJob = viewModelScope.launch {
            val lines = launch {
                delay(700)
                (phase as? Phase.Fetching)?.let { phase = it.copy(lines = it.lines + "› reading formats and metadata…") }
            }
            try {
                val info = g.engine.fetch(url, pid)
                lines.cancel()
                g.infoCache[url] = info
                onInfo(info, onPlaylist)
            } catch (e: EngineException) {
                lines.cancel()
                if (e.canceled) return@launch
                if (!g.network.read().online) { phase = Phase.Offline(url); return@launch }
                val kind = Errors.classify(e.log.ifBlank { e.message ?: "" })
                val card = Errors.card(kind, 0)
                phase = Phase.Failed(url, card.title, card.fix, card.suggestUpdate, Errors.technical(e.log.ifBlank { e.message ?: "" }))
            }
        }
        engineFetchId = pid
    }

    private var engineFetchId: String? = null

    fun cancelFetch() {
        engineFetchId?.let { g.engine.cancel(it) }
        fetchJob?.cancel()
        fetchJob = null
        if (phase is Phase.Fetching) phase = Phase.Idle
    }

    private suspend fun onInfo(info: MediaInfo, onPlaylist: () -> Unit) {
        phase = Phase.Ready(info)
        val s = g.settings.current()
        if (info.isPlaylist) {
            playlistSelected.clear()
            playlistSelected.addAll(info.entries.indices)
            playlistAudio = false
            onPlaylist()
            return
        }
        prepareSheet(info, s)
        sheetOpen = true
    }

    private fun prepareSheet(info: MediaInfo, s: AppSettings) {
        container = s.videoContainer
        audioTracks = FormatOptions.audioTracks(info)
        audioLanguage = audioTracks.firstOrNull()?.language
        videoChoices = FormatOptions.videoChoices(info, container, audioLanguage)
        recommendedHeight = FormatOptions.recommendedHeight(videoChoices)
        selectedHeight = FormatOptions.defaultHeight(videoChoices, s.videoHeight)
        mp3Bitrate = s.mp3Bitrate
        audioChoices = FormatOptions.audioChoices(info, mp3Bitrate, audioLanguage)
        audioFormat = audioChoices.firstOrNull { it.format == s.audioFormat }?.format ?: AudioFormat.MP3
        embedCover = s.embedCover
        subtitleTracks = info.subtitles
        subtitleCode = FormatOptions.defaultSubtitle(info.subtitles, s.subtitleLang)?.code
        subtitleMode = s.subtitleMode
        metaTitle = info.title
        metaArtist = info.uploader
        tab = if (info.hasVideo && videoChoices.isNotEmpty()) MediaType.VIDEO else MediaType.AUDIO
        pickedVideo = null
        pickedAudio = null
    }

    fun setContainerTo(c: String) {
        val i = info ?: return
        container = c
        val keep = selectedHeight
        videoChoices = FormatOptions.videoChoices(i, c, audioLanguage)
        recommendedHeight = FormatOptions.recommendedHeight(videoChoices)
        selectedHeight = videoChoices.firstOrNull { it.height == keep }?.height ?: recommendedHeight
    }

    fun setBitrate(b: Int) {
        mp3Bitrate = b
        info?.let { audioChoices = FormatOptions.audioChoices(it, b, audioLanguage) }
    }

    fun setLanguage(language: String) {
        val i = info ?: return
        audioLanguage = language
        setContainerTo(container)
        audioChoices = FormatOptions.audioChoices(i, mp3Bitrate, language)
        if (audioChoices.none { it.format == audioFormat }) audioFormat = AudioFormat.MP3
    }

    val selectedVideo: VideoChoice? get() = videoChoices.firstOrNull { it.height == selectedHeight }
    val selectedAudio: AudioChoice? get() = audioChoices.firstOrNull { it.format == audioFormat }

    fun audioSize(c: AudioChoice): Long? =
        if (c.format == AudioFormat.MP3) info?.let { FormatOptions.mp3Size(it, mp3Bitrate) } else c.sizeBytes

    /** The Download button in the format sheet. */
    fun download(onQueued: () -> Unit) {
        val i = info ?: return
        val req = when (tab) {
            MediaType.VIDEO -> selectedVideo?.let { v ->
                FormatOptions.videoRequest(v).let { r -> subtitleCode?.let { r.copy(subtitleLang = it, subtitleMode = subtitleMode) } ?: r }
            }
            MediaType.AUDIO -> selectedAudio?.let {
                FormatOptions.audioRequest(
                    it, mp3Bitrate, embedCover,
                    metaTitle.takeIf { t -> t != i.title },
                    metaArtist.takeIf { a -> a != i.uploader },
                    audioSize(it),
                )
            }
        } ?: return
        viewModelScope.launch {
            g.queue.enqueue(i, i.url, req)
            sheetOpen = false
            link = ""
            phase = Phase.Idle
            onQueued()
        }
    }

    // All formats -----------------------------------------------------------------------------

    fun pick(f: Fmt) {
        when {
            f.isCombined -> { pickedVideo = f.id; pickedAudio = null }
            f.isVideoOnly -> {
                pickedVideo = if (pickedVideo == f.id) null else f.id
                if (pickedAudio == null) pickedAudio = bestAudioId()
            }
            f.isAudioOnly -> {
                pickedAudio = if (pickedAudio == f.id) null else f.id
                if (pickedVideo != null && info?.formats?.firstOrNull { it.id == pickedVideo }?.isCombined == true) pickedVideo = null
            }
        }
    }

    fun initAllFormats() {
        if (pickedVideo != null || pickedAudio != null) return
        val i = info ?: return
        val v = selectedVideo
        // Pre-select the pair the quality list would have used.
        val ids = v?.selector?.substringBefore('/')?.split('+').orEmpty()
        pickedVideo = ids.getOrNull(0)?.takeIf { id -> i.formats.any { it.id == id } }
        pickedAudio = ids.getOrNull(1)?.takeIf { id -> i.formats.any { it.id == id } }
    }

    private fun bestAudioId(): String? =
        info?.let { FormatOptions.audioIn(it, audioLanguage) }?.maxByOrNull { it.abr.takeIf { a -> a > 0 } ?: it.tbr }?.id

    fun customRequestPreview() = info?.let { i ->
        FormatOptions.customRequest(
            i,
            i.formats.firstOrNull { it.id == pickedVideo },
            i.formats.firstOrNull { it.id == pickedAudio },
        )
    }

    fun downloadCustom(onQueued: () -> Unit) {
        val i = info ?: return
        val req = customRequestPreview() ?: return
        viewModelScope.launch {
            g.queue.enqueue(i, i.url, req)
            sheetOpen = false
            link = ""
            phase = Phase.Idle
            pickedVideo = null; pickedAudio = null
            onQueued()
        }
    }

    // Offline ---------------------------------------------------------------------------------

    fun queueOffline(onQueued: () -> Unit) {
        val url = (phase as? Phase.Offline)?.url ?: return
        viewModelScope.launch {
            val s = g.settings.current()
            g.queue.enqueue(null, url, FormatOptions.presetVideoRequest(s.videoHeight, s.videoContainer))
            link = ""
            phase = Phase.Idle
            onQueued()
        }
    }

    fun retry(onPlaylist: () -> Unit = {}) {
        val url = when (val p = phase) {
            is Phase.Offline -> p.url
            is Phase.Failed -> p.url
            else -> return
        }
        submit(url, onPlaylist)
    }

    fun updateEngineAndRetry(onPlaylist: () -> Unit, onResult: (String) -> Unit) {
        val p = phase as? Phase.Failed ?: return
        viewModelScope.launch {
            phase = Phase.Fetching(p.url, listOf("› updating the download engine…"))
            val v = runCatching { g.engine.update(g.settings.current().channel) }.getOrNull()
            g.settings.setYtdlpLastCheck(System.currentTimeMillis())
            onResult(if (v != null) "yt-dlp updated to $v. Retrying." else "yt-dlp is already up to date. Retrying.")
            phase = Phase.Idle
            submit(p.url, onPlaylist)
        }
    }

    // Playlist --------------------------------------------------------------------------------

    fun togglePlaylistItem(i: Int) {
        if (i in playlistSelected) playlistSelected.remove(i) else playlistSelected.add(i)
    }

    fun togglePlaylistAll() {
        val n = info?.entries?.size ?: 0
        if (playlistSelected.size == n) playlistSelected.clear() else { playlistSelected.clear(); playlistSelected.addAll(0 until n) }
    }

    fun playlistEstimate(s: AppSettings): Long {
        val entries = info?.entries ?: return 0
        return playlistSelected.sumOf { idx ->
            val d = entries.getOrNull(idx)?.durationSec ?: 0
            if (playlistAudio) FormatOptions.presetAudioRequest(s.audioFormat, s.mp3Bitrate, s.embedCover, d).estimatedBytes
            else FormatOptions.estimateVideoBytes(s.videoHeight, d)
        }
    }

    fun downloadPlaylist(onQueued: () -> Unit) {
        val i = info ?: return
        viewModelScope.launch {
            val s = g.settings.current()
            playlistSelected.sorted().forEach { idx ->
                val e = i.entries.getOrNull(idx) ?: return@forEach
                val req = if (playlistAudio) FormatOptions.presetAudioRequest(s.audioFormat, s.mp3Bitrate, s.embedCover, e.durationSec)
                else FormatOptions.presetVideoRequest(s.videoHeight, s.videoContainer, e.durationSec)
                val entryInfo = MediaInfo(
                    url = e.url, title = e.title, uploader = i.uploader, site = i.site, host = MediaInfo.hostOf(e.url),
                    thumbnail = e.thumbnail, durationSec = e.durationSec, formats = emptyList(),
                )
                g.queue.enqueue(entryInfo, e.url, req)
            }
            link = ""
            phase = Phase.Idle
            onQueued()
        }
    }
}
