package app.haul.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import app.haul.HaulApp
import app.haul.MainActivity
import app.haul.BuildConfig
import app.haul.data.AppSettings
import app.haul.data.Pairing
import app.haul.data.MediaType
import app.haul.data.ShareAction
import app.haul.engine.DownloadRequest
import app.haul.engine.FormatOptions
import app.haul.engine.MediaInfo
import app.haul.ui.components.CheckBoxMark
import app.haul.ui.components.HaulSheet
import app.haul.ui.components.Skeleton
import app.haul.ui.components.T
import app.haul.ui.components.Thumb
import app.haul.ui.components.Wordmark
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.ui.theme.HaulTheme
import app.haul.util.Links
import app.haul.util.Pretty
import kotlinx.coroutines.launch

/** Screen 05: a sheet over the app the link was shared from. Downloads run in the background. */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val link = intent?.data
        if (link?.scheme == "haul" && link.host == "pair") {
            showPair(link.toString())
            return
        }
        val text = intent?.data?.takeIf { it.scheme == "haul" }?.getQueryParameter("url")
            ?: intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        val url = Links.extract(text)
        if (url == null) {
            Toast.makeText(this, "Haul couldn't find a link in what was shared.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val g = HaulApp.graph(this)
        lifecycleScope.launch {
            val s = g.settings.current()
            when (s.shareAction) {
                ShareAction.VIDEO, ShareAction.AUDIO -> {
                    enqueuePreset(url, null, s, s.shareAction == ShareAction.AUDIO)
                    toastSaving(s.shareAction == ShareAction.AUDIO)
                    finish()
                }
                ShareAction.ASK -> setContent {
                    val settings by g.settings.settings.collectAsState(s)
                    HaulTheme(settings.theme, settings.wallpaperColours) {
                        ShareSheet(url, settings, onClose = { finish() })
                    }
                }
            }
        }
    }

    private fun showPair(link: String) {
        val pairing = Pairing.fromLink(link, BuildConfig.DEBUG)
        if (pairing == null) {
            Toast.makeText(this, "That pairing link isn't valid. Make a new one in your browser.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val g = HaulApp.graph(this)
        setContent {
            val settings by g.settings.settings.collectAsState(AppSettings())
            HaulTheme(settings.theme, settings.wallpaperColours) {
                PairSheet(
                    pairing,
                    onConnect = { g.pcInbox.connect(pairing) },
                    onConnected = {
                        Toast.makeText(this, "Connected to ${pairing.host}.", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private suspend fun enqueuePreset(url: String, info: MediaInfo?, s: AppSettings, audio: Boolean) {
        val g = HaulApp.graph(this)
        if (info != null && info.isPlaylist) {
            info.entries.forEach { e ->
                val entryInfo = MediaInfo(
                    url = e.url, title = e.title, uploader = info.uploader, site = info.site, host = MediaInfo.hostOf(e.url),
                    thumbnail = e.thumbnail, durationSec = e.durationSec, formats = emptyList(),
                )
                g.queue.enqueue(entryInfo, e.url, presetFor(s, audio, e.durationSec, null))
            }
        } else {
            g.queue.enqueue(info, url, presetFor(s, audio, info?.durationSec ?: 0, info))
        }
    }

    private fun toastSaving(audio: Boolean) {
        Toast.makeText(this, if (audio) "Saving audio in Haul." else "Saving video in Haul.", Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun ShareSheet(url: String, s: AppSettings, onClose: () -> Unit) {
        val c = Haul.colors
        val g = HaulApp.graph(this)
        val scope = rememberCoroutineScope()
        var visible by remember { mutableStateOf(false) }
        var info by remember { mutableStateOf(g.infoCache[url]) }
        var failed by remember { mutableStateOf(false) }
        var rememberChoice by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        LaunchedEffect(url) {
            if (info != null) return@LaunchedEffect
            val r = runCatching { g.engine.fetch(url, "share-${url.hashCode()}") }
            r.getOrNull()?.let { g.infoCache[url] = it; info = it }
            failed = r.isFailure
        }
        fun pick(audio: Boolean) {
            scope.launch {
                if (rememberChoice) g.settings.setShareAction(if (audio) ShareAction.AUDIO else ShareAction.VIDEO)
                enqueuePreset(url, info, s, audio)
                toastSaving(audio)
                visible = false
                onClose()
            }
        }
        Box(Modifier.fillMaxSize()) {
            HaulSheet(visible, { visible = false; onClose() }) {
                Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Wordmark(20.sp, Modifier.weight(1f))
                    T("FROM ${(info?.host ?: MediaInfo.hostOf(url)).uppercase()}", mono(11.sp), c.textLow, maxLines = 1)
                }
                Spacer(Modifier.height(16.dp))
                val i = info
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (i != null) {
                        Thumb(i.thumbnail, 120.dp, 68.dp, durationSec = i.durationSec, badgeInset = 5.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            T(i.title, sans(15.sp, FontWeight.SemiBold, 20.sp), c.textHi, maxLines = 3)
                            val sub = if (i.isPlaylist) "Playlist · ${i.entries.size} items" else i.uploader
                            if (sub.isNotBlank()) T(sub, sans(12.sp), c.textLow, maxLines = 1)
                        }
                    } else if (failed) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            T(Pretty.shortUrl(url), mono(13.sp), c.textHi, maxLines = 2)
                            T("Couldn't read this link yet. Haul will try again when it downloads.", sans(12.sp, lineHeight = 17.sp), c.textLow)
                        }
                    } else {
                        Skeleton(Modifier.size(120.dp, 68.dp), radius = 8.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Skeleton(Modifier.fillMaxWidth().height(14.dp))
                            Skeleton(Modifier.width(140.dp).height(14.dp))
                            Skeleton(Modifier.width(90.dp).height(10.dp))
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                val count = if (i?.isPlaylist == true) i.entries.size else 1
                val dur = i?.durationSec ?: 0
                val vReq = presetFor(s, false, dur, i)
                val aReq = presetFor(s, true, dur, i)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Option(
                        HaulIcon.Video, c.signal, c.signalTint, "Save video",
                        listOfNotNull(if (count > 1) "$count ITEMS" else null, vReq.formatLabel, sizeOf(vReq)).joinToString(" · "),
                    ) { pick(false) }
                    Option(
                        HaulIcon.Audio, c.tape, c.tapeTint, "Save audio only",
                        listOfNotNull(
                            if (count > 1) "$count ITEMS" else null,
                            if (s.audioFormat == app.haul.data.AudioFormat.MP3) "MP3 ${s.mp3Bitrate} KBPS" else aReq.formatLabel,
                            sizeOf(aReq),
                        ).joinToString(" · "),
                    ) { pick(true) }
                }
                Row(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            startActivity(
                                Intent(this@ShareActivity, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_URL, url)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            )
                            onClose()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    T("Choose quality and format", sans(14.sp, FontWeight.SemiBold), c.signal)
                    Spacer(Modifier.width(6.dp))
                    HIcon(HaulIcon.ChevRight, c.signal, size = 16.dp, stroke = 2f)
                }
                Row(
                    Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Checkbox) { rememberChoice = !rememberChoice },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CheckBoxMark(rememberChoice)
                    T("Remember my choice and skip this next time", sans(14.sp), c.textMid)
                }
                T(
                    "DOWNLOADS IN THE BACKGROUND · KEEP WATCHING",
                    mono(10.sp, tracking = 0.06f), c.textLow,
                    Modifier.padding(top = 8.dp).fillMaxWidth(), align = TextAlign.Center,
                )
            }
        }
    }

    @Composable
    private fun Option(icon: HaulIcon, tint: Color, tile: Color, title: String, spec: String, onClick: () -> Unit) {
        val c = Haul.colors
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.surface2, shape)
                .border(1.dp, c.line, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(tile), contentAlignment = Alignment.Center) {
                HIcon(icon, tint, size = 20.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(title, sans(15.sp, FontWeight.SemiBold), c.textHi)
                T(spec, mono(11.sp), c.textMid, maxLines = 1)
            }
            HIcon(HaulIcon.Queue, c.textMid, size = 20.dp)
        }
    }

    private fun sizeOf(r: DownloadRequest): String? = r.estimatedBytes.takeIf { it > 0 }?.let { Pretty.bytes(it) }
}

/** The share presets. With formats in hand the video size comes from the site, not a guess. */
internal fun presetFor(s: AppSettings, audio: Boolean, durationSec: Int, info: MediaInfo?): DownloadRequest {
    if (audio) return FormatOptions.presetAudioRequest(s.audioFormat, s.mp3Bitrate, s.embedCover, durationSec)
    val base = FormatOptions.presetVideoRequest(s.videoHeight, s.videoContainer, durationSec)
    if (info == null || info.formats.isEmpty()) return base
    val choice = FormatOptions.videoChoices(info, s.videoContainer)
        .let { list -> list.firstOrNull { it.height <= s.videoHeight } ?: list.lastOrNull() } ?: return base
    return FormatOptions.videoRequest(choice).let { if (it.mediaType == MediaType.VIDEO) it else base }
}
