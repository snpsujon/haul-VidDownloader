package app.haul.ui.grab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.data.AudioFormat
import app.haul.data.MediaType
import app.haul.data.SubtitleMode
import app.haul.engine.MediaInfo
import app.haul.ui.components.ChoiceRow
import app.haul.ui.components.Divider
import app.haul.ui.components.FilterChip
import app.haul.ui.components.HButton
import app.haul.ui.components.HSwitch
import app.haul.ui.components.HaulSheet
import app.haul.ui.components.IconButton44
import app.haul.ui.components.SectionLabel
import app.haul.ui.components.Segmented
import app.haul.ui.components.ShapeMd
import app.haul.ui.components.T
import app.haul.ui.components.Thumb
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.util.Pretty

/** Screens 07 (Video) and 08 (Audio). */
@Composable
fun FormatSheet(
    vm: GrabViewModel,
    saveLabel: String,
    onAllFormats: () -> Unit,
    onQueued: () -> Unit,
) {
    val info = vm.info
    HaulSheet(visible = vm.sheetOpen && info != null, onDismiss = { vm.sheetOpen = false }, fullHeight = true) {
        if (info == null) return@HaulSheet
        val c = Haul.colors
        Spacer(Modifier.height(16.dp))
        MediaHeader(info, onClose = { vm.sheetOpen = false })
        Spacer(Modifier.height(20.dp))
        val hasVideo = vm.videoChoices.isNotEmpty()
        if (hasVideo) {
            Segmented(
                listOf("Video", "Audio"),
                if (vm.tab == MediaType.VIDEO) 0 else 1,
                { vm.tab = if (it == 0) MediaType.VIDEO else MediaType.AUDIO },
                icons = listOf(HaulIcon.Video, HaulIcon.Audio),
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (vm.audioTracks.isNotEmpty()) AudioLanguage(vm)
            if (vm.tab == MediaType.VIDEO && hasVideo) VideoTab(vm, onAllFormats) else AudioTab(vm)
        }
        Spacer(Modifier.height(12.dp))
        if (vm.tab == MediaType.VIDEO && hasVideo) {
            Row(
                Modifier.padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HIcon(HaulIcon.Folder, c.textLow, size = 16.dp)
                T("Saves to", sans(12.sp), c.textLow)
                T(saveLabel, mono(12.sp), c.textMid)
            }
        }
        val label = if (vm.tab == MediaType.VIDEO && hasVideo) {
            vm.selectedVideo?.let { "Download · ${it.height}p ${it.container.uppercase()} · ${Pretty.bytes(it.sizeBytes)}" }
        } else {
            vm.selectedAudio?.let { a ->
                val name = if (a.format == AudioFormat.MP3) "MP3 ${vm.mp3Bitrate}" else a.format.label.uppercase()
                "Download · $name · ${Pretty.bytes(vm.audioSize(a))}"
            }
        }
        HButton(
            label?.replace(" · —", "") ?: "Download",
            { vm.download(onQueued) },
            Modifier.fillMaxWidth(),
            enabled = label != null,
            icon = HaulIcon.Queue,
        )
    }
}

@Composable
fun MediaHeader(info: MediaInfo, onClose: (() -> Unit)?) {
    val c = Haul.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Thumb(info.thumbnail, 120.dp, 68.dp, durationSec = info.durationSec, badgeInset = 5.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T(info.title, sans(15.sp, FontWeight.SemiBold, 20.sp), c.textHi, maxLines = 3)
            T(listOf(info.uploader, info.host).filter { it.isNotBlank() }.joinToString(" · "), sans(12.sp), c.textLow, maxLines = 1)
        }
        if (onClose != null) {
            IconButton44(HaulIcon.Close, "Close", onClose, Modifier.offset(x = 12.dp, y = (-10).dp), color = c.textMid, iconSize = 20.dp)
        }
    }
}

/** Shown when the video has more than one spoken language. Applies to both tabs. */
@Composable
private fun AudioLanguage(vm: GrabViewModel) {
    Spacer(Modifier.height(20.dp))
    SectionLabel("Audio language")
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.audioTracks.forEach { t ->
            FilterChip(
                if (t.original) "${t.label} · Original" else t.label,
                vm.audioLanguage == t.language,
                { vm.setLanguage(t.language) },
                hPad = 12.dp, height = 32.dp,
            )
        }
    }
}

