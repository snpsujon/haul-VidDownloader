package app.haul.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.haul.ui.components.ButtonKind
import app.haul.ui.components.HButton
import app.haul.ui.components.IconButton44
import app.haul.ui.components.ShapeMd
import app.haul.ui.components.T
import app.haul.ui.components.TextAction
import app.haul.ui.components.TickSkyline
import app.haul.ui.components.Wordmark
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon

/** Screen 01. */
@Composable
fun OnboardingScreen(onNext: () -> Unit, onSkip: () -> Unit, onTerms: () -> Unit) {
    val c = Haul.colors
    FullHeightScroll {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("STEP 1 OF 2", mono(11.sp, tracking = 0.08f), c.textLow, Modifier.weight(1f))
            TextAction("Skip", onSkip, color = c.textMid)
        }
        Spacer(Modifier.height(48.dp))
        Wordmark(72.sp)
        Spacer(Modifier.height(20.dp))
        TickSkyline(ticks = 48, lit = 29, height = 28.dp)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("VIDEO", "AUDIO", "1,000+ SITES").forEach { T(it, mono(11.sp, tracking = 0.06f), c.textLow) }
        }
        Spacer(Modifier.height(48.dp))
        T("Save video and audio to your phone.", Haul.type.display, c.textHi)
        Spacer(Modifier.height(14.dp))
        T(
            "Paste or share a link, pick the quality, and keep it offline. Everything runs on this device.",
            sans(15.sp, lineHeight = 22.sp), c.textMid,
        )
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                "No account, no ads, no tracking",
                "Files saved where every app can open them",
                "Open source, powered by yt-dlp",
            ).forEach {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HIcon(HaulIcon.Check, c.ok, size = 18.dp, stroke = 2f)
                    T(it, sans(14.sp), c.textHi)
                }
            }
        }
        Spacer(Modifier.heightIn(min = 40.dp).weight(1f))
        HButton("Get started", onNext, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text(
            buildAnnotatedString {
                append("Only download content you own or have permission to save. ")
                withStyle(SpanStyle(color = c.signal)) { append("Terms of use") }
            },
            style = sans(12.sp, lineHeight = 17.sp),
            color = c.textLow,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onTerms).padding(vertical = 4.dp),
        )
    }
}

/** Screen 02. */
@Composable
fun PermissionsScreen(
    saveLabel: String,
    onBack: () -> Unit,
    onPickFolder: () -> Unit,
    onDone: () -> Unit,
) {
    val c = Haul.colors
    val ctx = LocalContext.current
    var notifOk by remember { mutableStateOf(notificationsAllowed(ctx)) }
    var batteryOk by remember { mutableStateOf(ignoringBattery(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notifOk = notificationsAllowed(ctx)
        batteryOk = ignoringBattery(ctx)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifOk = granted
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Denied twice: Android stops asking, so send people to the app's notification settings.
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
        }
    }

    FullHeightScroll {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton44(HaulIcon.Back, "Back", onBack, Modifier.offset(x = (-12).dp))
            Spacer(Modifier.weight(1f))
            T("STEP 2 OF 2", mono(11.sp, tracking = 0.08f), c.textLow)
        }
        Spacer(Modifier.height(28.dp))
        T("Two quick things.", Haul.type.display, c.textHi)
        Spacer(Modifier.height(12.dp))
        T("So downloads keep going when you leave the app, and you know where they end up.", sans(15.sp, lineHeight = 22.sp), c.textMid)
        Spacer(Modifier.height(32.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PermCard(HaulIcon.Bell, c.signal, "Notifications", null, "Shows progress, with pause and cancel, while the app is closed.") {
                if (notifOk) Allowed()
                else HButton(
                    "Allow",
                    { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    height = 40.dp,
                )
            }
            PermCard(HaulIcon.Folder, c.signal, "Save folder", saveLabel, "Visible to your gallery, music and file apps.") {
                HButton("Change", onPickFolder, kind = ButtonKind.Secondary, height = 40.dp)
            }
            PermCard(
                HaulIcon.Battery, if (batteryOk) c.ok else c.signal, "Run in background", null,
                "Stops some phones from pausing long downloads.", optional = true,
            ) {
                if (batteryOk) Allowed()
                else HButton("Allow", { requestIgnoreBattery(ctx) }, kind = ButtonKind.Secondary, height = 40.dp)
            }
        }
        Spacer(Modifier.height(20.dp))
        T("Permission to install app updates is asked for later, only when an update is ready.", sans(13.sp, lineHeight = 18.sp), c.textLow)
        Spacer(Modifier.heightIn(min = 40.dp).weight(1f))
        HButton("Start using Haul", onDone, Modifier.fillMaxWidth())
        HButton("Not now", onDone, Modifier.padding(top = 8.dp).fillMaxWidth(), kind = ButtonKind.Ghost, height = 44.dp, textColor = c.textMid)
    }
}

@Composable
private fun PermCard(
    icon: HaulIcon,
    tint: Color,
    title: String,
    monoLine: String?,
    body: String,
    optional: Boolean = false,
    action: @Composable () -> Unit,
) {
    val c = Haul.colors
    Row(
        Modifier.fillMaxWidth().clip(ShapeMd).background(c.surface1, ShapeMd).border(1.dp, c.divider, ShapeMd).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
            HIcon(icon, tint, size = 22.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T(title, sans(15.sp, FontWeight.SemiBold), c.textHi)
                if (optional) T("OPTIONAL", mono(10.sp, tracking = 0.04f), c.textLow)
            }
            if (monoLine != null) T(monoLine, mono(12.sp), c.textHi)
            T(body, sans(13.sp, lineHeight = 18.sp), c.textMid)
        }
        Box(Modifier.align(Alignment.CenterVertically).heightIn(min = 40.dp), contentAlignment = Alignment.Center) { action() }
    }
}

@Composable
private fun Allowed() {
    val c = Haul.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HIcon(HaulIcon.Check, c.ok, size = 18.dp, stroke = 2f)
        T("Allowed", sans(14.sp, FontWeight.SemiBold), c.ok)
    }
}

fun notificationsAllowed(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun ignoringBattery(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

@SuppressLint("BatteryLife")
private fun requestIgnoreBattery(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    }.onFailure {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

/** Scrolls on short screens; on tall ones a weighted spacer pushes the buttons to the bottom. */
@Composable
private fun FullHeightScroll(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
            content = content,
        )
    }
}
