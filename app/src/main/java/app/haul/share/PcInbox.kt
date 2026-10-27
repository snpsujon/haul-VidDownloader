package app.haul.share

import android.content.Context
import android.os.Build
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
import app.haul.data.InboxItem
import app.haul.data.Pairing
import app.haul.data.SettingsRepository
import app.haul.download.DownloadQueue
import app.haul.download.Notifications
import app.haul.util.Pretty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * "Send to phone": links sent from a paired browser wait in the website's inbox until this
 * phone collects them. Each one is queued like a share, as video or audio as the browser asked.
 */
class PcInbox(
    private val context: Context,
    private val settings: SettingsRepository,
    private val queue: DownloadQueue,
) {
    class PairException(message: String) : Exception(message)

    private val pollLock = Mutex()

    /** Claims the pairing on the website, then remembers it. */
    suspend fun connect(p: Pairing) {
        val (code, body) = post(p, "/api/pair/claim", JSONObject().put("pair_id", p.id).put("receive_token", p.token).put("device_name", Build.MODEL))
        when {
            code == 404 || code == 410 -> throw PairException("This pairing code has expired. Make a new one in your browser and scan it again.")
            code !in 200..299 -> throw PairException(serverError(body) ?: "${p.host} couldn't connect this phone (error $code).")
            runCatching { JSONObject(body).optBoolean("ok", true) }.getOrDefault(true).not() ->
                throw PairException(serverError(body) ?: "${p.host} couldn't connect this phone.")
        }
        settings.updatePairings { list -> list.filterNot { it.site == p.site && it.id == p.id } + p }
    }

    /** Tells the website to forget this phone (best effort) and forgets the pairing here. */
    suspend fun remove(p: Pairing) {
        runCatching { post(p, "/api/pair/unpair", JSONObject().put("pair_id", p.id).put("receive_token", p.token)) }
            .onFailure { Log.w(TAG, "unpair ${p.host} failed", it) }
        drop(p)
    }

    /** Collects waiting links from every paired browser. Cheap when nothing is paired. */
    suspend fun poll() = pollLock.withLock {
        val pairings = settings.current().pairings
        for (p in pairings) {
            val result = runCatching { get(p) }
            val r = result.getOrNull()
            if (r == null) {
                Log.w(TAG, "inbox ${p.host} failed", result.exceptionOrNull())
                continue
            }
            when (r.first) {
                404 -> drop(p) // removed on the website
                in 200..299 -> runCatching { InboxItem.parse(r.second) }.getOrNull()?.forEach { receive(it) }
                else -> Log.w(TAG, "inbox ${p.host}: HTTP ${r.first}")
            }
        }
    }

    private suspend fun receive(item: InboxItem) {
        val s = settings.current()
        val id = queue.enqueue(null, item.url, presetFor(s, item.audio, 0, null))
        Notifications.fromPc(context, item.title.ifBlank { Pretty.shortUrl(item.url) }, id)
    }

    private suspend fun drop(p: Pairing) = settings.updatePairings { list -> list.filterNot { it.site == p.site && it.id == p.id } }

    private suspend fun get(p: Pairing): Pair<Int, String> = request(
        "${p.site}/api/pair/inbox?id=${enc(p.id)}&token=${enc(p.token)}", null,
    )

    private suspend fun post(p: Pairing, path: String, body: JSONObject): Pair<Int, String> = request(p.site + path, body.toString())

    private suspend fun request(url: String, body: String?): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (!Pairing.siteAllowed(url, BuildConfig.DEBUG)) throw IOException("Only https sites can be paired")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } finally {
            c.disconnect()
        }
    }

    private fun serverError(body: String): String? =
        runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "PcInbox"
    }
}

/** Checks the inbox about every 15 minutes while the app is in the background. */
class PcInboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        HaulApp.graph(applicationContext).pcInbox.poll()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "pc-inbox",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PcInboxWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
