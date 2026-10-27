package app.haul.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.data.Pairing
import app.haul.ui.components.ButtonKind
import app.haul.ui.components.ErrorCard
import app.haul.ui.components.HButton
import app.haul.ui.components.HaulSheet
import app.haul.ui.components.ShapeMd
import app.haul.ui.components.T
import app.haul.ui.components.Wordmark
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.Haul
import kotlinx.coroutines.launch

/** Confirms a haul://pair link before this phone starts taking links from that browser. */
@Composable
fun PairSheet(pairing: Pairing, onConnect: suspend () -> Unit, onConnected: () -> Unit, onClose: () -> Unit) {
    val c = Haul.colors
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { visible = true }
    Box(Modifier.fillMaxSize()) {
        HaulSheet(visible, { visible = false; onClose() }) {
            Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(20.sp, Modifier.weight(1f))
                T("SEND TO PHONE", mono(11.sp), c.textLow)
            }
            Spacer(Modifier.height(16.dp))
            T("Connect this phone to your browser?", Haul.type.headline, c.textHi)
            Spacer(Modifier.height(6.dp))
            T(
                "Links you send from the Haul browser extension will download here. You can remove it any time in Settings.",
                sans(13.sp, lineHeight = 18.sp), c.textLow,
            )
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.fillMaxWidth().clip(ShapeMd).background(c.surface2, ShapeMd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                T("THROUGH", mono(11.sp, tracking = 0.08f), c.textLow)
                T(pairing.host, mono(14.sp), c.textHi, maxLines = 1)
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                ErrorCard("Couldn't connect.", it)
            }
            Spacer(Modifier.height(20.dp))
            HButton(
                if (connecting) "Connecting…" else "Connect",
                {
                    connecting = true
                    error = null
                    scope.launch {
                        val r = runCatching { onConnect() }
                        connecting = false
                        r.onSuccess { visible = false; onConnected() }
                        r.onFailure { e ->
                            error = (e as? PcInbox.PairException)?.message
                                ?: "Couldn't reach ${pairing.host}. Check your connection and try again."
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                enabled = !connecting,
            )
            Spacer(Modifier.height(8.dp))
            HButton("Not now", { visible = false; onClose() }, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost, height = 44.dp)
            Spacer(Modifier.height(8.dp))
        }
    }
}
