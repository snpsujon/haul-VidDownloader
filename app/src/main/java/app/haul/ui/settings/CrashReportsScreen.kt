package app.haul.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.ui.components.ButtonKind
import app.haul.ui.components.Divider
import app.haul.ui.components.ErrorCard
import app.haul.ui.components.HButton
import app.haul.ui.components.ShapeMd
import app.haul.ui.components.SnackState
import app.haul.ui.components.T
import app.haul.ui.components.TextAction
import app.haul.ui.components.TitleBar
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.util.CrashReports
import java.io.File

/** Shown once on the launch after a crash. */
@Composable
fun CrashNotice(file: File, snack: SnackState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Haul.colors
    val ctx = LocalContext.current
    val text = remember(file) { runCatching { file.readText() }.getOrDefault("") }
    ErrorCard("Haul closed unexpectedly.", "A crash report was saved on this phone. Sharing it helps get the problem fixed.", modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(
                "Copy report", { ctx.copyReport(text); snack.show("Report copied.") }, Modifier.weight(1f),
                kind = ButtonKind.Ghost, height = 44.dp, textColor = c.textHi, border = c.errLine,
            )
            HButton(
                "Share", { ctx.shareReport(text) }, Modifier.weight(1f),
                kind = ButtonKind.Ghost, height = 44.dp, textColor = c.textHi, border = c.errLine,
            )
        }
        TextAction("Dismiss", onDismiss, color = c.errBody, height = 36.dp)
    }
}

/** Settings → About → Crash reports. */
@Composable
fun CrashReportsScreen(snack: SnackState, onBack: () -> Unit) {
    val c = Haul.colors
    val ctx = LocalContext.current
    var reports by remember { mutableStateOf(CrashReports.reports(ctx)) }
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        TitleBar("Crash reports", onBack) {
            if (reports.isNotEmpty()) {
                TextAction("Delete all", { CrashReports.deleteAll(ctx); reports = emptyList() })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)) {
            T(
                "Reports stay on this phone. Nothing is sent unless you share it.",
                sans(14.sp, lineHeight = 20.sp), c.textMid, Modifier.padding(bottom = 16.dp),
            )
            if (reports.isEmpty()) {
                T("No crashes recorded.", sans(14.sp), c.textLow)
                return@Column
            }
            Column(Modifier.fillMaxWidth().clip(ShapeMd).background(c.surface1, ShapeMd)) {
                reports.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    val text = remember(f) { runCatching { f.readText() }.getOrDefault("") }
                    val expanded = open == f.name
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { open = if (expanded) null else f.name }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            T(CrashReports.summary(text).ifBlank { "Crash" }, sans(15.sp), c.textHi, maxLines = 2)
                            T(CrashReports.label(f), mono(11.sp), c.textLow)
                        }
                        HIcon(if (expanded) HaulIcon.ChevDown else HaulIcon.ChevRight, c.textLow, size = 18.dp)
                    }
                    if (expanded) {
                        T(
                            text, mono(11.sp, lineHeight = 16.sp), c.textMid,
                            Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp)).background(c.ground).padding(12.dp),
                            maxLines = 40,
                        )
                        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextAction("Copy", { ctx.copyReport(text); snack.show("Report copied.") })
                            TextAction("Share", { ctx.shareReport(text) })
                            TextAction("Delete", {
                                CrashReports.delete(ctx, f)
                                reports = CrashReports.reports(ctx)
                            }, color = c.textMid)
                        }
                    }
                }
            }
        }
    }
}

private fun Context.copyReport(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Haul crash report", text))
}

private fun Context.shareReport(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Haul crash report")
        .putExtra(Intent.EXTRA_TEXT, text.take(100_000))
    runCatching { startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
