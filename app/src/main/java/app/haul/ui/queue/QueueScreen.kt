package app.haul.ui.queue

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.data.DownloadEntity
import app.haul.data.ErrorKind
import app.haul.data.MediaType
import app.haul.data.Status
import app.haul.engine.Errors
import app.haul.ui.components.AudioTile
import app.haul.ui.components.ButtonKind
import app.haul.ui.components.Divider
import app.haul.ui.components.ErrorCard
import app.haul.ui.components.HButton
import app.haul.ui.components.IconButton44
import app.haul.ui.components.SnackState
import app.haul.ui.components.T
import app.haul.ui.components.Tag
import app.haul.ui.components.TagKind
import app.haul.ui.components.TapeProgress
import app.haul.ui.components.TapeState
import app.haul.ui.components.TechnicalDetails
import app.haul.ui.components.TextAction
import app.haul.ui.components.Thumb
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.components.typeTag
import app.haul.ui.grab.statusLine
import app.haul.ui.grab.tapeState
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.util.Pretty

/** Screens 11 (Queue) and 12 (Queue with an error). */
@Composable
fun QueueScreen(vm: QueueViewModel, snack: SnackState, onOpenItem: (DownloadEntity) -> Unit, onGrab: () -> Unit) {
    val c = Haul.colors
    val s by vm.sections.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("Queue", Haul.type.title, c.textHi, Modifier.weight(1f))
            if (s.active.isNotEmpty() || s.waiting.isNotEmpty()) {
                TextAction(if (s.allPaused) "Resume all" else "Pause all", { vm.togglePauseAll(s.allPaused) })
            }
        }
        if (s.isEmpty) {
            EmptyQueue(onGrab)
            return@Column
        }
        val hasError = s.failed.isNotEmpty()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            var first = true
            fun LazyListScope.header(text: String, color: Color? = null) {
                val top = first
                first = false
                item(key = "h-$text") { SectionHeader(text, top, color) }
            }
            if (hasError) {
                header("NEEDS ATTENTION · ${s.failed.size}", c.err)
                items(s.failed, key = { "f-${it.id}" }) { item ->
                    FailedCard(item, vm, snack, last = false)
                }
            }
            if (s.active.isNotEmpty()) {
                header("ACTIVE · ${s.active.size}")
                items(s.active, key = { "a-${it.id}" }) { item ->
                    if (hasError) CompactActiveRow(item) else ActiveCard(item, vm)
                }
            }
            if (s.waiting.isNotEmpty()) {
                header("WAITING · ${s.waiting.size}")
                items(s.waiting, key = { "w-${it.id}" }) { item ->
                    WaitingRow(item, isFirst = item.id == s.waiting.first().id) {
                        vm.moveToFront(item.id)
                        snack.show("Moved to the front of the queue.")
                    }
                }
            }
            if (s.doneToday.isNotEmpty()) {
                header("DONE TODAY · ${s.doneToday.size}")
                items(s.doneToday, key = { "d-${it.id}" }) { item ->
                    DoneRow(item, last = item.id == s.doneToday.last().id) { onOpenItem(item) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, first: Boolean, color: Color?) {
    T(
        text,
        mono(11.sp, tracking = 0.08f),
        color ?: Haul.colors.textLow,
        Modifier.fillMaxWidth().padding(
            start = 20.dp, end = 20.dp,
            top = if (first) 8.dp else 14.dp,
            bottom = if (first) 8.dp else 4.dp,
        ),
    )
}

@Composable
private fun MediaThumb(item: DownloadEntity, w: Dp, h: Dp) {
    if (item.mediaType == MediaType.AUDIO && w == h) AudioTile(size = w)
    else Thumb(item.thumbnailUrl, w, h, durationSec = item.durationSec)
}

@Composable
private fun TitleAndTags(item: DownloadEntity, modifier: Modifier = Modifier) {
    val c = Haul.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T(item.title.ifBlank { Pretty.shortUrl(item.url) }, sans(14.sp, FontWeight.SemiBold, 18.sp), c.textHi, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val (t, k) = typeTag(item)
            Tag(t, k)
            if (item.formatLabel.isNotBlank()) Tag(item.formatLabel, TagKind.Neutral)
        }
    }
}

@Composable
private fun ActiveCard(item: DownloadEntity, vm: QueueViewModel) {
    val c = Haul.colors
    val st = tapeState(item)
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MediaThumb(item, 96.dp, 54.dp)
            TitleAndTags(item, Modifier.weight(1f))
        }
        TapeProgress(item.progress, st)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val color = when (item.status) {
                Status.PAUSED -> c.textMid
                Status.PROCESSING -> c.signal
                else -> c.textHi
            }
            T(statusLine(item, withBytes = true, withLeft = false), mono(11.sp, tracking = 0.04f), color, Modifier.weight(1f), maxLines = 1)
            val paused = item.status == Status.PAUSED
            SmallSquareButton(
                if (paused) HaulIcon.Play else HaulIcon.Pause,
                if (paused) "Resume" else "Pause",
                c.textHi,
                filled = paused,
            ) { if (paused) vm.resume(item.id) else vm.pause(item.id) }
            SmallSquareButton(HaulIcon.Close, "Cancel", c.textMid) { vm.cancel(item.id) }
        }
    }
    Divider()
}

