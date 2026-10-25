package app.haul.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("Same as system") }

enum class ShareAction(val label: String) {
    ASK("Ask every time"), VIDEO("Save video"), AUDIO("Save audio only")
}

enum class AudioFormat(val label: String, val ext: String) {
    MP3("MP3", "mp3"), M4A("M4A", "m4a"), OPUS("Opus", "opus")
}

enum class Channel(val label: String) { STABLE("Stable"), NIGHTLY("Nightly") }

data class AppSettings(
    val onboarded: Boolean = false,
    val saveTreeUri: String? = null,
    val saveLabel: String = DEFAULT_FOLDER_LABEL,
    val videoHeight: Int = 1080,
    val videoContainer: String = "mp4",
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val mp3Bitrate: Int = 320,
    val embedCover: Boolean = true,
    val shareAction: ShareAction = ShareAction.ASK,
    val concurrent: Int = 2,
    val mobileData: Boolean = true,
    val aria2c: Boolean = false,
    val channel: Channel = Channel.STABLE,
    val ytdlpAutoUpdate: Boolean = true,
    val ytdlpLastCheck: Long = 0L,
    val ytdlpBundledVersion: String? = null,
    val theme: ThemeMode = ThemeMode.DARK,
    val wallpaperColours: Boolean = false,
    val appUpdateCheck: Boolean = true,
    val appLastCheck: Long = 0L,
    val skippedVersionCode: Int = 0,
) {
    /** "1080p MP4" */
    val videoPresetLabel: String get() = "${videoHeight}p ${videoContainer.uppercase()}"

    /** "MP3 320" / "M4A" / "OPUS" */
    val audioPresetShort: String
        get() = when (audioFormat) {
            AudioFormat.MP3 -> "MP3 $mp3Bitrate"
            AudioFormat.M4A -> "M4A"
            AudioFormat.OPUS -> "OPUS"
        }

    /** "MP3, 320 kbps, cover art on" */
    val audioPresetLong: String
        get() {
            val fmt = when (audioFormat) {
                AudioFormat.MP3 -> "MP3, $mp3Bitrate kbps"
                AudioFormat.M4A -> "M4A, original quality"
                AudioFormat.OPUS -> "Opus, original quality"
            }
            return "$fmt, cover art ${if (embedCover && audioFormat != AudioFormat.OPUS) "on" else "off"}"
        }

    companion object {
        const val DEFAULT_FOLDER_LABEL = "Download/Haul"
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val saveTreeUri = stringPreferencesKey("save_tree_uri")
        val saveLabel = stringPreferencesKey("save_label")
        val videoHeight = intPreferencesKey("video_height")
        val videoContainer = stringPreferencesKey("video_container")
        val audioFormat = stringPreferencesKey("audio_format")
        val mp3Bitrate = intPreferencesKey("mp3_bitrate")
        val embedCover = booleanPreferencesKey("embed_cover")
        val shareAction = stringPreferencesKey("share_action")
        val concurrent = intPreferencesKey("concurrent")
        val mobileData = booleanPreferencesKey("mobile_data")
        val aria2c = booleanPreferencesKey("aria2c")
        val channel = stringPreferencesKey("channel")
        val ytdlpAutoUpdate = booleanPreferencesKey("ytdlp_auto_update")
        val ytdlpLastCheck = longPreferencesKey("ytdlp_last_check")
        val ytdlpBundledVersion = stringPreferencesKey("ytdlp_bundled_version")
        val theme = stringPreferencesKey("theme")
        val wallpaper = booleanPreferencesKey("wallpaper_colours")
        val appUpdateCheck = booleanPreferencesKey("app_update_check")
        val appLastCheck = longPreferencesKey("app_last_check")
        val skippedVersion = intPreferencesKey("skipped_version")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboarded = p[K.onboarded] ?: false,
            saveTreeUri = p[K.saveTreeUri],
            saveLabel = p[K.saveLabel] ?: AppSettings.DEFAULT_FOLDER_LABEL,
            videoHeight = p[K.videoHeight] ?: 1080,
            videoContainer = p[K.videoContainer] ?: "mp4",
            audioFormat = p[K.audioFormat]?.let { runCatching { AudioFormat.valueOf(it) }.getOrNull() } ?: AudioFormat.MP3,
            mp3Bitrate = p[K.mp3Bitrate] ?: 320,
            embedCover = p[K.embedCover] ?: true,
            shareAction = p[K.shareAction]?.let { runCatching { ShareAction.valueOf(it) }.getOrNull() } ?: ShareAction.ASK,
            concurrent = p[K.concurrent] ?: 2,
            mobileData = p[K.mobileData] ?: true,
            aria2c = p[K.aria2c] ?: false,
            channel = p[K.channel]?.let { runCatching { Channel.valueOf(it) }.getOrNull() } ?: Channel.STABLE,
            ytdlpAutoUpdate = p[K.ytdlpAutoUpdate] ?: true,
            ytdlpLastCheck = p[K.ytdlpLastCheck] ?: 0L,
            ytdlpBundledVersion = p[K.ytdlpBundledVersion],
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            wallpaperColours = p[K.wallpaper] ?: false,
            appUpdateCheck = p[K.appUpdateCheck] ?: true,
            appLastCheck = p[K.appLastCheck] ?: 0L,
            skippedVersionCode = p[K.skippedVersion] ?: 0,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboarded() = context.dataStore.edit { it[K.onboarded] = true }
    suspend fun setSaveFolder(treeUri: String?, label: String) = context.dataStore.edit {
        if (treeUri == null) it.remove(K.saveTreeUri) else it[K.saveTreeUri] = treeUri
        it[K.saveLabel] = label
    }
    suspend fun setVideoPreset(height: Int, container: String) = context.dataStore.edit {
        it[K.videoHeight] = height
        it[K.videoContainer] = container
    }
    suspend fun setAudioPreset(format: AudioFormat, bitrate: Int, cover: Boolean) = context.dataStore.edit {
        it[K.audioFormat] = format.name
        it[K.mp3Bitrate] = bitrate
        it[K.embedCover] = cover
    }
    suspend fun setShareAction(a: ShareAction) = context.dataStore.edit { it[K.shareAction] = a.name }
    suspend fun setConcurrent(n: Int) = context.dataStore.edit { it[K.concurrent] = n.coerceIn(1, 4) }
    suspend fun setMobileData(on: Boolean) = context.dataStore.edit { it[K.mobileData] = on }
    suspend fun setAria2c(on: Boolean) = context.dataStore.edit { it[K.aria2c] = on }
    suspend fun setChannel(c: Channel) = context.dataStore.edit { it[K.channel] = c.name }
    suspend fun setYtdlpAutoUpdate(on: Boolean) = context.dataStore.edit { it[K.ytdlpAutoUpdate] = on }
    suspend fun setYtdlpLastCheck(t: Long) = context.dataStore.edit { it[K.ytdlpLastCheck] = t }
    suspend fun setYtdlpBundledVersion(v: String) = context.dataStore.edit { it[K.ytdlpBundledVersion] = v }
    suspend fun setTheme(t: ThemeMode) = context.dataStore.edit { it[K.theme] = t.name }
    suspend fun setWallpaperColours(on: Boolean) = context.dataStore.edit { it[K.wallpaper] = on }
    suspend fun setAppUpdateCheck(on: Boolean) = context.dataStore.edit { it[K.appUpdateCheck] = on }
    suspend fun setAppLastCheck(t: Long) = context.dataStore.edit { it[K.appLastCheck] = t }
    suspend fun setSkippedVersion(code: Int) = context.dataStore.edit { it[K.skippedVersion] = code }
}
