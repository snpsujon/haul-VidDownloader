package app.haul.ui.grab

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import app.haul.data.DownloadEntity
import app.haul.data.MediaType
import app.haul.data.Status
import app.haul.ui.components.AudioTile
import app.haul.ui.components.ButtonKind
import app.haul.ui.components.Card
import app.haul.ui.components.Divider
import app.haul.ui.components.ErrorCard
import app.haul.ui.components.Gutter
import app.haul.ui.components.HButton
import app.haul.ui.components.LinkSlot
import app.haul.ui.components.ScanTicks
import app.haul.ui.components.SectionLabel
import app.haul.ui.components.ShapeMd
import app.haul.ui.components.Skeleton
import app.haul.ui.components.T
import app.haul.ui.components.Tag
import app.haul.ui.components.TagKind
import app.haul.ui.components.TapeProgress
import app.haul.ui.components.TapeState
import app.haul.ui.components.TechnicalDetails
import app.haul.ui.components.TextAction
import app.haul.ui.components.Thumb
import app.haul.ui.components.WordmarkBar
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.engine.Errors
import app.haul.util.Links
import app.haul.util.Pretty

/** Screens 03 (first run), 04 (with recents), 06 (reading the link) and 13 (no connection). */
@Composable
fun GrabScreen(
    vm: GrabViewModel,
    recents: List<DownloadEntity>,
    defaultVideo: String,
    onSettings: () -> Unit,
    onSeeAll: () -> Unit,
    onOpenItem: (DownloadEntity) -> Unit,
    onPlaylist: () -> Unit,
    onQueued: () -> Unit,
    onSnack: (String) -> Unit,
) {
    val c = Haul.colors
    val context = LocalContext.current
    var clip by remember { mutableStateOf<String?>(null) }
    val focused = LocalWindowInfo.current.isWindowFocused
    // The clipboard can only be read while the window has focus.
    LaunchedEffect(focused) {
        if (focused) clip = readClipboardLink(context)
    }

    Column(Modifier.fillMaxSize()) {
        WordmarkBar(onSettings)
        when (val p = vm.phase) {
            is GrabViewModel.Phase.Fetching -> FetchingBody(vm, p)
            is GrabViewModel.Phase.Offline -> OfflineBody(vm, defaultVideo, onQueued, onPlaylist)
            else -> {
                val empty = recents.isEmpty()
                BoxWithConstraints(Modifier.weight(1f)) {
                val minH = maxHeight
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier
                        .heightIn(min = minH)
                        .padding(start = Gutter, end = Gutter, top = if (empty) 24.dp else 16.dp),
                ) {
                    T("What are we\nsaving today?", Haul.type.display, c.textHi)
                    if (empty) {
                        Spacer(Modifier.height(10.dp))
                        T("Paste a link from any of 1,000+ sites, or share it here from another app.", Haul.type.body, c.textMid)
                    }
                    Spacer(Modifier.height(if (empty) 28.dp else 24.dp))
                    SectionLabel("Link")
                    Spacer(Modifier.height(8.dp))
                    LinkSlot(
                        value = vm.link,
                        onValueChange = vm::onLinkChange,
                        onPaste = {
                            val text = readClipboardText(context)
                            if (text.isNullOrBlank()) onSnack("Your clipboard is empty.") else vm.submit(text, onPlaylist)
                        },
                        onClear = vm::clear,
                        onGo = { vm.submit(vm.link, onPlaylist) },
                        error = vm.linkError,
                        showPaste = vm.link.isEmpty(),
                    )
                    val cl = clip
                    if (cl != null && vm.link.isEmpty() && empty) {
                        Spacer(Modifier.height(12.dp))
                        ClipboardCard(cl) { vm.submit(cl, onPlaylist) }
                    }
                    if (vm.link.isNotEmpty() && vm.phase is GrabViewModel.Phase.Idle && vm.linkError == null) {
                        Spacer(Modifier.height(12.dp))
                        HButton("Read link", { vm.submit(vm.link, onPlaylist) }, Modifier.fillMaxWidth())
                    }
                    (vm.phase as? GrabViewModel.Phase.Failed)?.let { f ->
                        Spacer(Modifier.height(16.dp))
                        FetchError(vm, f, onPlaylist, onSnack)
                    }
                    if (empty) {
                        Spacer(Modifier.height(36.dp))
                        Steps()
                    } else {
                        if (cl != null && vm.link.isEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            ClipboardCard(cl) { vm.submit(cl, onPlaylist) }
                        }
                        Spacer(Modifier.height(36.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("Recent", Modifier.weight(1f))
                            TextAction("See all", onSeeAll, height = 36.dp)
                        }
                        Spacer(Modifier.height(8.dp))
                        recents.take(3).forEach { RecentRow(it) { onOpenItem(it) } }
                    }
                    Spacer(Modifier.weight(1f))
                    if (empty) {
                        Spacer(Modifier.height(24.dp))
                        T(
                            "Download only what you own or have permission to save.",
                            sans(12.sp, lineHeight = 17.sp), c.textLow, Modifier.padding(bottom = 16.dp),
                        )
                    }
                }
                }
                }
            }
        }
    }
}

