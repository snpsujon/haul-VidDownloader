package app.haul.util

import android.content.Context
import android.os.Build
import app.haul.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a report when the app crashes, so it can be copied or shared on the next launch.
 * Reports stay in app storage only; nothing is sent anywhere.
 */
object CrashReports {
    private const val DIR = "crashes"
    private const val UNSEEN = "unseen"
    private const val KEEP = 10

    /** The yt-dlp version in use, kept current by the app so a report can name it. */
    @Volatile var engineVersion: String? = null

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val now = System.currentTimeMillis()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("Haul crash report")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(now))}")
            appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Engine: yt-dlp ${engineVersion ?: "unknown"}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
        }
        val d = dir(context).apply { mkdirs() }
        val file = File(d, "crash-$now.txt")
        file.writeText(text)
        File(d, UNSEEN).writeText(file.name)
        reports(context).drop(KEEP).forEach { it.delete() }
    }

    /** Newest first. */
    fun reports(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** The report from a crash the user hasn't been told about yet. */
    fun unseen(context: Context): File? {
        val name = runCatching { File(dir(context), UNSEEN).readText().trim() }.getOrNull() ?: return null
        return File(dir(context), name).takeIf { it.exists() }
    }

    fun markSeen(context: Context) {
        File(dir(context), UNSEEN).delete()
    }

    fun delete(context: Context, file: File) {
        file.delete()
        if (unseen(context) == null) markSeen(context)
    }

    fun deleteAll(context: Context) {
        dir(context).deleteRecursively()
    }

    /** "CRASH · 27 OCT 10:41" for list rows. */
    fun label(file: File): String =
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(file.lastModified())).uppercase()

    /** The first line of the stack trace, e.g. "IllegalStateException: …". */
    fun summary(text: String): String {
        val line = text.lineSequence().dropWhile { it.isNotBlank() }.firstOrNull { it.isNotBlank() }?.trim() ?: return ""
        val type = line.substringBefore(':').substringAfterLast('.')
        return (type + line.substringAfter(':', "").let { if (it.isBlank()) "" else ":$it" }).take(160)
    }
}
