package app.haul.ui.grab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import app.haul.engine.Fmt
import app.haul.engine.FormatOptions
import app.haul.ui.components.Divider
import app.haul.ui.components.FilterChip
import app.haul.ui.components.HButton
import app.haul.ui.components.T
import app.haul.ui.components.TagKind
import app.haul.ui.components.TitleBar
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.Haul
import app.haul.util.Pretty

/** Screen 09: every format yt-dlp found, for advanced users. */
@Composable
fun AllFormatsScreen(vm: GrabViewModel, onBack: () -> Unit, onQueued: () -> Unit) {
    val c = Haul.colors
    val info = vm.info ?: run { onBack(); return }
    LaunchedEffect(Unit) { vm.initAllFormats() }
    val all = info.formats.sortedWith(
        compareBy<Fmt> { if (it.isVideoOnly) 0 else if (it.isCombined) 1 else 2 }
            .thenByDescending { it.height }.thenByDescending { it.tbr },
    )
    val filtered = when (vm.allFilter) {
        1 -> all.filter { it.isVideoOnly }
        2 -> all.filter { it.isAudioOnly }
        3 -> all.filter { it.isCombined }
        else -> all
    }
    val multiLanguage = FormatOptions.audioTracks(info).isNotEmpty()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize()) {
        TitleBar("All formats", onBack) {
            T("${info.formats.size} FOUND", mono(11.sp), c.textLow)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("All", "Video only", "Audio only", "Combined").forEachIndexed { i, l ->
                FilterChip(l, vm.allFilter == i, { vm.allFilter = i })
            }
        }
        Divider()
        HeaderRow()
        Divider()
        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.id }) { f ->
                val sel = f.id == vm.pickedVideo || f.id == vm.pickedAudio
                FormatRow(f, info.durationSec, sel, multiLanguage) { vm.pick(f) }
            }
        }
        val req = vm.customRequestPreview()
        Divider()
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.surface1)
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = maxOf(28.dp, navInset + 12.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val v = info.formats.firstOrNull { it.id == vm.pickedVideo }
            val a = info.formats.firstOrNull { it.id == vm.pickedAudio }
            Text(
                buildAnnotatedString {
                    when {
                        v != null && !v.isCombined && a != null -> {
                            withStyle(SpanStyle(fontFamily = app.haul.ui.theme.JetBrainsMono, color = c.signal)) { append(v.id) }
                            append(" video + ")
                            withStyle(SpanStyle(fontFamily = app.haul.ui.theme.JetBrainsMono, color = c.tape)) { append(a.id) }
                            append(" audio, merged to ${FormatOptions.mergeContainer(v, a).uppercase()}")
                        }
                        v != null -> {
                            withStyle(SpanStyle(fontFamily = app.haul.ui.theme.JetBrainsMono, color = c.signal)) { append(v.id) }
                            append(if (v.isCombined) " video with audio" else " video only, no audio")
                        }
                        a != null -> {
                            withStyle(SpanStyle(fontFamily = app.haul.ui.theme.JetBrainsMono, color = c.tape)) { append(a.id) }
                            append(" audio only")
                        }
                        else -> append("Pick a video format, an audio format, or both.")
                    }
                },
                style = sans(13.sp),
                color = c.textMid,
            )
            HButton(
                if (req != null) "Download · ${Pretty.bytes(req.estimatedBytes)}".replace(" · —", "") else "Download",
                { vm.downloadCustom(onQueued) },
                Modifier.fillMaxWidth(),
                enabled = req != null,
            )
        }
    }
}

@Composable
private fun HeaderRow() {
    val c = Haul.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val st = mono(10.sp, tracking = 0.08f)
        T("ID", st, c.textLow, Modifier.width(44.dp))
        T("QUALITY", st, c.textLow, Modifier.width(72.dp))
        T("CODEC · EXT · FPS", st, c.textLow, Modifier.weight(1f))
        T("SIZE", st, c.textLow, Modifier.width(64.dp), align = TextAlign.End)
    }
}

@Composable
private fun FormatRow(f: Fmt, duration: Int, selected: Boolean, showLanguage: Boolean, onClick: () -> Unit) {
    val c = Haul.colors
    val (kind, tag) = when {
        f.isCombined -> TagKind.Neutral to "A+V"
        f.isAudioOnly -> TagKind.Audio to "AUD"
        else -> TagKind.Video to "VID"
    }
    val quality = when {
        f.hasVideo && f.height > 0 -> "${f.height}p"
        f.isAudioOnly -> FormatOptions.kbps(f).takeIf { it > 0 }?.let { "${it}k" } ?: "—"
        else -> f.note.ifBlank { "—" }
    }
    val codec = when {
        f.isCombined -> "${f.vcodec.substringBefore('.')}+${f.acodec.substringBefore('.')}"
        f.isAudioOnly -> f.acodec.substringBefore('.')
        else -> f.vcodec.substringBefore('.')
    }
    val language = f.language.uppercase().takeIf { showLanguage && f.hasAudio && it.isNotEmpty() }
    val spec = listOfNotNull(language, codec.takeIf { it.isNotBlank() && it != "unknown" }, f.ext, f.fps.takeIf { it > 0 && f.hasVideo }?.toString())
        .joinToString(" · ")
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(if (selected) c.signalTint else Color.Transparent)
                .clickable(role = Role.Checkbox, onClick = onClick)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            T(f.id, mono(12.sp, FontWeight.SemiBold), if (selected) c.signal else c.textLow, Modifier.width(44.dp), maxLines = 1)
            T(quality, mono(12.sp), c.textHi, Modifier.width(72.dp), maxLines = 1)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallKindTag(tag, kind)
                T(spec, mono(12.sp), c.textMid, maxLines = 1)
            }
            T(Pretty.bytes(f.size(duration), gbDecimals = 2), mono(12.sp), c.textHi, Modifier.width(64.dp), align = TextAlign.End, maxLines = 1)
        }
        Divider(color = c.rowDivider)
    }
}

@Composable
private fun SmallKindTag(text: String, kind: TagKind) {
    val c = Haul.colors
    val (bg, fg) = when (kind) {
        TagKind.Video -> c.signalTint to c.signal
        TagKind.Audio -> c.tapeTint to c.tape
        else -> c.surface2 to c.textMid
    }
    androidx.compose.foundation.layout.Box(
        Modifier
            .heightIn(min = 18.dp)
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, mono(9.sp, FontWeight.SemiBold), fg) }
}
