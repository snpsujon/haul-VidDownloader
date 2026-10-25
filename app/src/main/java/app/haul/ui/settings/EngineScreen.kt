package app.haul.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.data.Channel
import app.haul.ui.components.ButtonKind
import app.haul.ui.components.Divider
import app.haul.ui.components.HButton
import app.haul.ui.components.HSwitch
import app.haul.ui.components.HaulDialog
import app.haul.ui.components.SectionLabel
import app.haul.ui.components.Segmented
import app.haul.ui.components.ShapeMd
import app.haul.ui.components.SnackState
import app.haul.ui.components.T
import app.haul.ui.components.TitleBar
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.util.Pretty

/** Screen 20. */
@Composable
fun EngineScreen(vm: SettingsViewModel, snack: SnackState, onBack: () -> Unit) {
    val c = Haul.colors
    val s by vm.settings.collectAsState()
    LaunchedEffect(Unit) { vm.loadEngine() }
    var confirmRestore by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TitleBar("Download engine", onBack)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)) {
                Column(
                    Modifier.fillMaxWidth().clip(ShapeMd).background(c.surface1, ShapeMd).border(1.dp, c.divider, ShapeMd).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    T("YT-DLP", mono(11.sp, tracking = 0.08f), c.textLow)
                    T(vm.engineVersion ?: "…", mono(30.sp, FontWeight.SemiBold, tracking = -0.01f), c.textHi)
                    val failed = vm.engineNote == "failed"
                    val dot = when {
                        vm.engineChecking -> c.info
                        failed -> c.err
                        s.ytdlpLastCheck > 0 -> c.ok
                        else -> c.textLow
                    }
                    val text = when {
                        vm.engineChecking -> "Checking GitHub for a newer version…"
                        failed -> "Couldn't check · ${Pretty.relativeCheck(s.ytdlpLastCheck)}"
                        s.ytdlpLastCheck > 0 -> "Up to date · ${Pretty.relativeCheck(s.ytdlpLastCheck)}"
                        else -> "Bundled with the app · not checked yet"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
                        T(text, sans(14.sp), dot)
                    }
                    HButton(
                        if (vm.engineChecking) "Checking…" else "Check for update now",
                        { vm.checkEngine { snack.show(it) } },
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        kind = ButtonKind.Secondary, height = 44.dp, icon = HaulIcon.Retry, iconStroke = 1.75f,
                        enabled = !vm.engineChecking,
                    )
                }
                SectionLabel("UPDATE CHANNEL", Modifier.padding(top = 24.dp, bottom = 8.dp))
                Segmented(Channel.entries.map { it.label }, Channel.entries.indexOf(s.channel), { vm.setChannel(Channel.entries[it]) })
                T(
                    "Stable is tested releases. Nightly gets site fixes days sooner but can break.",
                    sans(13.sp, lineHeight = 18.sp), c.textLow, Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .clip(ShapeMd)
                        .background(c.surface1, ShapeMd)
                        .clickable(role = Role.Switch) { vm.setAutoUpdate(!s.ytdlpAutoUpdate) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        T("Update automatically", sans(15.sp, FontWeight.Medium), c.textHi)
                        T("At most once a day, on Wi-Fi", sans(13.sp), c.textLow)
                    }
                    HSwitch(s.ytdlpAutoUpdate)
                }
                SectionLabel("BUNDLED PARTS", Modifier.padding(top = 24.dp, bottom = 8.dp))
                Divider()
                val parts = vm.parts.ifEmpty { mapOf("FFmpeg" to "…", "Python runtime" to "…", "JavaScript runtime" to "…") }
                parts.forEach { (k, v) -> PartRow(k, v) }
                Spacer(Modifier.height(28.dp))
                val bundled = s.ytdlpBundledVersion
                HButton(
                    if (bundled != null) "Restore bundled version ($bundled)" else "Restore bundled version",
                    { confirmRestore = true },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.Ghost, height = 48.dp, textColor = c.err, enabled = !vm.engineChecking,
                )
                T(
                    "Use this if downloads broke after an update.",
                    sans(12.sp, lineHeight = 17.sp), c.textLow, Modifier.fillMaxWidth().padding(top = 4.dp), align = TextAlign.Center,
                )
            }
        }
        HaulDialog(confirmRestore, { confirmRestore = false }) {
            T("Restore the bundled yt-dlp?", Haul.type.headline, c.textHi)
            Spacer(Modifier.height(8.dp))
            T(
                "Haul goes back to the version that came with the app. Automatic updates may bring it forward again; turn them off to stay on it.",
                sans(14.sp, lineHeight = 20.sp), c.textMid,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HButton("Cancel", { confirmRestore = false }, Modifier.weight(1f), kind = ButtonKind.Secondary, height = 44.dp)
                HButton(
                    "Restore", { confirmRestore = false; vm.restoreBundled { snack.show(it) } },
                    Modifier.weight(1f), kind = ButtonKind.Destructive, height = 44.dp,
                )
            }
        }
    }
}

@Composable
private fun PartRow(name: String, value: String) {
    val c = Haul.colors
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            T(name, sans(14.sp), c.textHi, Modifier.weight(1f))
            T(value, mono(12.sp), c.textMid, maxLines = 1)
        }
        Divider()
    }
}
