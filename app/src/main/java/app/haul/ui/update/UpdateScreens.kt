package app.haul.ui.update

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.haul.BuildConfig
import app.haul.HaulApp
import app.haul.update.AppUpdater
import app.haul.update.UpdateInfo
import app.haul.ui.components.Bullet
import app.haul.ui.components.ButtonKind
import app.haul.ui.components.Divider
import app.haul.ui.components.ErrorCard
import app.haul.ui.components.HButton
import app.haul.ui.components.HaulSheet
import app.haul.ui.components.SectionLabel
import app.haul.ui.components.T
import app.haul.ui.components.TapeProgress
import app.haul.ui.components.TapeState
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.settings.openUrl
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.util.Pretty
import kotlinx.coroutines.launch

/** Screen 21: offered update, as a sheet over the current screen. */
@Composable
fun UpdatePromptSheet(info: UpdateInfo?, visible: Boolean, onUpdate: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit) {
    val c = Haul.colors
    HaulSheet(visible && info != null, onLater) {
        if (info == null) return@HaulSheet
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(c.signal), contentAlignment = Alignment.Center) {
                    HIcon(HaulIcon.AppMark, c.onSignal, size = 30.dp, stroke = 6f, squareCaps = true)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T("APP UPDATE", mono(11.sp, tracking = 0.08f), c.textLow)
                    T("Version ${info.versionName} is ready", Haul.type.title, c.textHi)
                }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("${BuildConfig.VERSION_NAME} → ${info.versionName}")
                if (info.sizeBytes > 0) Chip(Pretty.bytes(info.sizeBytes))
                if (info.abi.isNotBlank()) Chip(info.abi.uppercase())
            }
            if (info.changelog.isNotEmpty()) {
                SectionLabel("WHAT'S NEW", Modifier.padding(top = 22.dp, bottom = 10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    info.changelog.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Bullet(c.signal)
                            T(line, sans(15.sp, lineHeight = 21.sp), c.textHi)
                        }
                    }
                }
            }
            Row(
                Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HIcon(HaulIcon.Shield, c.ok, size = 20.dp)
                T("Downloaded from your site and checked against its SHA-256 before Android installs it.", sans(13.sp, lineHeight = 18.sp), c.textMid)
            }
            HButton("Update now", onUpdate, Modifier.padding(top = 20.dp).fillMaxWidth())
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HButton("Later", onLater, Modifier.weight(1f), kind = ButtonKind.Secondary, height = 48.dp, fontSize = 14.sp, radius = 12.dp)
                HButton("Skip this version", onSkip, Modifier.weight(1f), kind = ButtonKind.Ghost, height = 48.dp, textColor = c.textMid, fontSize = 14.sp, radius = 12.dp)
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    val c = Haul.colors
    Box(
        Modifier.height(22.dp).clip(RoundedCornerShape(6.dp)).background(c.surface2).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, mono(11.sp, tracking = 0.04f), c.textMid, maxLines = 1) }
}

/**
 * Screen 22: the four install steps. [required] blocks the app (the installed version is below
 * minSupported); otherwise the screen can be left and the update finished later.
 */
