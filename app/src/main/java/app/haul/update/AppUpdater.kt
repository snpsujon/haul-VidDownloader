package app.haul.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.haul.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What version.json on the website says. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val minSupported: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val abi: String,
    val changelog: List<String>,
) {
    val required: Boolean get() = BuildConfig.VERSION_CODE < minSupported
}

/**
 * The app's own update loop: read version.json from the website, download the APK for this
 * phone's CPU type, check its SHA-256, and hand it to Android's installer.
 */
class AppUpdater(private val context: Context) {

    sealed interface Install {
        data object Idle : Install
        data class Downloading(val done: Long, val total: Long) : Install
        data object Verifying : Install
        data object NeedsPermission : Install
        data object ReadyToInstall : Install
        data object Installing : Install
        data class Failed(val message: String) : Install
    }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available

    private val _install = MutableStateFlow<Install>(Install.Idle)
    val install: StateFlow<Install> = _install

    private val apkFile get() = File(context.cacheDir, "update/update.apk")

    val configured: Boolean get() = !BuildConfig.UPDATE_URL.contains("example.com")

    /** Returns the newer version, or null if this is the latest. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val conn = (URL(BuildConfig.UPDATE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Cache-Control", "no-cache")
        }
        val text = try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val info = parse(text)
        _available.value = info?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        _available.value
    }

    fun parse(text: String): UpdateInfo? {
        val o = JSONObject(text)
        val apks = o.optJSONObject("apks") ?: return null
        val abi = Build.SUPPORTED_ABIS.firstOrNull { apks.has(it) } ?: "universal"
        val apk = apks.optJSONObject(abi) ?: apks.optJSONObject("universal") ?: return null
        val changelog = when (val c = o.opt("changelog")) {
            is org.json.JSONArray -> (0 until c.length()).map { c.optString(it) }
            is String -> c.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        return UpdateInfo(
            versionCode = o.optInt("versionCode"),
            versionName = o.optString("versionName"),
            minSupported = o.optInt("minSupported", 0),
            apkUrl = apk.optString("url"),
            sha256 = apk.optString("sha256").lowercase(),
            sizeBytes = apk.optLong("size", 0),
            abi = if (apks.has(Build.SUPPORTED_ABIS.firstOrNull() ?: "")) Build.SUPPORTED_ABIS.first() else "universal",
            changelog = changelog,
        )
    }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Steps 01 and 02: download, then check the SHA-256. */
    suspend fun download(info: UpdateInfo) = withContext(Dispatchers.IO) {
        try {
            apkFile.parentFile?.mkdirs()
            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            _install.value = Install.Downloading(0, total)
            conn.inputStream.use { input ->
                apkFile.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 256_000) {
                            last = done
                            _install.value = Install.Downloading(done, total)
                        }
                    }
                }
            }
            conn.disconnect()
            _install.value = Install.Verifying
            val digest = MessageDigest.getInstance("SHA-256")
            apkFile.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256.isEmpty() || !hash.equals(info.sha256, ignoreCase = true)) {
                apkFile.delete()
                _install.value = Install.Failed(
                    if (info.sha256.isEmpty()) "The update listing has no SHA-256, so the file can't be checked."
                    else "The downloaded file did not match its SHA-256, so it was deleted.",
                )
                return@withContext
            }
            _install.value = if (canInstall()) Install.ReadyToInstall else Install.NeedsPermission
        } catch (t: Throwable) {
            _install.value = Install.Failed(t.message ?: "The update could not be downloaded.")
        }
    }

    fun recheckPermission() {
        if (_install.value == Install.NeedsPermission && canInstall()) _install.value = Install.ReadyToInstall
    }

    /** Step 04: hand the verified APK to Android. */
    suspend fun install() = withContext(Dispatchers.IO) {
        try {
            _install.value = Install.Installing
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("haul.apk", 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pi.intentSender)
            }
        } catch (t: Throwable) {
            _install.value = Install.Failed(t.message ?: "Android could not install the update.")
        }
    }

    internal fun onInstallFailed(message: String) {
        _install.value = Install.Failed(message)
    }

    fun reset() {
        _install.value = Install.Idle
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android could not install the update."
                app.haul.HaulApp.graph(context).updater.onInstallFailed(msg)
            }
        }
    }
}