@Composable
private fun ColumnScope.VideoTab(vm: GrabViewModel, onAllFormats: () -> Unit) {
    val c = Haul.colors
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Quality", Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("mp4", "webm").forEach { ct ->
                FilterChip(
                    ct.uppercase(), vm.container == ct, { vm.setContainerTo(ct) },
                    monoLabel = true, hPad = 10.dp, height = 30.dp,
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        vm.videoChoices.forEach { ch ->
            ChoiceRow(
                title = ch.label,
                spec = ch.spec,
                size = Pretty.bytes(ch.sizeBytes),
                selected = ch.height == vm.selectedHeight,
                onClick = { vm.selectedHeight = ch.height },
                badge = if (ch.height == vm.recommendedHeight) "Best for phones" else null,
            )
        }
    }
    if (vm.subtitleTracks.isNotEmpty()) Subtitles(vm)
    val n = vm.info?.formats?.size ?: 0
    Spacer(Modifier.height(6.dp))
    Divider()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onAllFormats)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T("All $n formats", sans(14.sp), c.textHi)
        T(" · codecs, frame rates", sans(14.sp), c.textLow, Modifier.weight(1f))
        HIcon(HaulIcon.ChevRight, c.textLow, size = 18.dp)
    }
}

@Composable
private fun Subtitles(vm: GrabViewModel) {
    val c = Haul.colors
    Spacer(Modifier.height(18.dp))
    SectionLabel("Subtitles")
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip("Off", vm.subtitleCode == null, { vm.subtitleCode = null }, hPad = 12.dp, height = 32.dp)
        vm.subtitleTracks.forEach { t ->
            FilterChip(
                if (t.auto) "${t.name} · Auto" else t.name,
                vm.subtitleCode == t.code,
                { vm.subtitleCode = t.code },
                hPad = 12.dp, height = 32.dp,
            )
        }
    }
    if (vm.subtitleCode != null) {
        Spacer(Modifier.height(10.dp))
        Segmented(
            listOf("Embed", "Save .srt"),
            if (vm.subtitleMode == SubtitleMode.EMBED) 0 else 1,
            { vm.subtitleMode = if (it == 0) SubtitleMode.EMBED else SubtitleMode.SRT },
            compact = true,
            trackColor = c.surface1,
        )
    }
}

@Composable
private fun ColumnScope.AudioTab(vm: GrabViewModel) {
    val c = Haul.colors
    Spacer(Modifier.height(20.dp))
    SectionLabel("Format")
    Spacer(Modifier.height(10.dp))
    val bitrateRow: @Composable ColumnScope.() -> Unit = {
        val rates = listOf(128, 192, 320)
        Segmented(
            rates.map { if (it == vm.mp3Bitrate) "$it KBPS" else "$it" },
            rates.indexOf(vm.mp3Bitrate).coerceAtLeast(0),
            { vm.setBitrate(rates[it]) },
            Modifier.padding(start = 34.dp),
            compact = true,
            monoLabels = true,
            trackColor = c.surface1,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        vm.audioChoices.forEach { ch ->
            ChoiceRow(
                title = ch.format.label,
                spec = ch.spec,
                size = Pretty.bytes(vm.audioSize(ch)),
                selected = ch.format == vm.audioFormat,
                onClick = { vm.audioFormat = ch.format },
                badge = ch.badge,
                badgeOutlined = false,
                extra = if (ch.format == AudioFormat.MP3) bitrateRow else null,
            )
        }
    }
    Spacer(Modifier.height(18.dp))
    SectionLabel("Tags")
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().clip(ShapeMd).background(c.surface2, ShapeMd)) {
        TagField("Title", vm.metaTitle) { vm.metaTitle = it }
        Divider(color = c.inputDivider)
        TagField("Artist", vm.metaArtist) { vm.metaArtist = it }
        if (vm.audioFormat != AudioFormat.OPUS) {
            Divider(color = c.inputDivider)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Switch) { vm.embedCover = !vm.embedCover }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                T("Embed cover art", sans(15.sp), c.textHi, Modifier.weight(1f))
                HSwitch(vm.embedCover)
            }
        }
    }
}

@Composable
private fun TagField(label: String, value: String, onChange: (String) -> Unit) {
    val c = Haul.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        T(label, sans(12.sp), c.textLow)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = sans(15.sp).copy(color = c.textHi),
            cursorBrush = SolidColor(c.signal),
            modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
        )
    }
}