@Composable
private fun ClipboardCard(url: String, onUse: () -> Unit) {
    val c = Haul.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ShapeMd)
            .background(c.surface2, ShapeMd)
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HIcon(HaulIcon.Link, c.textMid, size = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T("Link in your clipboard", sans(12.sp), c.textLow)
            T(Pretty.shortUrl(url), mono(13.sp), c.textHi, maxLines = 1)
        }
        TextAction("Use it", onUse, height = 40.dp)
    }
}

@Composable
private fun Steps() {
    val c = Haul.colors
    Column(Modifier.fillMaxWidth()) {
        Divider()
        listOf(
            "Paste or share" to " a link",
            "Pick video or audio" to " and the quality",
            "Find it in Library" to ", even offline",
        ).forEachIndexed { i, (bold, rest) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                T("0${i + 1}", mono(12.sp), c.signal, Modifier.width(20.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c.textHi, fontWeight = FontWeight.SemiBold)) { append(bold) }
                        append(rest)
                    },
                    style = sans(14.sp),
                    color = c.textMid,
                )
            }
            Divider()
        }
    }
}

@Composable
private fun RecentRow(item: DownloadEntity, onClick: () -> Unit) {
    val c = Haul.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Thumb(item.thumbnailUrl, 96.dp, 54.dp, durationSec = item.durationSec)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(item.title.ifBlank { Pretty.shortUrl(item.url) }, sans(14.sp, FontWeight.SemiBold, 18.sp), c.textHi, maxLines = 1)
                if (item.status == Status.DONE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.mediaType == MediaType.AUDIO) Tag("Audio", TagKind.Audio) else Tag("Video", TagKind.Video)
                        T("${item.formatLabel} · ${Pretty.bytes(item.fileSize)}", mono(11.sp), c.textMid)
                    }
                    T(listOf(item.uploader, Pretty.relative(item.finishedAt)).filter { it.isNotBlank() }.joinToString(" · "), sans(12.sp), c.textLow)
                } else {
                    TapeProgress(item.progress, tapeState(item), ticks = 30, height = 10.dp)
                    T(statusLine(item), mono(11.sp, tracking = 0.04f), c.textMid, maxLines = 1)
                }
            }
        }
        Divider()
    }
}

@Composable
private fun FetchingBody(vm: GrabViewModel, p: GrabViewModel.Phase.Fetching) {
    val c = Haul.colors
    Column(Modifier.fillMaxSize().padding(start = Gutter, end = Gutter, top = 16.dp)) {
        SectionLabel("Link")
        Spacer(Modifier.height(8.dp))
        LinkSlot(vm.link, {}, {}, vm::clear, {}, enabled = false, showPaste = false)
        Spacer(Modifier.height(24.dp))
        Card(padding = 16.dp) {
            Skeleton(Modifier.fillMaxWidth().height(182.dp), radius = 8.dp)
            Spacer(Modifier.height(14.dp))
            Skeleton(Modifier.fillMaxWidth(0.85f).height(16.dp))
            Spacer(Modifier.height(14.dp))
            Skeleton(Modifier.fillMaxWidth(0.55f).height(16.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Skeleton(Modifier.size(58.dp, 22.dp), radius = 6.dp)
                Skeleton(Modifier.size(72.dp, 22.dp), radius = 6.dp)
            }
        }
        Spacer(Modifier.height(20.dp))
        ScanTicks()
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            p.lines.forEachIndexed { i, l ->
                T(l, mono(12.sp, lineHeight = 16.sp), if (i == p.lines.lastIndex) c.textHi else c.textLow)
            }
        }
        Spacer(Modifier.weight(1f))
        HButton("Cancel", vm::cancelFetch, Modifier.fillMaxWidth().padding(bottom = 16.dp), kind = ButtonKind.Secondary)
    }
}

