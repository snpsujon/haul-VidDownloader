package app.haul.ui.grab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.data.AppSettings
import app.haul.ui.components.CheckBoxMark
import app.haul.ui.components.Divider
import app.haul.ui.components.HButton
import app.haul.ui.components.IconButton44
import app.haul.ui.components.Segmented
import app.haul.ui.components.T
import app.haul.ui.components.Thumb
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.util.Pretty

/** Screen 10: pick which playlist entries to download, all with one preset. */
@Composable
fun PlaylistScreen(vm: GrabViewModel, settings: AppSettings, onBack: () -> Unit, onQueued: () -> Unit) {
    val c = Haul.colors
    val info = vm.info?.takeIf { it.isPlaylist } ?: run { onBack(); return }
    val n = info.entries.size
    val picked = vm.playlistSelected.size
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton44(HaulIcon.Back, "Back", onBack)
            T("PLAYLIST · $n ITEMS", mono(11.sp, tracking = 0.08f), c.textLow)
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T(info.title, Haul.type.title.copy(lineHeight = 28.sp), c.textHi, maxLines = 3)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (info.uploader.isNotBlank()) T("${info.uploader} · ", sans(13.sp), c.textLow)
                T(Pretty.longDuration(info.durationSec), mono(12.sp), c.textLow)
            }
        }
        Spacer(Modifier.height(16.dp))
        Divider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Checkbox) { vm.togglePlaylistAll() }
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CheckBoxMark(checked = picked == n && n > 0, partial = picked in 1 until n)
            T("Select all", sans(14.sp, FontWeight.SemiBold), c.textHi, Modifier.weight(1f))
            T("$picked OF $n", mono(12.sp), c.textMid)
        }
        Divider()
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(info.entries) { i, e ->
                val on = i in vm.playlistSelected
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clickable(role = Role.Checkbox) { vm.togglePlaylistItem(i) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CheckBoxMark(on)
                        Thumb(e.thumbnail, 72.dp, 40.dp, radius = 6.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            T(e.title, sans(14.sp, FontWeight.Medium, 18.sp), if (on) c.textHi else c.textLow, maxLines = 1)
                            val num = (i + 1).toString().padStart(2, '0')
                            val d = Pretty.duration(e.durationSec)
                            T(if (d.isEmpty()) num else "$num · $d", mono(11.sp), c.textLow)
                        }
                    }
                    Divider(color = c.rowDivider)
                }
            }
        }
        Divider()
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.surface1)
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = maxOf(28.dp, navInset + 12.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Segmented(
                listOf("Video · ${settings.videoHeight}p", "Audio · ${settings.audioPresetShort}"),
                if (vm.playlistAudio) 1 else 0,
                { vm.playlistAudio = it == 1 },
                compact = true,
            )
            val est = vm.playlistEstimate(settings)
            val items = if (picked == 1) "1 item" else "$picked items"
            HButton(
                if (est > 0) "Download $items · ~${Pretty.bytes(est)}" else "Download $items",
                { vm.downloadPlaylist(onQueued) },
                Modifier.fillMaxWidth(),
                enabled = picked > 0,
                icon = HaulIcon.Queue,
                iconStroke = 2f,
            )
        }
    }
}
