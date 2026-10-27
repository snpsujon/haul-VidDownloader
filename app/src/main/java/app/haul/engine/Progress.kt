package app.haul.engine

import app.haul.data.ErrorKind

/** Snapshot of a running download, as the queue shows it. */
data class ProgressSnapshot(
    val progress: Float,
    val downloaded: Long,
    val total: Long,
    val speedBps: Long,
    val etaSec: Long,
    val processing: Boolean,
)

/**
 * Turns yt-dlp's output lines into overall progress across every stream it downloads
 * (a video download is usually a video stream and then an audio stream).
 *
 * Lines come from our own --progress-template ("[haul]downloaded|total|estimate|speed|eta"),
 * from aria2c (percent only), and from post-processors (merging, converting).
 */
class ProgressParser(private val estimatedTotal: Long) {
    private var completedBytes = 0L
    private var currentDownloaded = 0L
    private var currentTotal = 0L
    private var streams = 0
    private var speed = 0L
    private var eta = -1L
    private var processing = false
    private var lastPercent = 0f

    fun onLine(line: String, libraryPercent: Float): ProgressSnapshot? {
        val l = line.trim()
        when {
            l.startsWith(TEMPLATE_PREFIX) -> {
                val parts = l.removePrefix(TEMPLATE_PREFIX).split('|')
                if (parts.size < 5) return null
                val downloaded = parts[0].toLongOrNullLoose() ?: return null
                val total = parts[1].toLongOrNullLoose() ?: parts[2].toLongOrNullLoose() ?: 0L
                if (streams == 0) streams = 1
                // A new stream has started when the counter goes back down.
                if (downloaded < currentDownloaded && currentTotal > 0 && currentDownloaded >= currentTotal * 0.9) {
                    completedBytes += currentTotal
                    streams++
                }
                currentDownloaded = downloaded
                currentTotal = total
                speed = parts[3].toDoubleOrNull()?.toLong() ?: 0L
                eta = parts[4].toDoubleOrNull()?.toLong() ?: -1L
                processing = false
            }
            l.startsWith("[download] Destination:") -> {
                if (streams > 0 && currentTotal > 0) {
                    completedBytes += currentTotal
                    currentDownloaded = 0
                    currentTotal = 0
                }
                streams++
                processing = false
            }
            POST_PROCESSORS.any { l.startsWith(it) } -> {
                processing = true
                speed = 0
                eta = -1
            }
            libraryPercent > 0f && libraryPercent > lastPercent && !l.startsWith("[download]") -> {
                // aria2c reports only a percentage of the current stream.
                lastPercent = libraryPercent
                val base = if (estimatedTotal > 0) estimatedTotal else 0L
                return ProgressSnapshot(
                    progress = (libraryPercent / 100f).coerceIn(0f, 1f),
                    downloaded = (base * libraryPercent / 100f).toLong(),
                    total = base,
                    speedBps = 0,
                    etaSec = -1,
                    processing = false,
                )
            }
            else -> return null
        }
        return snapshot()
    }

    fun snapshot(): ProgressSnapshot {
        val done = completedBytes + currentDownloaded
        val total = maxOf(estimatedTotal, completedBytes + currentTotal, done)
        val p = if (processing) 1f else if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        return ProgressSnapshot(p, done, total, speed, eta, processing)
    }

    companion object {
        const val TEMPLATE_PREFIX = "[haul]"
        const val TEMPLATE =
            "download:[haul]%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s"
        private val POST_PROCESSORS = listOf(
            "[Merger]", "[ExtractAudio]", "[EmbedThumbnail]", "[Metadata]", "[FixupM3u8]", "[FixupM4a]",
            "[FixupStretched]", "[FixupDuplicateMoov]", "[VideoConvertor]", "[VideoRemuxer]", "[ffmpeg]",
            "[ThumbnailsConvertor]", "[MoveFiles]",
        )

        private fun String.toLongOrNullLoose(): Long? = toLongOrNull() ?: toDoubleOrNull()?.toLong()
    }
}

/** Plain-language error cards from yt-dlp's stderr. */
object Errors {
    data class Card(val title: String, val fix: String, val suggestUpdate: Boolean)

