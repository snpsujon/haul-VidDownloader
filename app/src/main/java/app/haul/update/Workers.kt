package app.haul.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.haul.HaulApp
import app.haul.MainActivity
import app.haul.R
import app.haul.download.Notifications
import app.haul.share.PcInboxWorker
import java.util.concurrent.TimeUnit

/** Keeps yt-dlp current: at most once a day, on Wi-Fi, when "Update automatically" is on. */
class EngineUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = HaulApp.graph(applicationContext)
        val s = g.settings.current()
        if (!s.ytdlpAutoUpdate) return Result.success()
        return runCatching {
            g.engine.update(s.channel)
            g.settings.setYtdlpLastCheck(System.currentTimeMillis())
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

/** Checks the website's version.json once a day and posts a notification when there is a new version. */
class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = HaulApp.graph(applicationContext)
        val s = g.settings.current()
        if (!s.appUpdateCheck || !g.updater.configured) return Result.success()
        val info = runCatching { g.updater.check() }.getOrElse { return Result.retry() }
        g.settings.setAppLastCheck(System.currentTimeMillis())
        if (info != null && (info.required || info.versionCode != s.skippedVersionCode) && Notifications.canPost(applicationContext)) {
            val open = PendingIntent.getActivity(
                applicationContext, 7,
                Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(0xFFFF7A3D.toInt())
                .setContentTitle("Version ${info.versionName} is ready")
                .setContentText(info.changelog.firstOrNull() ?: "Tap to update")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(applicationContext).notify(Notifications.ID_APP_UPDATE, n) }
        }
        return Result.success()
    }
}

object Schedules {
    fun schedule(context: Context) {
        PcInboxWorker.schedule(context)
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "engine-update",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EngineUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            "app-update",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AppUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}