@Composable
fun InstallScreen(info: UpdateInfo, required: Boolean, onLater: () -> Unit) {
    val c = Haul.colors
    val ctx = LocalContext.current
    val updater = HaulApp.graph(ctx).updater
    val state by updater.install.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(info.versionCode) {
        if (state is AppUpdater.Install.Idle) updater.download(info)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updater.recheckPermission() }

    // 1 download, 2 verify, 3 permission, 4 install
    val step = when (state) {
        AppUpdater.Install.Idle, is AppUpdater.Install.Downloading -> 1
        AppUpdater.Install.Verifying -> 2
        AppUpdater.Install.NeedsPermission -> 3
        AppUpdater.Install.ReadyToInstall, AppUpdater.Install.Installing -> 4
        is AppUpdater.Install.Failed -> 0
    }
    // Decided once, so the step numbers don't shift after the permission is granted.
    val needsPermissionStep = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !updater.canInstall() } || step == 3

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 28.dp)) {
        T(if (required) "UPDATE REQUIRED" else "APP UPDATE", mono(11.sp, tracking = 0.08f), c.signal)
        Spacer(Modifier.height(14.dp))
        T(if (required) "This version is no longer supported." else "Installing version ${info.versionName}.", Haul.type.display, c.textHi)
        Spacer(Modifier.height(12.dp))
        T(
            if (required) "Most sites stopped working with ${BuildConfig.VERSION_NAME}. Update to keep downloading. Your Library and files stay exactly where they are."
            else "Your Library and files stay exactly where they are.",
            sans(15.sp, lineHeight = 22.sp), c.textMid,
        )
        Row(
            Modifier.padding(top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface1)
                .border(1.dp, c.divider, RoundedCornerShape(12.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T("Installed", sans(12.sp), c.textLow)
                T(BuildConfig.VERSION_NAME, mono(18.sp, FontWeight.SemiBold), c.textMid)
            }
            HIcon(HaulIcon.ArrowRight, c.textLow, size = 20.dp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T("Latest", sans(12.sp), c.textLow)
                T(info.versionName, mono(18.sp, FontWeight.SemiBold), c.textHi)
            }
        }
        Spacer(Modifier.height(24.dp))
        Divider()
        val dl = state as? AppUpdater.Install.Downloading
        StepRow(1, "Downloading update", step, trailing = dl?.let { if (it.total > 0) Pretty.bytesPair(it.done, it.total) else Pretty.bytes(it.done) }) {
            if (step == 1) {
                val p = if (dl != null && dl.total > 0) dl.done.toFloat() / dl.total else 0f
                TapeProgress(p, TapeState.Downloading, Modifier.padding(start = 38.dp), ticks = 40, height = 10.dp)
            }
        }
        StepRow(2, "Check the file's SHA-256", step)
        if (needsPermissionStep) {
            StepRow(3, "Allow installs from Haul", step) {
                Text(
                    buildAnnotatedString {
                        append("Android asks once. Turn on ")
                        withStyle(SpanStyle(color = c.textHi)) { append("Allow from this source") }
                        append(", then come back.")
                    },
                    style = sans(13.sp, lineHeight = 18.sp),
                    color = if (step == 3) c.textMid else c.textLow,
                    modifier = Modifier.padding(start = 38.dp),
                )
            }
        }
        StepRow(if (needsPermissionStep) 4 else 3, "Install", step, realStep = 4)

        (state as? AppUpdater.Install.Failed)?.let { f ->
            Spacer(Modifier.height(20.dp))
            ErrorCard("The update didn't finish.", f.message) {
                HButton("Try again", { updater.reset(); scope.launch { updater.download(info) } }, Modifier.fillMaxWidth(), height = 44.dp, icon = HaulIcon.Retry)
            }
        }
        Spacer(Modifier.height(32.dp))
        when (state) {
            AppUpdater.Install.NeedsPermission ->
                HButton("Allow installs from Haul", { ctx.startActivity(updater.permissionIntent()) }, Modifier.fillMaxWidth())
            AppUpdater.Install.ReadyToInstall ->
                HButton("Install", { scope.launch { updater.install() } }, Modifier.fillMaxWidth())
            AppUpdater.Install.Installing ->
                HButton("Installing…", {}, Modifier.fillMaxWidth(), enabled = false)
            else -> HButton("Install when ready", {}, Modifier.fillMaxWidth(), enabled = false)
        }
        HButton(
            "Download from the website instead", { ctx.openUrl(BuildConfig.WEBSITE_URL) },
            Modifier.padding(top = 8.dp).fillMaxWidth(), kind = ButtonKind.Ghost, height = 44.dp, textColor = c.textMid,
        )
        if (!required) {
            HButton("Later", onLater, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost, height = 44.dp, textColor = c.textLow)
        }
    }
}

@Composable
private fun StepRow(
    n: Int,
    title: String,
    current: Int,
    trailing: String? = null,
    realStep: Int = n,
    extra: (@Composable () -> Unit)? = null,
) {
    val c = Haul.colors
    val active = current == realStep
    val done = current > realStep
    val fg = if (active || done) c.textHi else c.textLow
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(24.dp)) {
                if (done) HIcon(HaulIcon.Check, c.ok, size = 16.dp, stroke = 2.5f)
                else T(n.toString().padStart(2, '0'), mono(12.sp), if (active) c.signal else c.textLow)
            }
            T(title, sans(15.sp, if (active) FontWeight.SemiBold else FontWeight.Normal), fg, Modifier.weight(1f))
            if (trailing != null && active) T(trailing, mono(12.sp), c.textHi)
        }
        extra?.invoke()
    }
    Divider()
}
