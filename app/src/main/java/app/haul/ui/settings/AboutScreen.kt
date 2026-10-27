package app.haul.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.BuildConfig
import app.haul.ui.components.Divider
import app.haul.ui.components.ShapeMd
import app.haul.ui.components.T
import app.haul.ui.components.TitleBar
import app.haul.ui.components.Wordmark
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.util.CrashReports

/** Screen 23. */
@Composable
fun AboutScreen(vm: SettingsViewModel, onBack: () -> Unit, onLicences: () -> Unit, onCrashReports: () -> Unit) {
    val c = Haul.colors
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TitleBar("About", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Wordmark(56.sp, Modifier.weight(1f))
                T(
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nGPL-3.0",
                    mono(11.sp, tracking = 0.04f, lineHeight = 16.sp), c.textLow, align = TextAlign.End,
                )
            }
            Column(
                Modifier.padding(top = 24.dp).fillMaxWidth().clip(ShapeMd).background(c.surface1, ShapeMd).border(1.dp, c.divider, ShapeMd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                T("TERMS OF USE", mono(11.sp, tracking = 0.08f), c.textLow)
                T(
                    "This app is for downloading content you own, content in the public domain, or content you have permission to save. " +
                        "You are responsible for respecting copyright and each site's terms. This app is not affiliated with any of the sites it can download from.",
                    sans(14.sp, lineHeight = 21.sp), c.termsBody,
                )
            }
            Column(Modifier.padding(top = 20.dp).fillMaxWidth().clip(ShapeMd).background(c.surface1, ShapeMd)) {
                LinkRow("Open source licences", "YT-DLP · FFMPEG · +${LICENCES.size - 2}", HaulIcon.ChevRight, first = true, onClick = onLicences)
                LinkRow("Source code", null, HaulIcon.External) { ctx.openUrl(BuildConfig.SOURCE_URL) }
                LinkRow("Privacy policy", "Collects nothing", HaulIcon.External, monoHint = false) { ctx.openUrl(BuildConfig.PRIVACY_URL) }
                LinkRow("Copyright and takedown contact", null, HaulIcon.ChevRight) {
                    ctx.openUrl("mailto:${BuildConfig.TAKEDOWN_EMAIL}?subject=" + Uri.encode("Copyright or takedown request"))
                }
                val crashes = remember { CrashReports.reports(ctx).size }
                LinkRow("Crash reports", if (crashes > 0) "$crashes SAVED" else "NONE", HaulIcon.ChevRight, onClick = onCrashReports)
            }
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T("SIGNING CERTIFICATE · SHA-256", mono(11.sp, tracking = 0.08f), c.textLow)
                T(vm.signingFingerprint, mono(12.sp, lineHeight = 18.sp), c.textMid)
                T("Matches the fingerprint on the download page if this copy is genuine.", sans(12.sp, lineHeight = 17.sp), c.textLow)
            }
        }
    }
}

@Composable
private fun LinkRow(
    title: String,
    hint: String?,
    icon: HaulIcon,
    first: Boolean = false,
    monoHint: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Haul.colors
    if (!first) Divider()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        T(title, sans(15.sp), c.textHi, Modifier.weight(1f))
        if (hint != null) T(hint, if (monoHint) mono(11.sp) else sans(13.sp), c.textLow, maxLines = 1)
        HIcon(icon, c.textLow, size = if (icon == HaulIcon.External) 18.dp else 20.dp)
    }
}

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

data class Licence(val name: String, val licence: String, val url: String, val asset: String? = null)

val LICENCES = listOf(
    Licence("yt-dlp", "The Unlicense", "https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE"),
    Licence("FFmpeg", "LGPL-2.1 / GPL", "https://ffmpeg.org/legal.html"),
    Licence("youtubedl-android", "GPL-3.0", "https://github.com/yausername/youtubedl-android/blob/master/LICENSE"),
    Licence("Python", "PSF License", "https://docs.python.org/3/license.html"),
    Licence("aria2", "GPL-2.0", "https://github.com/aria2/aria2/blob/master/COPYING"),
    Licence("QuickJS (quickjs-ng)", "MIT", "https://github.com/quickjs-ng/quickjs/blob/master/LICENSE"),
    Licence("AndroidX, Jetpack Compose, Media3", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    Licence("Coil", "Apache-2.0", "https://github.com/coil-kt/coil/blob/main/LICENSE.txt"),
    Licence("Kotlin and kotlinx.coroutines", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    Licence("Bricolage Grotesque", "SIL Open Font License 1.1", "https://openfontlicense.org", "licenses/OFL-BricolageGrotesque.txt"),
    Licence("Instrument Sans", "SIL Open Font License 1.1", "https://openfontlicense.org", "licenses/OFL-InstrumentSans.txt"),
    Licence("JetBrains Mono", "SIL Open Font License 1.1", "https://openfontlicense.org", "licenses/OFL-JetBrainsMono.txt"),
)

@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val c = Haul.colors
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        TitleBar("Open source licences", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)) {
            T(
                "Haul is free software under the GNU GPL-3.0. It is built on these projects.",
                sans(14.sp, lineHeight = 20.sp), c.textMid, Modifier.padding(bottom = 16.dp),
            )
            Column(Modifier.fillMaxWidth().clip(ShapeMd).background(c.surface1, ShapeMd)) {
                LICENCES.forEachIndexed { i, l ->
                    if (i > 0) Divider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable {
                                if (l.asset != null) open = if (open == l.name) null else l.name else ctx.openUrl(l.url)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            T(l.name, sans(15.sp), c.textHi)
                            T(l.licence, mono(11.sp), c.textLow)
                        }
                        HIcon(
                            if (l.asset != null) (if (open == l.name) HaulIcon.ChevDown else HaulIcon.ChevRight) else HaulIcon.External,
                            c.textLow, size = 18.dp,
                        )
                    }
                    if (l.asset != null && open == l.name) {
                        val text = remember(l.asset) { runCatching { ctx.assets.open(l.asset).bufferedReader().readText() }.getOrDefault("") }
                        T(
                            text, mono(11.sp, lineHeight = 16.sp), c.textMid,
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp).fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp)).background(c.ground).padding(12.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            T("Full GPL-3.0 text: gnu.org/licenses/gpl-3.0", sans(12.sp), c.textLow, Modifier.clickable { ctx.openUrl("https://www.gnu.org/licenses/gpl-3.0.html") })
        }
    }
}