@Composable
private fun SmallSquareButton(icon: HaulIcon, label: String, tint: Color, filled: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(44.dp, 36.dp)
            .clip(shape)
            .background(Haul.colors.surface2, shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { HIcon(icon, tint, size = 18.dp, filled = filled, stroke = if (filled) 0f else 2f) }
}

@Composable
private fun CompactActiveRow(item: DownloadEntity) {
    val c = Haul.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MediaThumb(item, 54.dp, 54.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T(item.title.ifBlank { Pretty.shortUrl(item.url) }, sans(14.sp, FontWeight.SemiBold), c.textHi, maxLines = 1)
            TapeProgress(item.progress, tapeState(item), ticks = 36, height = 10.dp)
            T(
                statusLine(item, withLeft = false), mono(11.sp, tracking = 0.04f),
                if (item.status == Status.PAUSED) c.textMid else c.textHi, maxLines = 1,
            )
        }
    }
}

@Composable
private fun FailedCard(item: DownloadEntity, vm: QueueViewModel, snack: SnackState, last: Boolean) {
    val c = Haul.colors
    val ctx = LocalContext.current
    val pct = (item.failedAtProgress * 100).toInt()
    val card = Errors.card(item.errorKind ?: ErrorKind.UNKNOWN, pct)
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MediaThumb(item, 96.dp, 54.dp)
            TitleAndTags(item, Modifier.weight(1f))
        }
        TapeProgress(item.failedAtProgress, TapeState.Failed)
        val kept = if (item.downloadedBytes > 0 && item.totalBytes > 0) " · ${Pretty.bytesPair(item.downloadedBytes, item.totalBytes)} KEPT" else ""
        T("FAILED AT $pct%$kept", mono(11.sp, tracking = 0.04f), c.err)
        ErrorCard(card.title, card.fix) {
            val updating = vm.updatingFor == item.id
            if (card.suggestUpdate) {
                HButton(
                    if (updating) "Updating engine…" else "Update engine and retry",
                    { vm.updateEngineAndRetry(item.id) { snack.show(it) } },
                    Modifier.fillMaxWidth(),
                    height = 44.dp,
                    icon = HaulIcon.Retry,
                    enabled = !updating,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HButton(
                    if (card.suggestUpdate) "Retry only" else "Retry",
                    { vm.retry(item.id) },
                    Modifier.weight(1f),
                    kind = ButtonKind.Ghost,
                    height = 44.dp,
                    textColor = c.textHi,
                    border = c.errLine,
                    enabled = !updating,
                )
                HButton(
                    "Copy log",
                    {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("Haul log", item.errorLog.orEmpty().ifBlank { card.title }))
                        snack.show("Log copied.")
                    },
                    Modifier.weight(1f),
                    kind = ButtonKind.Ghost,
                    height = 44.dp,
                    textColor = c.textHi,
                    border = c.errLine,
                )
            }
            val log = item.errorLog.orEmpty()
            if (log.isNotBlank()) {
                val open = item.id !in vm.collapsedDetails
                TechnicalDetails(Errors.technical(log), open) {
                    if (open) vm.collapsedDetails.add(item.id) else vm.collapsedDetails.remove(item.id)
                }
            }
            TextAction("Remove from queue", { vm.cancel(item.id) }, color = c.errBody, height = 36.dp)
        }
    }
    if (!last) Divider()
}

@Composable
private fun WaitingRow(item: DownloadEntity, isFirst: Boolean, onMoveToFront: () -> Unit) {
    val c = Haul.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MediaThumb(item, 54.dp, 54.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T(item.title.ifBlank { Pretty.shortUrl(item.url) }, sans(14.sp, FontWeight.SemiBold), c.textHi, maxLines = 1)
            T(statusLine(item), mono(11.sp), c.textLow, maxLines = 1)
        }
        if (!isFirst) IconButton44(HaulIcon.Reorder, "Move to front", onMoveToFront, color = c.textLow, iconSize = 20.dp)
    }
    Divider()
}

@Composable
private fun DoneRow(item: DownloadEntity, last: Boolean, onOpen: () -> Unit) {
    val c = Haul.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Thumb(item.thumbnailUrl, 54.dp, 54.dp, overlay = {
            Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                HIcon(HaulIcon.Check, c.ok, size = 22.dp, stroke = 2f)
            }
        })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T(item.title.ifBlank { item.fileName.orEmpty() }, sans(14.sp, FontWeight.SemiBold), c.textHi, maxLines = 1)
            T(statusLine(item), mono(11.sp), c.ok, maxLines = 1)
        }
        TextAction("Open", onOpen)
    }
    if (!last) Divider()
}

@Composable
private fun EmptyQueue(onGrab: () -> Unit) {
    val c = Haul.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        T("Nothing in the queue.", sans(15.sp, FontWeight.SemiBold), c.textHi)
        T("Downloads you start show up here.", sans(13.sp), c.textLow, Modifier.padding(top = 4.dp, bottom = 16.dp))
        HButton("Grab a link", onGrab, kind = ButtonKind.Secondary, height = 44.dp, icon = HaulIcon.Link)
    }
}
