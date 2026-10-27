package app.haul.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.BuildConfig
import app.haul.data.AppSettings
import app.haul.data.AudioFormat
import app.haul.data.ShareAction
import app.haul.data.SubtitleMode
import app.haul.data.ThemeMode
import app.haul.engine.FormatOptions
import app.haul.ui.components.ChoiceRow
import app.haul.ui.components.FilterChip
import app.haul.ui.components.HSwitch
import app.haul.ui.components.HaulSheet
import app.haul.ui.components.Segmented
import app.haul.ui.components.SettingsGroup
import app.haul.ui.components.SettingsRow
import app.haul.ui.components.SnackState
import app.haul.ui.components.Stepper
import app.haul.ui.components.T
import app.haul.ui.components.TitleBar
import app.haul.ui.components.Trailing
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.Haul
import app.haul.util.Pretty

/** Screen 19. */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    snack: SnackState,
    onBack: () -> Unit,
    onEngine: () -> Unit,
    onAccounts: () -> Unit,
    onAbout: () -> Unit,
) {
    val c = Haul.colors
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setFolder(uri)
    }
    var folderSheet by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TitleBar("Settings", onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SettingsGroup("DOWNLOADS") {
                    SettingsRow("Save to", s.saveLabel, { folderSheet = true }, first = true, monoSubtitle = true)
                    SettingsRow("Default video quality", "${s.videoHeight}p ${s.videoContainer.uppercase()}", { vm.picker = SettingsViewModel.Picker.Video })
                    SettingsRow("Default audio format", s.audioPresetLong, { vm.picker = SettingsViewModel.Picker.Audio })
                    SettingsRow(
                        "Subtitles",
                        if (s.subtitleLang.isBlank()) "Off" else "${FormatOptions.languageName(s.subtitleLang)}, ${s.subtitleMode.label.lowercase()}",
                        { vm.picker = SettingsViewModel.Picker.Subtitles },
                    )
                    SettingsRow("When a link is shared to Haul", s.shareAction.label, { vm.picker = SettingsViewModel.Picker.Share })
                    SettingsRow(
                        "Downloads at the same time", "More is faster but heavier on battery", null,
                        trailingContent = { Stepper(s.concurrent, 1, 4) { vm.setConcurrent(it) } },
                    )
                    SettingsRow("Download on mobile data", "Wi-Fi is always used first", { vm.setMobileData(!s.mobileData) }, switch = s.mobileData)
                    SettingsRow("Faster downloads (aria2c)", "Several connections per file", { vm.setAria2c(!s.aria2c) }, switch = s.aria2c)
                }
                SettingsGroup("ACCOUNTS") {
                    val n = s.signedInSites.size
                    SettingsRow(
                        "Sign in to sites",
                        if (n == 0) "For private, members-only and age-restricted videos" else "$n signed in",
                        onAccounts, first = true,
                    )
                }
                SettingsGroup("DOWNLOAD ENGINE") {
                    val v = vm.engineVersion ?: s.ytdlpBundledVersion ?: "…"
                    SettingsRow("yt-dlp", "$v · ${s.channel.label} · ${Pretty.relativeCheck(s.ytdlpLastCheck)}", onEngine, first = true)
                }
                SettingsGroup("APPEARANCE") {
                    SettingsRow("Theme", s.theme.label, { vm.picker = SettingsViewModel.Picker.Theme }, first = true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SettingsRow(
                            "Use wallpaper colours", "Replaces signal orange with your Material You accent",
                            { vm.setWallpaper(!s.wallpaperColours) }, switch = s.wallpaperColours,
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        SettingsRow("Language", "System (${java.util.Locale.getDefault().displayLanguage})", {
                            runCatching {
                                ctx.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                            }
                        })
                    } else {
                        SettingsRow("Language", "English", null, trailing = Trailing.None)
                    }
                }
                SettingsGroup("UPDATES") {
                    val site = Uri.parse(BuildConfig.WEBSITE_URL).host?.removePrefix("www.") ?: "[YOUR SITE]"
                    SettingsRow(
                        "Check for app updates", "Once a day from $site · ${Pretty.relativeCheck(s.appLastCheck)}",
                        { vm.setAppUpdateCheck(!s.appUpdateCheck) }, switch = s.appUpdateCheck,
                    )
                    SettingsRow(
                        "Check now", if (vm.appChecking) "Checking…" else "Version ${BuildConfig.VERSION_NAME}",
                        { vm.checkApp { msg -> if (msg != null) snack.show(msg) } },
                    )
                }
                SettingsGroup("ABOUT") {
                    SettingsRow("About, licences and terms", "Open source under GPL-3.0", onAbout, first = true)
                }
                T(
                    "HAUL ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · YT-DLP ${vm.engineVersion ?: s.ytdlpBundledVersion ?: "—"} · ${(Build.SUPPORTED_ABIS.firstOrNull() ?: "").uppercase()}",
                    mono(11.sp, tracking = 0.04f), c.textLow, Modifier.fillMaxWidth(), align = TextAlign.Center,
                )
            }
        }
        FolderSheet(folderSheet, s, onDismiss = { folderSheet = false }, onDefault = { folderSheet = false; vm.setFolder(null) }) {
            folderSheet = false
            folderPicker.launch(null)
        }
        Pickers(vm, s)
    }
}