@Composable
private fun OfflineBody(vm: GrabViewModel, defaultVideo: String, onQueued: () -> Unit, onPlaylist: () -> Unit) {
    val c = Haul.colors
    Column(Modifier.fillMaxSize().padding(start = Gutter, end = Gutter, top = 16.dp)) {
        SectionLabel("Link")
        Spacer(Modifier.height(8.dp))
        LinkSlot(vm.link, vm::onLinkChange, {}, vm::clear, { vm.retry(onPlaylist) }, showPaste = false)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Box(
                Modifier.size(72.dp).clip(ShapeMd).background(c.surface1, ShapeMd).border(1.dp, c.line, ShapeMd),
                contentAlignment = Alignment.Center,
            ) {
                HIcon(HaulIcon.OfflineLarge, c.textMid, size = 34.dp, stroke = 1.5f)
            }
            SectionLabel("No connection")
            T("You're offline.", Haul.type.display, c.textHi)
            T(
                "We can't read this link without the internet. Queue it now and it starts on its own as soon as you're back online.",
                Haul.type.body, c.textMid,
            )
        }
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton("Queue for when I'm online", { vm.queueOffline(onQueued) }, Modifier.fillMaxWidth())
            HButton("Try again", { vm.retry(onPlaylist) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
            T(
                "Queued links use your default format: $defaultVideo. Change it in Settings.",
                sans(12.sp, lineHeight = 17.sp), c.textLow, Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun FetchError(vm: GrabViewModel, f: GrabViewModel.Phase.Failed, onPlaylist: () -> Unit, onSnack: (String) -> Unit) {
    val c = Haul.colors
    var open by remember { mutableStateOf(false) }
    ErrorCard(f.title, f.fix) {
        if (f.suggestUpdate) {
            HButton(
                "Update engine and retry",
                { vm.updateEngineAndRetry(onPlaylist, onSnack) },
                Modifier.fillMaxWidth(), height = 44.dp, icon = HaulIcon.Retry, radius = 10.dp,
            )
        }
        HButton(
            "Retry only", { vm.retry(onPlaylist) }, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost,
            height = 44.dp, radius = 10.dp, textColor = c.textHi, border = c.errLine,
        )
        if (f.log.isNotBlank()) TechnicalDetails(f.log, open) { open = !open }
    }
}

fun tapeState(item: DownloadEntity): TapeState = when (item.status) {
    Status.PAUSED -> TapeState.Paused
    Status.PROCESSING -> TapeState.Merging
    Status.DONE -> TapeState.Done
    Status.FAILED -> TapeState.Failed
    else -> TapeState.Downloading
}

/** "62% · 255/412 MB · 4.2 MB/S · 0:38" */
fun statusLine(item: DownloadEntity, withBytes: Boolean = false, withLeft: Boolean = true, now: Long = System.currentTimeMillis()): String {
    val pct = "${(item.progress * 100).toInt()}%"
    return when (item.status) {
        Status.PROCESSING -> if (item.mediaType == MediaType.AUDIO) "CONVERTING AUDIO…" else "MERGING AUDIO + VIDEO…"
        Status.PAUSED -> listOf("PAUSED", pct, if (item.totalBytes > 0) Pretty.bytesPair(item.downloadedBytes, item.totalBytes) else "")
            .filter { it.isNotEmpty() }.joinToString(" · ")
        Status.QUEUED -> "${item.formatLabel} · STARTS WHEN A SLOT FREES"
        Status.WAITING_NETWORK -> "${item.formatLabel} · WAITING FOR A CONNECTION"
        Status.RETRYING -> {
            val left = ((item.retryAt - now) / 1000).coerceAtLeast(0)
            "RETRY ${item.retryCount} OF ${Errors.MAX_AUTO_RETRIES} " + if (left > 0) "IN ${Pretty.eta(left)}" else "WHEN ONLINE"
        }
        Status.FAILED -> "FAILED AT ${(item.failedAtProgress * 100).toInt()}%"
        Status.DONE -> "SAVED · ${item.formatLabel} · ${Pretty.bytes(item.fileSize)}"
        Status.DOWNLOADING -> {
            if (item.progress <= 0f && item.downloadedBytes <= 0) "STARTING…"
            else listOf(
                pct,
                if (withBytes && item.totalBytes > 0) Pretty.bytesPair(item.downloadedBytes, item.totalBytes) else "",
                Pretty.speed(item.speedBps),
                if (item.etaSec >= 0) Pretty.eta(item.etaSec) + if (withLeft) " LEFT" else "" else "",
            ).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }
}

private fun readClipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

private fun readClipboardLink(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    // Only peek when the clipboard says it holds text, to avoid a needless read.
    if (cm.primaryClipDescription?.hasMimeType("text/*") != true) return null
    Links.extract(readClipboardText(context))
}.getOrNull()