    fun classify(log: String): ErrorKind {
        val l = log.lowercase()
        return when {
            "no space left" in l -> ErrorKind.STORAGE
            "unsupported url" in l -> ErrorKind.UNSUPPORTED
            "private video" in l || "members-only" in l || "members only" in l || "join this channel" in l ||
                "this video is private" in l || "login required" in l || "account" in l && "sign in" in l -> ErrorKind.PRIVATE
            "timed out" in l || "connection reset" in l || "network is unreachable" in l ||
                "temporary failure in name resolution" in l || "failed to resolve" in l ||
                "connection refused" in l || "remote end closed" in l || "connection aborted" in l ||
                "no route to host" in l || "<urlopen error" in l -> ErrorKind.NETWORK
            "unable to extract" in l || "sign in to confirm" in l || "http error 403" in l ||
                "nsig" in l || "requested format is not available" in l || "precondition" in l ||
                "unable to download" in l || "player response" in l || "extractor" in l ||
                Regex("""error: \[[a-z0-9_:]+]""").containsMatchIn(l) -> ErrorKind.EXTRACTOR
            else -> ErrorKind.UNKNOWN
        }
    }

    const val MAX_AUTO_RETRIES = 3
    private val RETRY_DELAYS_MS = longArrayOf(30_000, 2 * 60_000, 10 * 60_000)
    private val PERMANENT = listOf(
        "video unavailable", "has been removed", "no longer available", "available in your country",
        "geo restrict", "geo-restrict", "copyright",
    )
    private val RETRYABLE_HTTP = Regex("http error (429|5\\d\\d)")

    /** Errors worth retrying on their own: dropped connections, timeouts, rate limits and server errors. */
    fun isTransient(log: String): Boolean {
        val l = log.lowercase()
        if (PERMANENT.any { it in l }) return false
        return when (classify(log)) {
            ErrorKind.NETWORK -> true
            ErrorKind.PRIVATE, ErrorKind.UNSUPPORTED, ErrorKind.STORAGE -> false
            else -> RETRYABLE_HTTP.containsMatchIn(l) || "too many requests" in l ||
                "incompleteread" in l || "incomplete read" in l
        }
    }

    /** Wait before automatic retry number [attempt] (0-based): 30 s, 2 min, 10 min. */
    fun retryDelayMs(attempt: Int): Long = RETRY_DELAYS_MS[attempt.coerceIn(0, RETRY_DELAYS_MS.lastIndex)]

    fun card(kind: ErrorKind, failedAt: Int): Card {
        val resume = if (failedAt > 0) " It resumes from $failedAt%." else ""
        return when (kind) {
            ErrorKind.EXTRACTOR -> Card(
                "This site changed how it serves video.",
                "Updating the download engine usually fixes it. It takes about 10 seconds" +
                    if (failedAt > 0) " and resumes from $failedAt%." else ".",
                suggestUpdate = true,
            )
            ErrorKind.NETWORK -> Card("The connection dropped.", "Check your connection, then retry.$resume", false)
            ErrorKind.STORAGE -> Card("Your phone is out of space.", "Free up some space, then retry.$resume", false)
            ErrorKind.UNSUPPORTED -> Card(
                "This link isn't supported.",
                "Check that it opens a video or audio page. An engine update sometimes adds new sites.",
                suggestUpdate = true,
            )
            ErrorKind.PRIVATE -> Card(
                "This video isn't public.",
                "If your account can open it, sign in under Settings → Accounts, then retry.",
                false,
            )
            ErrorKind.UNKNOWN -> Card(
                "The download stopped with an error.",
                "Retrying usually works. If it keeps failing, copy the log and send it to us.",
                suggestUpdate = true,
            )
        }
    }

    /** The last ERROR lines, for "Technical details". */
    fun technical(log: String, maxLines: Int = 3): String {
        val lines = log.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val errors = lines.filter { it.startsWith("ERROR") }
        val picked = (errors.ifEmpty { lines }).takeLast(maxLines)
        return picked.joinToString("\n")
    }
}
