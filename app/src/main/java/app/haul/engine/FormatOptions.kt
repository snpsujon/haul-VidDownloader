package app.haul.engine

import app.haul.data.AudioFormat
import app.haul.data.MediaType
import java.util.Locale

/** A quality row in the Video tab of the format picker. */
data class VideoChoice(
    val height: Int,
    val label: String,        // "2160p · 4K"
    val spec: String,         // "VP9 · 30FPS"
    val sizeBytes: Long?,
    val selector: String,     // yt-dlp -f value
    val container: String,    // merge output format
    val codecs: String,       // "AVC + AAC"
)

data class AudioChoice(
    val format: AudioFormat,
    val spec: String,
    val sizeBytes: Long?,
    val selector: String,
    val badge: String? = null,
)

/** One spoken language among a video's audio streams. */
data class AudioTrack(
    val language: String,     // yt-dlp language code, e.g. "en-US"
    val label: String,        // "English (United States)"
    val original: Boolean,
)

/** What the engine needs to run one download. Stored on the queue row. */
data class DownloadRequest(
    val mediaType: MediaType,
    val formatLabel: String,
    val selector: String,
    val container: String = "",
    val audioFormat: String = "",
    val audioBitrate: Int = 0,
    val embedCover: Boolean = false,
    val metaTitle: String? = null,
    val metaArtist: String? = null,
    val estimatedBytes: Long = 0,
    val codecs: String? = null,
)

object FormatOptions {

    private val qualityNames = mapOf(4320 to "8K", 2160 to "4K")

    /** The audio languages on offer, original first. Empty unless there are at least two. */
    fun audioTracks(info: MediaInfo): List<AudioTrack> {
        val byLanguage = info.formats.filter { it.hasAudio && it.language.isNotEmpty() }.groupBy { it.language }
        if (byLanguage.size < 2) return emptyList()
        return byLanguage.map { (code, fs) ->
            AudioTrack(code, languageName(code), fs.any { it.languagePreference >= 10 || it.note.contains("original", ignoreCase = true) })
        }.sortedWith(compareByDescending<AudioTrack> { it.original }.thenBy { it.label })
    }

    fun languageName(code: String): String {
        val name = Locale.forLanguageTag(code).displayName
        return if (name.isBlank()) code else name.replaceFirstChar { it.titlecase() }
    }

    /** [language] limits the audio to that track; null takes any. */
    fun videoChoices(info: MediaInfo, container: String, language: String? = null): List<VideoChoice> {
        val d = info.durationSec
        val videoOnly = info.formats.filter { it.isVideoOnly && it.height > 0 }
        val combined = info.formats.filter { it.isCombined && it.height > 0 }
        val audio = bestAudioFor(info, container, language)
        val lf = languageFilter(language)
        val heights = (videoOnly + combined).map { it.height }.distinct().sortedDescending()
        return heights.mapNotNull { h ->
            val pick = pickVideo(videoOnly.filter { it.height == h }, container)
            if (pick != null) {
                val aSize = audio?.size(d) ?: 0L
                val vSize = pick.size(d)
                val selector = buildString {
                    append(pick.id)
                    if (audio != null) append("+").append(audio.id)
                    append("/").append(pick.id).append("+bestaudio").append(lf)
                    append("/best[height<=").append(h).append("]/best")
                }
                VideoChoice(
                    height = h,
                    label = heightLabel(h),
                    spec = spec(pick),
                    sizeBytes = vSize?.plus(aSize),
                    selector = selector,
                    container = container,
                    codecs = listOf(pick.videoCodecName, audio?.audioCodecName ?: "").filter { it.isNotEmpty() }.joinToString(" + "),
                )
            } else {
                val c = pickVideo(combined.filter { it.height == h }, container) ?: return@mapNotNull null
                VideoChoice(
                    height = h,
                    label = heightLabel(h),
                    spec = spec(c),
                    sizeBytes = c.size(d),
                    selector = "${c.id}/best[height<=$h]/best",
                    container = container,
                    codecs = listOf(c.videoCodecName, c.audioCodecName).filter { it.isNotEmpty() }.joinToString(" + "),
                )
            }
        }
    }

    /** "BEST FOR PHONES": 1080p when there is one, otherwise the highest at or below 1080p. */
    fun recommendedHeight(choices: List<VideoChoice>): Int? =
        choices.firstOrNull { it.height == 1080 }?.height
            ?: choices.filter { it.height <= 1080 }.maxOfOrNull { it.height }
            ?: choices.maxOfOrNull { it.height }

    fun defaultHeight(choices: List<VideoChoice>, preferred: Int): Int? =
        choices.firstOrNull { it.height == preferred }?.height
            ?: choices.filter { it.height <= preferred }.maxOfOrNull { it.height }
            ?: choices.minOfOrNull { it.height }

