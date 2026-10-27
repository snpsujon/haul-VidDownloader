package app.haul.engine

import org.json.JSONArray
import org.json.JSONObject

/** One format as yt-dlp lists it. Sizes are bytes; null when the site gives none. */
data class Fmt(
    val id: String,
    val ext: String,
    val vcodec: String,
    val acodec: String,
    val height: Int,
    val width: Int,
    val fps: Int,
    val tbr: Double,
    val abr: Double,
    val filesize: Long?,
    val note: String,
    val dynamicRange: String,
    val language: String = "",          // "en", "hi"; empty when the site gives none
    val languagePreference: Int = -1,   // yt-dlp ranks the original audio highest (10)
) {
    val hasVideo: Boolean get() = vcodec.isNotEmpty() && vcodec != "none"
    val hasAudio: Boolean get() = acodec.isNotEmpty() && acodec != "none"
    val isVideoOnly: Boolean get() = hasVideo && !hasAudio
    val isAudioOnly: Boolean get() = hasAudio && !hasVideo
    val isCombined: Boolean get() = hasVideo && hasAudio

    /** File size, or an estimate from the bitrate when the site gives none. */
    fun size(durationSec: Int): Long? =
        filesize ?: if (tbr > 0 && durationSec > 0) (tbr * 1000 / 8 * durationSec).toLong() else null

    val videoCodecName: String
        get() = codecName(vcodec)
    val audioCodecName: String
        get() = codecName(acodec)

    companion object {
        fun codecName(c: String): String {
            val v = c.lowercase()
            return when {
                v.startsWith("avc") || v.startsWith("h264") -> "AVC"
                v.startsWith("vp09") || v.startsWith("vp9") -> "VP9"
                v.startsWith("vp8") -> "VP8"
                v.startsWith("av01") || v.startsWith("av1") -> "AV1"
                v.startsWith("hev") || v.startsWith("hvc") || v.startsWith("h265") -> "HEVC"
                v.startsWith("mp4a") || v.startsWith("aac") -> "AAC"
                v.startsWith("opus") -> "OPUS"
                v.startsWith("vorbis") -> "VORBIS"
                v.startsWith("mp3") -> "MP3"
                v.startsWith("flac") -> "FLAC"
                v.isEmpty() || v == "none" -> ""
                else -> v.substringBefore('.').uppercase()
            }
        }
    }
}

data class PlaylistEntry(
    val url: String,
    val title: String,
    val durationSec: Int,
    val thumbnail: String?,
)

data class MediaInfo(
    val url: String,
    val title: String,
    val uploader: String,
    val site: String,
    val host: String,
    val thumbnail: String?,
    val durationSec: Int,
    val formats: List<Fmt>,
    val isPlaylist: Boolean = false,
    val entries: List<PlaylistEntry> = emptyList(),
) {
    val hasVideo: Boolean get() = formats.any { it.hasVideo }

    companion object {
        fun parse(json: String, requestedUrl: String): MediaInfo = parse(JSONObject(json), requestedUrl)

        fun parse(o: JSONObject, requestedUrl: String): MediaInfo {
            val url = o.optStringOrNull("webpage_url") ?: o.optStringOrNull("original_url") ?: requestedUrl
            val host = hostOf(url)
            val isPlaylist = o.optString("_type") == "playlist"
            val entries = if (isPlaylist) parseEntries(o.optJSONArray("entries"), o) else emptyList()
            val thumbnail = o.optStringOrNull("thumbnail")
                ?: o.optJSONArray("thumbnails")?.let { arr -> bestThumbnail(arr) }
                ?: entries.firstOrNull()?.thumbnail
            val duration = if (isPlaylist) entries.sumOf { it.durationSec } else o.optDouble("duration", 0.0).toInt()
            return MediaInfo(
                url = url,
                title = o.optStringOrNull("title") ?: o.optStringOrNull("fulltitle") ?: url,
                uploader = o.optStringOrNull("uploader") ?: o.optStringOrNull("channel")
                    ?: o.optStringOrNull("playlist_uploader") ?: "",
                site = o.optStringOrNull("extractor_key") ?: o.optStringOrNull("extractor") ?: host,
                host = host,
                thumbnail = thumbnail,
                durationSec = duration,
                formats = if (isPlaylist) emptyList() else parseFormats(o.optJSONArray("formats"), o),
                isPlaylist = isPlaylist,
                entries = entries,
            )
        }

        private fun parseEntries(arr: JSONArray?, parent: JSONObject): List<PlaylistEntry> {
            if (arr == null) return emptyList()
            val out = ArrayList<PlaylistEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val u = e.optStringOrNull("url") ?: e.optStringOrNull("webpage_url") ?: continue
                val full = if (u.startsWith("http")) u else {
                    // Some extractors give only the id in flat mode.
                    val key = e.optStringOrNull("ie_key") ?: parent.optStringOrNull("extractor_key")
                    if (key == "Youtube") "https://www.youtube.com/watch?v=$u" else u
                }
                out += PlaylistEntry(
                    url = full,
                    title = e.optStringOrNull("title") ?: full,
                    durationSec = e.optDouble("duration", 0.0).toInt(),
                    thumbnail = e.optStringOrNull("thumbnail")
                        ?: e.optJSONArray("thumbnails")?.let { bestThumbnail(it) },
                )
            }
            return out
        }

        private fun bestThumbnail(arr: JSONArray): String? {
            var best: String? = null
            var bestW = -1
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val u = t.optStringOrNull("url") ?: continue
                val w = t.optInt("width", 0)
                if (w >= bestW) { best = u; bestW = w }
            }
            return best
        }

        private fun parseFormats(arr: JSONArray?, o: JSONObject): List<Fmt> {
            val list = ArrayList<Fmt>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    parseFormat(f)?.let { list += it }
                }
            }
            if (list.isEmpty()) parseFormat(o)?.let { list += it }
            return list
        }

        private fun parseFormat(f: JSONObject): Fmt? {
            val id = f.optStringOrNull("format_id") ?: return null
            val protocol = f.optString("protocol")
            // Storyboards and image formats are not media.
            if (f.optString("ext") == "mhtml" || protocol == "mhtml") return null
            var vcodec = f.optStringOrNull("vcodec") ?: ""
            var acodec = f.optStringOrNull("acodec") ?: ""
            val height = f.optInt("height", 0)
            // Some sites leave codecs out. Infer from what is there.
            if (vcodec.isEmpty() && acodec.isEmpty()) {
                if (height > 0) { vcodec = "unknown"; acodec = "unknown" } else if (f.optString("audio_ext", "none") != "none") {
                    vcodec = "none"; acodec = "unknown"
                } else { vcodec = "unknown"; acodec = "unknown" }
            }
            val size = f.optLong("filesize", 0L).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0L).takeIf { it > 0 }
            return Fmt(
                id = id,
                ext = f.optString("ext"),
                vcodec = vcodec,
                acodec = acodec,
                height = height,
                width = f.optInt("width", 0),
                fps = f.optDouble("fps", 0.0).let { if (it.isNaN()) 0 else Math.round(it).toInt() },
                tbr = f.optDouble("tbr", 0.0).let { if (it.isNaN()) 0.0 else it },
                abr = f.optDouble("abr", 0.0).let { if (it.isNaN()) 0.0 else it },
                filesize = size,
                note = f.optString("format_note"),
                dynamicRange = f.optString("dynamic_range", "SDR"),
                language = f.optStringOrNull("language") ?: "",
                languagePreference = f.optInt("language_preference", -1),
            )
        }

        fun hostOf(url: String): String =
            url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.").removePrefix("m.")
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() && it != "null" } else null
