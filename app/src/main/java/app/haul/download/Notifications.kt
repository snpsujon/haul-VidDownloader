package app.haul.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.haul.MainActivity
import app.haul.R
import app.haul.data.DownloadEntity
import app.haul.data.Status
import app.haul.util.Pretty

/**
 * Notification content follows the "progress notification" spec card in 00 Components:
 * "Haul · downloading 2 of 3", the title, a progress bar, "62% · 4.2 MB/S", Pause and Cancel.
 */
object Notifications {
    const val CHANNEL_PROGRESS = "downloads"
    const val CHANNEL_FINISHED = "finished"
    const val CHANNEL_UPDATES = "updates"
    const val ID_SERVICE = 1
    const val ID_APP_UPDATE = 2
    private const val SIGNAL = 0xFFFF7A3D.toInt()

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_downloads_desc); setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FINISHED, context.getString(R.string.channel_finished), NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun openApp(context: Context, tab: String = "queue", itemId: Long = 0): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .putExtra(MainActivity.EXTRA_ITEM, itemId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, (tab.hashCode() * 31 + itemId).toInt(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun action(context: Context, action: String, id: Long): PendingIntent {
        val i = Intent(context, DownloadActionReceiver::class.java).setAction(action).putExtra(DownloadActionReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, (action.hashCode() * 31 + id).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun service(context: Context, queue: List<DownloadEntity>): android.app.Notification {
        val active = queue.filter { it.status == Status.DOWNLOADING || it.status == Status.PROCESSING }
        val waitingNet = queue.count { it.status == Status.WAITING_NETWORK }
        val retrying = queue.count { it.status == Status.RETRYING }
        val total = queue.count { it.status != Status.FAILED && it.status != Status.PAUSED }
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(SIGNAL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        val first = active.firstOrNull()
        if (first == null) {
            when {
                waitingNet > 0 -> b.setContentTitle("Waiting for a connection")
                    .setContentText("$waitingNet queued. They start on their own when you're back online.")
                retrying > 0 -> b.setContentTitle("Retrying soon")
                    .setContentText(if (retrying == 1) "A download hit a temporary error and will try again." else "$retrying downloads hit a temporary error and will try again.")
                else -> b.setContentTitle("Getting ready")
            }
            return b.build()
        }
        val index = queue.indexOfFirst { it.id == first.id }.coerceAtLeast(0) + 1
        b.setSubText("downloading $index of ${total.coerceAtLeast(1)}")
        b.setContentTitle(first.title.ifBlank { Pretty.shortUrl(first.url) })
        if (first.status == Status.PROCESSING) {
            b.setContentText(if (first.mediaType == app.haul.data.MediaType.AUDIO) "Converting audio" else "Merging audio + video")
            b.setProgress(0, 0, true)
        } else {
            val pct = (first.progress * 100).toInt()
            b.setContentText(listOf("$pct%", Pretty.speed(first.speedBps)).filter { it.isNotEmpty() }.joinToString(" · "))
            b.setProgress(100, pct, first.progress <= 0f)
            b.addAction(0, "Pause", action(context, DownloadActionReceiver.ACTION_PAUSE, first.id))
        }
        b.addAction(0, "Cancel", action(context, DownloadActionReceiver.ACTION_CANCEL, first.id))
        return b.build()
    }

    fun finished(context: Context, item: DownloadEntity) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(SIGNAL)
            .setContentTitle(item.title)
            .setContentText("Saved · ${item.formatLabel} · ${Pretty.bytes(item.fileSize)}")
            .setContentIntent(openApp(context, "library", item.id))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(itemTag(item.id), 0, n) }
    }

    fun failed(context: Context, item: DownloadEntity) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(SIGNAL)
            .setContentTitle("Download failed")
            .setContentText(item.title.ifBlank { Pretty.shortUrl(item.url) })
            .setContentIntent(openApp(context, "queue", item.id))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(itemTag(item.id), 0, n) }
    }

    fun cancelItem(context: Context, id: Long) {
        NotificationManagerCompat.from(context).cancel(itemTag(id), 0)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun itemTag(id: Long) = "item-$id"
}
