package app.haul.update

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.haul.BuildConfig
import app.haul.HaulApp
import app.haul.data.SettingsRepository
import app.haul.download.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** An announcement posted on the website for app users. [createdAt] is in epoch seconds. */
data class Notice(
    val id: Long,
    val title: String,
    val body: String,
    val url: String?,
    val minVersion: Int,
    val maxVersion: Int,
    val createdAt: Long,
) {
    /** 0 means no limit on that side. */
    fun appliesTo(versionCode: Int): Boolean =
        (minVersion == 0 || versionCode >= minVersion) && (maxVersion == 0 || versionCode <= maxVersion)

    companion object {
        private const val WEEK_SEC = 7L * 24 * 60 * 60

        /** {ok, items:[{id, title, body, url|null, min_version, max_version, created_at}]}. */
        fun parse(json: String): List<Notice> {
            val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                val o = items.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                val title = if (o.isNull("title")) "" else o.optString("title").trim()
                if (title.isEmpty()) return@mapNotNull null
                Notice(
                    id = id,
                    title = title,
                    body = if (o.isNull("body")) "" else o.optString("body").trim(),
                    url = if (o.isNull("url")) null else o.optString("url").trim().takeIf { it.startsWith("https://") || it.startsWith("http://") },
                    minVersion = o.optInt("min_version", 0),
                    maxVersion = o.optInt("max_version", 0),
                    createdAt = epochSeconds(o.opt("created_at")),
                )
            }
        }

        /** Numbers in seconds or milliseconds, or an ISO-8601 time; 0 if unreadable. */
        private fun epochSeconds(v: Any?): Long = when (v) {
            is Number -> v.toLong().let { if (it > 100_000_000_000L) it / 1000 else it }
            is String -> v.toDoubleOrNull()?.let { epochSeconds(it) } ?: isoSeconds(v)
            else -> 0
        }

        private fun isoSeconds(s: String): Long {
            for (p in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss")) {
                val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                runCatching { return f.parse(s)!!.time / 1000 }
            }
            return 0
        }

        /** The stored id only counts for the site it came from; ids from another site (a test server) mean nothing here. */
        fun lastIdFor(site: String, storedSite: String?, storedId: Long?): Long? = storedId?.takeIf { storedSite == site }

        /**
         * Which notices to show and the highest id to remember. On the first run ([lastId] null) only
         * the newest notice from the last 7 days is shown, so a new install isn't flooded.
         */
        fun select(items: List<Notice>, lastId: Long?, versionCode: Int, nowSec: Long): Pair<List<Notice>, Long> {
            val maxId = maxOf(lastId ?: 0, items.maxOfOrNull { it.id } ?: 0)
            val fitting = items.filter { it.appliesTo(versionCode) }
            val show = if (lastId == null) {
                listOfNotNull(fitting.filter { it.createdAt >= nowSec - WEEK_SEC }.maxByOrNull { it.id })
            } else {
                fitting.filter { it.id > lastId }.sortedBy { it.id }
            }
            return show to maxId
        }
    }
}

/** Fetches news from the website on open and every 15 minutes, and shows new items as notifications. */
class NoticePoller(private val context: Context, private val settings: SettingsRepository) {
    private val lock = Mutex()

    private val configured: Boolean get() = !BuildConfig.WEBSITE_URL.contains("example.com")

    suspend fun poll() = lock.withLock {
        val s = settings.current()
        if (!s.newsEnabled || !configured) return@withLock
        val site = BuildConfig.WEBSITE_URL
        val lastId = Notice.lastIdFor(site, s.lastNoticeSite, s.lastNoticeId)
        val url = "$site/api/notices?since=${lastId ?: 0}&v=${BuildConfig.VERSION_CODE}"
        // A missing endpoint or no connection is not worth bothering anyone about.
        val body = runCatching { fetch(url) }.onFailure { Log.d(TAG, "notices: ${it.message}") }.getOrNull() ?: return@withLock
        val items = runCatching { Notice.parse(body) }.getOrNull() ?: return@withLock
        val (show, maxId) = Notice.select(items, lastId, BuildConfig.VERSION_CODE, System.currentTimeMillis() / 1000)
        show.forEach { Notifications.news(context, it) }
        settings.setLastNotice(site, maxId)
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val TAG = "Notices"
    }
}

class NoticeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        HaulApp.graph(applicationContext).notices.poll()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "notices",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NoticeWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
