package app.haul.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.haul.HaulApp
import app.haul.data.AppSettings
import app.haul.data.AudioFormat
import app.haul.data.Channel
import app.haul.data.AppSettings.Companion.DEFAULT_FOLDER_LABEL
import app.haul.data.ShareAction
import app.haul.data.Storage
import app.haul.data.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.MessageDigest

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val g = HaulApp.graph(app)
    private val repo = g.settings

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    enum class Picker { Video, Audio, Share, Theme }
    var picker by mutableStateOf<Picker?>(null)

    // Engine -------------------------------------------------------------------------------------

    var engineVersion by mutableStateOf<String?>(null)
        private set
    var engineChecking by mutableStateOf(false)
        private set
    /** Last check result shown on the engine screen. */
    var engineNote by mutableStateOf<String?>(null)
        private set
    var parts by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun loadEngine() = viewModelScope.launch {
        engineVersion = g.engine.version()
        if (parts.isEmpty()) parts = runCatching { g.engine.partVersions() }.getOrDefault(emptyMap())
    }

    fun checkEngine(onResult: (String) -> Unit) {
        if (engineChecking) return
        engineChecking = true
        viewModelScope.launch {
            val r = runCatching { g.engine.update(settings.value.channel) }
            repo.setYtdlpLastCheck(System.currentTimeMillis())
            engineVersion = g.engine.version()
            engineChecking = false
            val msg = when {
                r.isFailure -> "Couldn't reach GitHub. Check your connection."
                r.getOrNull() != null -> "Updated yt-dlp to ${r.getOrNull()}."
                else -> "yt-dlp is up to date."
            }
            engineNote = if (r.isFailure) "failed" else null
            onResult(msg)
        }
    }

    fun restoreBundled(onResult: (String) -> Unit) {
        if (engineChecking) return
        engineChecking = true
        viewModelScope.launch {
            val v = runCatching { g.engine.restoreBundled() }.getOrNull()
            engineVersion = v ?: g.engine.version()
            engineChecking = false
            onResult(if (v != null) "Restored yt-dlp $v." else "Couldn't restore the bundled version.")
        }
    }

    fun setChannel(c: Channel) = viewModelScope.launch { repo.setChannel(c) }
    fun setAutoUpdate(on: Boolean) = viewModelScope.launch { repo.setYtdlpAutoUpdate(on) }

    // Downloads ----------------------------------------------------------------------------------

    fun setFolder(uri: Uri?) = viewModelScope.launch {
        if (uri == null) {
            repo.setSaveFolder(null, DEFAULT_FOLDER_LABEL)
        } else {
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            repo.setSaveFolder(uri.toString(), Storage.labelForTree(uri))
        }
    }

    fun setVideo(height: Int, container: String) = viewModelScope.launch { repo.setVideoPreset(height, container) }
    fun setAudio(format: AudioFormat, bitrate: Int, cover: Boolean) = viewModelScope.launch { repo.setAudioPreset(format, bitrate, cover) }
    fun setShare(a: ShareAction) = viewModelScope.launch { repo.setShareAction(a) }
    fun setConcurrent(n: Int) = viewModelScope.launch { repo.setConcurrent(n); g.queue.pump() }
    fun setMobileData(on: Boolean) = viewModelScope.launch { repo.setMobileData(on); g.queue.pump() }
    fun setAria2c(on: Boolean) = viewModelScope.launch { repo.setAria2c(on) }

    // Appearance and updates ---------------------------------------------------------------------

    fun setTheme(t: ThemeMode) = viewModelScope.launch { repo.setTheme(t) }
    fun setWallpaper(on: Boolean) = viewModelScope.launch { repo.setWallpaperColours(on) }
    fun setAppUpdateCheck(on: Boolean) = viewModelScope.launch { repo.setAppUpdateCheck(on) }

    var appChecking by mutableStateOf(false)
        private set

    fun checkApp(onResult: (String?) -> Unit) {
        if (appChecking) return
        if (!g.updater.configured) { onResult("Update checks aren't set up in this build."); return }
        appChecking = true
        viewModelScope.launch {
            val r = runCatching { g.updater.check() }
            repo.setAppLastCheck(System.currentTimeMillis())
            appChecking = false
            onResult(
                when {
                    r.isFailure -> "Couldn't check for updates. Check your connection."
                    r.getOrNull() == null -> "You're on the latest version."
                    else -> null // the update sheet opens
                },
            )
        }
    }

    /** SHA-256 of the certificate this copy was signed with, as colon-separated hex. */
    val signingFingerprint: String by lazy {
        runCatching {
            val ctx = getApplication<Application>()
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            } else {
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            val cert = sigs?.lastOrNull()?.toByteArray() ?: return@runCatching "Unknown"
            MessageDigest.getInstance("SHA-256").digest(cert).joinToString(":") { "%02X".format(it) }
        }.getOrDefault("Unknown")
    }
}
