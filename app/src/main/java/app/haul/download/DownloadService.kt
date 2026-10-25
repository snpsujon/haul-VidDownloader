package app.haul.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.haul.HaulApp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Keeps downloads running while the app is closed. The queue itself lives in [DownloadQueue]. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class DownloadService : LifecycleService() {

    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val graph = HaulApp.graph(this)
        if (!started) {
            started = true
            goForeground(Notifications.service(this, emptyList()))
            lifecycleScope.launch {
                combine(graph.dao.observeQueue(), graph.queue.busy) { q, busy -> q to busy }
                    .debounce(300)
                    .collect { (q, busy) ->
                        val relevant = q.filter { it.status != app.haul.data.Status.FAILED && it.status != app.haul.data.Status.PAUSED }
                        if (!busy && relevant.none { it.isWaiting || it.isActive }) {
                            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else if (Notifications.canPost(this@DownloadService)) {
                            runCatching {
                                NotificationManagerCompat.from(this@DownloadService)
                                    .notify(Notifications.ID_SERVICE, Notifications.service(this@DownloadService, relevant))
                            }
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(n: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, Notifications.ID_SERVICE, n, type)
    }
}

/** Pause and Cancel buttons on the progress notification. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0)
        if (id == 0L) return
        val queue = HaulApp.graph(context).queue
        when (intent.action) {
            ACTION_PAUSE -> queue.pause(id)
            ACTION_CANCEL -> queue.cancel(id)
        }
    }

    companion object {
        const val ACTION_PAUSE = "app.haul.action.PAUSE"
        const val ACTION_CANCEL = "app.haul.action.CANCEL"
        const val EXTRA_ID = "id"
    }
}