@Composable
private fun FolderSheet(visible: Boolean, s: AppSettings, onDismiss: () -> Unit, onDefault: () -> Unit, onPick: () -> Unit) {
    val c = Haul.colors
    HaulSheet(visible, onDismiss) {
        Spacer(Modifier.height(16.dp))
        T("Save to", Haul.type.headline, c.textHi)
        Spacer(Modifier.height(6.dp))
        T("Download/Haul works with every gallery and music app. Pick another folder if you prefer, such as one on an SD card.", sans(13.sp, lineHeight = 18.sp), c.textLow)
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChoiceRow("Download/Haul", "Default, visible to other apps", "", s.saveTreeUri == null, onDefault)
            ChoiceRow(
                if (s.saveTreeUri != null) s.saveLabel else "Another folder…",
                if (s.saveTreeUri != null) "Tap to choose a different one" else "Choose with the system picker",
                "", s.saveTreeUri != null, onPick,
            )
        }
    }
}

@Composable
private fun Pickers(vm: SettingsViewModel, s: AppSettings) {
    val c = Haul.colors
    val close = { vm.picker = null }
    val p = vm.picker
    HaulSheet(p == SettingsViewModel.Picker.Video, close) {
        SheetTitle("Default video quality", "Used for playlists and when a link is shared with Save video.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("mp4", "webm").forEach { ct ->
                FilterChip(ct.uppercase(), s.videoContainer == ct, { vm.setVideo(s.videoHeight, ct) }, monoLabel = true, hPad = 10.dp, height = 30.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(2160, 1440, 1080, 720, 480, 360).forEach { h ->
                ChoiceRow(
                    FormatOptions.heightLabel(h), if (h == 2160) "4K, large files" else if (h <= 480) "Small files, saves data" else "Up to ${h}p when available",
                    "", s.videoHeight == h, { vm.setVideo(h, s.videoContainer) },
                    badge = if (h == 1080) "Best for phones" else null,
                )
            }
        }
    }
    HaulSheet(p == SettingsViewModel.Picker.Audio, close) {
        SheetTitle("Default audio format", "Used for playlists and when a link is shared with Save audio only.")
        val bitrateRow: @Composable ColumnScope.() -> Unit = {
            val rates = listOf(128, 192, 320)
            Segmented(
                rates.map { if (it == s.mp3Bitrate) "$it KBPS" else "$it" },
                rates.indexOf(s.mp3Bitrate).coerceAtLeast(0),
                { vm.setAudio(AudioFormat.MP3, rates[it], s.embedCover) },
                Modifier.padding(start = 34.dp), compact = true, monoLabels = true, trackColor = c.surface1,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AudioFormat.entries.forEach { f ->
                val spec = when (f) {
                    AudioFormat.MP3 -> "Plays everywhere"
                    AudioFormat.M4A -> "No re-encoding, usually the original"
                    AudioFormat.OPUS -> "Smallest, newer players only"
                }
                ChoiceRow(
                    f.label, spec, "", s.audioFormat == f, { vm.setAudio(f, s.mp3Bitrate, s.embedCover) },
                    extra = if (f == AudioFormat.MP3) bitrateRow else null,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (s.audioFormat != AudioFormat.OPUS) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Switch) {
                    vm.setAudio(s.audioFormat, s.mp3Bitrate, !s.embedCover)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("Embed cover art", sans(15.sp), c.textHi, Modifier.weight(1f))
                HSwitch(s.embedCover)
            }
        }
    }
    HaulSheet(p == SettingsViewModel.Picker.Share, close) {
        SheetTitle("When a link is shared to Haul", null)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ShareAction.entries.forEach { a ->
                val spec = when (a) {
                    ShareAction.ASK -> "Shows video and audio choices"
                    ShareAction.VIDEO -> "Starts at ${s.videoHeight}p ${s.videoContainer.uppercase()} right away"
                    ShareAction.AUDIO -> "Starts as ${s.audioPresetShort} right away"
                }
                ChoiceRow(a.label, spec, "", s.shareAction == a, { vm.setShare(a); close() })
            }
        }
    }
    HaulSheet(p == SettingsViewModel.Picker.Subtitles, close) {
        SheetTitle("Subtitles", "Picked in the download sheet when a video has them in this language.")
        val device = java.util.Locale.getDefault().language
        val langs = listOf(device, "en").distinct()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChoiceRow("Off", "Choose per video", "", s.subtitleLang.isBlank(), { vm.setSubtitles("", s.subtitleMode) })
            langs.forEach { l ->
                ChoiceRow(
                    FormatOptions.languageName(l), if (l == device) "Your phone's language" else "", "",
                    s.subtitleLang == l, { vm.setSubtitles(l, s.subtitleMode) },
                )
            }
        }
        if (s.subtitleLang.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Segmented(
                SubtitleMode.entries.map { if (it == SubtitleMode.EMBED) "Embed" else "Save .srt" },
                s.subtitleMode.ordinal,
                { vm.setSubtitles(s.subtitleLang, SubtitleMode.entries[it]) },
                compact = true, trackColor = c.surface1,
            )
        }
    }
    HaulSheet(p == SettingsViewModel.Picker.Theme, close) {
        SheetTitle("Theme", null)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ThemeMode.entries.forEach { t ->
                ChoiceRow(t.label, "", "", s.theme == t, { vm.setTheme(t); close() })
            }
        }
    }
}

@Composable
private fun ColumnScope.SheetTitle(title: String, sub: String?) {
    val c = Haul.colors
    Spacer(Modifier.height(16.dp))
    T(title, Haul.type.headline, c.textHi)
    if (sub != null) {
        Spacer(Modifier.height(6.dp))
        T(sub, sans(13.sp, lineHeight = 18.sp), c.textLow)
    }
    Spacer(Modifier.height(16.dp))
}