    fun audioChoices(info: MediaInfo, mp3Bitrate: Int, language: String? = null): List<AudioChoice> {
        val d = info.durationSec
        val list = ArrayList<AudioChoice>()
        val lf = languageFilter(language)
        val mp3Size = if (d > 0) d.toLong() * mp3Bitrate * 1000 / 8 else null
        val mp3Selector = if (lf.isEmpty()) "bestaudio/best" else "bestaudio$lf/bestaudio/best"
        list += AudioChoice(AudioFormat.MP3, "PLAYS EVERYWHERE · RE-ENCODED", mp3Size, mp3Selector)
        val audio = audioIn(info, language)
        val m4a = audio.filter { (it.ext == "m4a" || it.acodec.startsWith("mp4a")) }
            .maxByOrNull { it.abr.takeIf { a -> a > 0 } ?: it.tbr }
        if (m4a != null) {
            val k = kbps(m4a)
            list += AudioChoice(
                AudioFormat.M4A,
                "AAC ${if (k > 0) "${k}K · " else ""}ORIGINAL, NO RE-ENCODE",
                m4a.size(d),
                "${m4a.id}/bestaudio[ext=m4a]$lf/bestaudio",
                badge = "FASTEST",
            )
        }
        val opus = audio.filter { it.acodec.startsWith("opus") }
            .maxByOrNull { it.abr.takeIf { a -> a > 0 } ?: it.tbr }
        if (opus != null) {
            val k = kbps(opus)
            list += AudioChoice(
                AudioFormat.OPUS,
                "${if (k > 0) "${k}K · " else ""}ORIGINAL, SMALLEST",
                opus.size(d),
                "${opus.id}/bestaudio[acodec=opus]$lf/bestaudio",
            )
        }
        return list
    }

    fun mp3Size(info: MediaInfo, bitrate: Int): Long? =
        if (info.durationSec > 0) info.durationSec.toLong() * bitrate * 1000 / 8 else null

    // Requests --------------------------------------------------------------------------------

    fun videoRequest(c: VideoChoice): DownloadRequest = DownloadRequest(
        mediaType = MediaType.VIDEO,
        formatLabel = "${c.height}P ${c.container.uppercase()}",
        selector = c.selector,
        container = c.container,
        estimatedBytes = c.sizeBytes ?: 0,
        codecs = c.codecs,
    )

    fun audioRequest(
        c: AudioChoice,
        bitrate: Int,
        embedCover: Boolean,
        title: String?,
        artist: String?,
        sizeBytes: Long?,
    ): DownloadRequest = DownloadRequest(
        mediaType = MediaType.AUDIO,
        formatLabel = when (c.format) {
            AudioFormat.MP3 -> "MP3 $bitrate"
            AudioFormat.M4A -> "M4A"
            AudioFormat.OPUS -> "OPUS"
        },
        selector = c.selector,
        audioFormat = c.format.ext,
        audioBitrate = if (c.format == AudioFormat.MP3) bitrate else 0,
        embedCover = embedCover && c.format != AudioFormat.OPUS,
        metaTitle = title,
        metaArtist = artist,
        estimatedBytes = sizeBytes ?: 0,
        codecs = when (c.format) {
            AudioFormat.MP3 -> "MP3"
            AudioFormat.M4A -> "AAC"
            AudioFormat.OPUS -> "OPUS"
        },
    )

    /** Default video preset, used for shares, offline queueing and playlists. */
    fun presetVideoRequest(height: Int, container: String, durationSec: Int = 0): DownloadRequest {
        val vc = if (container == "webm") "[ext=webm]" else "[vcodec^=avc1]"
        val ac = if (container == "webm") "[acodec=opus]" else "[ext=m4a]"
        val selector = "bv*[height<=$height]$vc+ba$ac/bv*[height<=$height]+ba/b[height<=$height]/b"
        return DownloadRequest(
            mediaType = MediaType.VIDEO,
            formatLabel = "${height}P ${container.uppercase()}",
            selector = selector,
            container = container,
            estimatedBytes = estimateVideoBytes(height, durationSec),
        )
    }

    fun presetAudioRequest(format: AudioFormat, bitrate: Int, embedCover: Boolean, durationSec: Int = 0): DownloadRequest {
        val selector = when (format) {
            AudioFormat.MP3 -> "bestaudio/best"
            AudioFormat.M4A -> "bestaudio[ext=m4a]/bestaudio"
            AudioFormat.OPUS -> "bestaudio[acodec=opus]/bestaudio"
        }
        val kbpsGuess = when (format) { AudioFormat.MP3 -> bitrate; AudioFormat.M4A -> 129; AudioFormat.OPUS -> 128 }
        return DownloadRequest(
            mediaType = MediaType.AUDIO,
            formatLabel = when (format) { AudioFormat.MP3 -> "MP3 $bitrate"; AudioFormat.M4A -> "M4A"; AudioFormat.OPUS -> "OPUS" },
            selector = selector,
            audioFormat = format.ext,
            audioBitrate = if (format == AudioFormat.MP3) bitrate else 0,
            embedCover = embedCover && format != AudioFormat.OPUS,
            estimatedBytes = if (durationSec > 0) durationSec.toLong() * kbpsGuess * 1000 / 8 else 0,
            codecs = when (format) { AudioFormat.MP3 -> "MP3"; AudioFormat.M4A -> "AAC"; AudioFormat.OPUS -> "OPUS" },
        )
    }

