package app.haul.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

object Pretty {
    /** "412 MB", "1.9 GB" (gbDecimals = 1) or "1.86 GB" (gbDecimals = 2). */
    fun bytes(b: Long?, gbDecimals: Int = 1): String {
        if (b == null || b <= 0) return "—"
        val mb = b / 1_000_000.0
        return when {
            mb >= 1000 -> String.format(Locale.US, "%.${gbDecimals}f GB", mb / 1000)
            mb >= 1 -> "${mb.roundToLong()} MB"
            else -> "${(b / 1000.0).roundToLong().coerceAtLeast(1)} KB"
        }
    }

    /** "255/412 MB" */
    fun bytesPair(done: Long, total: Long): String {
        if (total <= 0) return bytes(done)
        val unitGb = total >= 1_000_000_000
        return if (unitGb) {
            String.format(Locale.US, "%.2f/%.2f GB", done / 1e9, total / 1e9)
        } else {
            "${(done / 1e6).roundToLong()}/${(total / 1e6).roundToLong()} MB"
        }
    }

    /** "4.2 MB/S" */
    fun speed(bps: Long): String = when {
        bps <= 0 -> ""
        bps >= 1_000_000 -> String.format(Locale.US, "%.1f MB/S", bps / 1e6)
        else -> "${(bps / 1000.0).roundToLong()} KB/S"
    }

    /** "41:27", "1:12:05" */
    fun duration(sec: Int): String {
        if (sec <= 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "6 H 12 MIN" */
    fun longDuration(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return when {
            h > 0 -> "$h H $m MIN"
            m > 0 -> "$m MIN"
            else -> "${sec} S"
        }
    }

    fun eta(sec: Long): String = if (sec < 0) "" else duration(sec.toInt()).ifEmpty { "0:00" }

    /** "12 min ago", "2:41 PM", "Yesterday", "Tue", "3 Oct" */
    fun relative(time: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - time
        if (diff < 60_000) return "Just now"
        if (diff < 3_600_000) return "${diff / 60_000} min ago"
        val cal = Calendar.getInstance().apply { timeInMillis = time }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val days = daysBetween(cal, today)
        return when {
            days == 0 -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(time))
            days == 1 -> "Yesterday"
            days < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(time))
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(time))
        }
    }

    /** Library rows: "2:41 PM" today, then the same as [relative]. */
    fun libraryTime(time: Long, now: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = time }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        return if (daysBetween(cal, today) == 0) SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(time))
        else relative(time, now)
    }

    /** "24 OCT 2026 · 14:14" */
    fun savedAt(time: Long): String =
        SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(time)).uppercase()

    fun daysBetween(a: Calendar, b: Calendar): Int {
        val a0 = a.clone() as Calendar
        val b0 = b.clone() as Calendar
        listOf(a0, b0).forEach {
            it.set(Calendar.HOUR_OF_DAY, 0); it.set(Calendar.MINUTE, 0); it.set(Calendar.SECOND, 0); it.set(Calendar.MILLISECOND, 0)
        }
        return ((b0.timeInMillis - a0.timeInMillis) / 86_400_000L).toInt()
    }

    /** "vimeo.com/827140226" from a full link. */
    fun shortUrl(url: String): String =
        url.substringAfter("://").removePrefix("www.").removePrefix("m.").trimEnd('/')

    fun relativeCheck(time: Long, now: Long = System.currentTimeMillis()): String {
        if (time <= 0) return "not checked yet"
        val diff = now - time
        return when {
            diff < 60_000 -> "checked just now"
            diff < 3_600_000 -> "checked ${diff / 60_000} min ago"
            diff < 2 * 3_600_000 -> "checked 1 hour ago"
            diff < 86_400_000 -> "checked ${diff / 3_600_000} hours ago"
            diff < 2 * 86_400_000 -> "checked yesterday"
            else -> "checked ${diff / 86_400_000} days ago"
        }
    }
}

/** Finds the first web link in shared or pasted text. */
object Links {
    private val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val bareRegex = Regex("""^(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/[^\s]*)?$""", RegexOption.IGNORE_CASE)

    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        urlRegex.find(text)?.let { return it.value.trimEnd('.', ',', ')', ']') }
        val t = text.trim()
        return if (bareRegex.matches(t)) "https://$t" else null
    }

    fun isLink(text: String): Boolean = extract(text) != null
}
