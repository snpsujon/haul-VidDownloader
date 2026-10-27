package app.haul

import android.app.Application
import android.content.Context
import app.haul.data.Accounts
import app.haul.data.HaulDatabase
import app.haul.data.SettingsRepository
import app.haul.data.Storage
import app.haul.download.DownloadQueue
import app.haul.download.NetworkMonitor
import app.haul.download.Notifications
import app.haul.engine.MediaInfo
import app.haul.engine.YtDlp
import app.haul.share.PcInbox
import app.haul.update.AppUpdater
import app.haul.update.Schedules
import app.haul.util.CrashReports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Everything the screens, the service and the workers share. */
class AppGraph(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = HaulDatabase.create(app)
    val dao = db.downloads()
    val settings = SettingsRepository(app)
    val accounts = Accounts(app, settings)
    val engine = YtDlp(app, accounts)
    val storage = Storage(app)
    val network = NetworkMonitor(app)
    val queue = DownloadQueue(app, dao, settings, engine, storage, network, scope)
    val updater = AppUpdater(app)
    val pcInbox = PcInbox(app, settings, queue)

    /** Link info read recently, so the share sheet and the app don't read the same link twice. */
    val infoCache = ConcurrentHashMap<String, MediaInfo>()
}

class HaulApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReports.install(this)
        graph = AppGraph(this)
        Notifications.createChannels(this)
        graph.scope.launch {
            if (graph.engine.ensureReady()) {
                CrashReports.engineVersion = graph.engine.version()
                // Remember which yt-dlp shipped in this APK, for "Restore bundled version".
                val s = graph.settings.current()
                if (s.ytdlpBundledVersion == null && graph.engine.updatedVersion() == null) {
                    graph.engine.runVersion()?.let { graph.settings.setYtdlpBundledVersion(it) }
                }
            }
            graph.queue.start()
        }
        Schedules.schedule(this)
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as HaulApp).graph
    }
}