    /** Rough total bitrate per height (video + audio), for estimates where the site gives no sizes. */
    fun estimateVideoBytes(height: Int, durationSec: Int): Long {
        if (durationSec <= 0) return 0
        val kbps = when {
            height >= 2160 -> 14000
            height >= 1440 -> 7000
            height >= 1080 -> 2800
            height >= 720 -> 1500
            height >= 480 -> 800
            else -> 500
        }
        return durationSec.toLong() * kbps * 1000 / 8
    }

    /** Request for a hand-picked pair (or single) from the All formats list. */
    fun customRequest(info: MediaInfo, video: Fmt?, audio: Fmt?): DownloadRequest? {
        val d = info.durationSec
        if (video == null && audio == null) return null
        if (video == null) {
            // Audio only: keep it as is.
            return DownloadRequest(
                mediaType = MediaType.AUDIO,
                formatLabel = "${audio!!.ext.uppercase()} ${kbps(audio).takeIf { it > 0 }?.let { "${it}K" } ?: ""}".trim(),
                selector = audio.id,
                estimatedBytes = audio.size(d) ?: 0,
                codecs = audio.audioCodecName,
            )
        }
        val container = mergeContainer(video, audio)
        val sel = if (audio != null && !video.isCombined) "${video.id}+${audio.id}" else video.id
        return DownloadRequest(
            mediaType = MediaType.VIDEO,
            formatLabel = "${video.height}P ${container.uppercase()}",
            selector = sel,
            container = container,
            estimatedBytes = (video.size(d) ?: 0) + (if (video.isCombined) 0 else audio?.size(d) ?: 0),
            codecs = listOf(video.videoCodecName, (if (video.isCombined) video.audioCodecName else audio?.audioCodecName) ?: "")
                .filter { it.isNotEmpty() }.joinToString(" + "),
        )
    }

    fun mergeContainer(video: Fmt, audio: Fmt?): String {
        if (video.isCombined || audio == null) return video.ext.ifEmpty { "mp4" }
        val v = video.ext
        val a = audio.ext
        return when {
            v == "mp4" && (a == "m4a" || a == "mp4") -> "mp4"
            v == "webm" && a == "webm" -> "webm"
            v == "mp4" && video.videoCodecName in setOf("AVC", "HEVC", "AV1", "VP9") && audio.audioCodecName in setOf("AAC", "OPUS", "MP3") -> "mp4"
            else -> "mkv"
        }
    }

    // Helpers ---------------------------------------------------------------------------------

    /** Audio-only formats, limited to [language] when it is set. */
    fun audioIn(info: MediaInfo, language: String?): List<Fmt> =
        info.formats.filter { it.isAudioOnly && (language == null || it.language == language) }

    private fun languageFilter(language: String?): String = language?.let { "[language=$it]" } ?: ""

    private fun bestAudioFor(info: MediaInfo, container: String, language: String?): Fmt? {
        val audio = audioIn(info, language)
        val score: (Fmt) -> Double = { it.abr.takeIf { a -> a > 0 } ?: it.tbr }
        val preferred = if (container == "webm") audio.filter { it.acodec.startsWith("opus") || it.ext == "webm" }
        else audio.filter { it.ext == "m4a" || it.acodec.startsWith("mp4a") }
        return preferred.maxByOrNull(score) ?: audio.maxByOrNull(score)
    }

    private fun pickVideo(candidates: List<Fmt>, container: String): Fmt? {
        if (candidates.isEmpty()) return null
        val order: (Fmt) -> Int = if (container == "webm") {
            { f -> when (f.videoCodecName) { "VP9" -> 0; "AV1" -> 1; "VP8" -> 2; else -> if (f.ext == "webm") 3 else 4 } }
        } else {
            { f -> when (f.videoCodecName) { "AVC" -> 0; "HEVC" -> 1; "VP9" -> 2; "AV1" -> 3; else -> if (f.ext == "mp4") 1 else 4 } }
        }
        val bestRank = candidates.minOf(order)
        return candidates.filter { order(it) == bestRank }
            .sortedWith(compareByDescending<Fmt> { it.fps }.thenByDescending { it.tbr })
            .first()
    }

    fun heightLabel(h: Int): String = qualityNames[h]?.let { "${h}p · $it" } ?: "${h}p"

    private fun spec(f: Fmt): String {
        val parts = ArrayList<String>()
        f.videoCodecName.takeIf { it.isNotEmpty() && it != "UNKNOWN" }?.let { parts += it }
        if (f.fps > 0) parts += "${f.fps}FPS"
        if (f.dynamicRange.isNotEmpty() && f.dynamicRange != "SDR") parts += f.dynamicRange.uppercase()
        if (parts.isEmpty()) parts += f.ext.uppercase()
        return parts.joinToString(" · ")
    }

    fun kbps(f: Fmt): Int = Math.round(f.abr.takeIf { it > 0 } ?: f.tbr).toInt()
}
