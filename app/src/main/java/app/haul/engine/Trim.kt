package app.haul.engine

import java.util.Locale

/** A part of a video or song to keep, in whole seconds from the start. */
data class TrimRange(val startSec: Int, val endSec: Int) {
    val lengthSec: Int get() = endSec - startSec

    /** "0:30–1:45", for tags and the sheet. */
    val label: String get() = "${Trim.format(startSec)}–${Trim.format(endSec)}"

    /** Stored on the queue row as "30-105". */
    fun encode(): String = "$startSec-$endSec"

    companion object {
        fun decode(s: String?): TrimRange? {
            val parts = s?.split('-')?.takeIf { it.size == 2 } ?: return null
            val start = parts[0].toIntOrNull() ?: return null
            val end = parts[1].toIntOrNull() ?: return null
            return TrimRange(start, end).takeIf { start >= 0 && end > start }
        }
    }
}

object Trim {
    /** "45", "1:05" or "1:02:05" to seconds; null if it isn't a time. */
    fun parse(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 5 }) return null
        val n = parts.map { it.toInt() }
        // Below the leading unit, minutes and seconds stay under 60.
        if (n.drop(1).any { it >= 60 }) return null
        return n.fold(0) { acc, v -> acc * 60 + v }
    }

    /** "0:05", "1:45", "1:02:05". */
    fun format(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** What is wrong with the two fields, or null when they make a range within [durationSec] (0 = unknown). */
    fun error(start: String, end: String, durationSec: Int): String? {
        val s = parse(start) ?: return "Start isn't a time. Use m:ss, such as 1:05."
        val e = parse(end) ?: return "End isn't a time. Use m:ss, such as 2:30."
        return when {
            e <= s -> "The end has to come after the start."
            durationSec > 0 && e > durationSec -> "The end is past the end of the video (${format(durationSec)})."
            else -> null
        }
    }

    fun range(start: String, end: String, durationSec: Int): TrimRange? =
        if (error(start, end, durationSec) != null) null else TrimRange(parse(start)!!, parse(end)!!)

    /** yt-dlp downloads only this part; cutting at the exact times needs a re-encode at the cuts, which is slower. */
    fun ytDlpArgs(r: TrimRange): List<String> =
        listOf("--download-sections", "*${r.startSec}-${r.endSec}", "--force-keyframes-at-cuts")

    /** Bytes for a part of a file of [totalBytes] lasting [durationSec]. */
    fun scaledBytes(totalBytes: Long?, durationSec: Int, r: TrimRange?): Long? {
        if (totalBytes == null || r == null || durationSec <= 0) return totalBytes
        return totalBytes * r.lengthSec.coerceAtMost(durationSec) / durationSec
    }

    /** "Song.mp3" -> "Song (trim).mp3". */
    fun trimmedName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name (trim)" else "${name.substring(0, dot)} (trim)${name.substring(dot)}"
    }

    /**
     * FFmpeg attempts for cutting an existing file, best first: exact cuts with encoders picked for
     * the container, then FFmpeg's default encoders, then a stream copy (cuts at the nearest keyframe).
     */
    fun ffmpegAttempts(input: String, output: String, r: TrimRange, audio: Boolean): List<List<String>> {
        val ext = output.substringAfterLast('.', "").lowercase()
        val head = listOf("-hide_banner", "-y", "-ss", r.startSec.toString(), "-i", input, "-t", r.lengthSec.toString())
        // Audio files keep their cover art (an attached picture stream) as is.
        val maps = if (audio) listOf("-map", "0:a:0", "-map", "0:v?", "-c:v", "copy") else listOf("-map", "0:v:0", "-map", "0:a?")
        val codecs = when {
            audio && ext == "mp3" -> listOf("-c:a", "libmp3lame", "-q:a", "2")
            audio && (ext == "opus" || ext == "ogg") -> listOf("-c:a", "libopus", "-b:a", "160k")
            audio -> listOf("-c:a", "aac", "-b:a", "192k")
            ext == "webm" -> listOf("-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0", "-c:a", "libopus")
            else -> listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac", "-b:a", "192k")
        }
        return listOf(
            head + maps + codecs + output,
            head + maps + output,
            head + maps + listOf("-c", "copy") + output,
        )
    }
}
