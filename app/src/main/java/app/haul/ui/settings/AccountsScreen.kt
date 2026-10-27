package app.haul.ui.settings

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.haul.data.Site
import app.haul.ui.components.HButton
import app.haul.ui.components.HaulSheet
import app.haul.ui.components.SettingsGroup
import app.haul.ui.components.SettingsRow
import app.haul.ui.components.ShapeMd
import app.haul.ui.components.SnackState
import app.haul.ui.components.T
import app.haul.ui.components.TextAction
import app.haul.ui.components.TitleBar
import app.haul.ui.components.sans
import app.haul.ui.theme.Haul

/** Settings → Accounts: sites signed in to, and sites to add. */
@Composable
fun AccountsScreen(vm: SettingsViewModel, snack: SnackState, onBack: () -> Unit, onSignIn: (Site) -> Unit) {
    val c = Haul.colors
    val signedIn by vm.signedIn.collectAsState()
    var otherSheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TitleBar("Accounts", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            T(
                "Sign in to save videos that need an account, such as private, members-only or age-restricted ones. " +
                    "Your sign-in stays on this phone and is only sent to that site.",
                sans(14.sp, lineHeight = 20.sp), c.textMid,
            )
            if (signedIn.isNotEmpty()) {
                SettingsGroup("SIGNED IN") {
                    signedIn.forEachIndexed { i, site ->
                        SettingsRow(
                            site.name, site.domains.first(), null, first = i == 0, monoSubtitle = true,
                            trailingContent = {
                                TextAction("Sign out", { vm.signOut(site); snack.show("Signed out of ${site.name}.") }, color = c.textMid)
                            },
                        )
                    }
                }
            }
            SettingsGroup("ADD A SITE") {
                val ids = signedIn.map { it.id }.toSet()
                Site.presets.filter { it.id !in ids }.forEachIndexed { i, site ->
                    SettingsRow(site.name, site.domains.first(), { onSignIn(site) }, first = i == 0, monoSubtitle = true)
                }
                SettingsRow("Another site", "Enter its address", { otherSheet = true }, first = Site.presets.all { it.id in ids })
            }
        }
    }
    OtherSiteSheet(otherSheet, onDismiss = { otherSheet = false }) { site ->
        otherSheet = false
        onSignIn(site)
    }
}

@Composable
private fun OtherSiteSheet(visible: Boolean, onDismiss: () -> Unit, onContinue: (Site) -> Unit) {
    val c = Haul.colors
    var address by remember { mutableStateOf("") }
    val site = Site.custom(address)
    HaulSheet(visible, onDismiss) {
        Spacer(Modifier.height(16.dp))
        T("Sign in to another site", Haul.type.headline, c.textHi)
        Spacer(Modifier.height(6.dp))
        T("Enter the site's address or its sign-in page.", sans(13.sp, lineHeight = 18.sp), c.textLow)
        Spacer(Modifier.height(16.dp))
        BasicTextField(
            value = address,
            onValueChange = { address = it },
            singleLine = true,
            textStyle = sans(15.sp).copy(color = c.textHi),
            cursorBrush = SolidColor(c.signal),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { site?.let(onContinue) }),
            modifier = Modifier.fillMaxWidth().clip(ShapeMd).background(c.surface2, ShapeMd).heightIn(min = 52.dp).padding(16.dp),
            decorationBox = { inner ->
                if (address.isEmpty()) T("vimeo.com", sans(15.sp), c.textLow)
                inner()
            },
        )
        Spacer(Modifier.height(16.dp))
        HButton("Continue", { site?.let(onContinue) }, Modifier.fillMaxWidth(), enabled = site != null)
        Spacer(Modifier.height(8.dp))
    }
}

/** The site's own sign-in page in a WebView. Done saves whatever sign-in it left behind. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInScreen(site: Site, onDone: () -> Unit, onBack: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    BackHandler(canGoBack) { web?.goBack() }
    Column(Modifier.fillMaxSize()) {
        TitleBar("Sign in to ${site.name}", onBack) {
            TextAction("Done", onDone)
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Some sign-in pages refuse embedded browsers by their user agent.
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    val view = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(view, true)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            canGoBack = view.canGoBack()
                        }
                    }
                    loadUrl(site.loginUrl)
                    web = this
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
