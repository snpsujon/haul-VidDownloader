package app.haul.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import kotlinx.coroutines.delay

/** Snackbar: surface-3, 4s, one action. */
@Stable
class SnackState {
    data class Snack(val message: String, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())

    var current by mutableStateOf<Snack?>(null)
        private set

    fun show(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        current = Snack(message, action, onAction)
    }

    fun dismiss() { current = null }
}

@Composable
fun SnackHost(state: SnackState, modifier: Modifier = Modifier) {
    val snack = state.current
    LaunchedEffect(snack?.id) {
        if (snack != null) { delay(4000); if (state.current?.id == snack.id) state.dismiss() }
    }
    AnimatedVisibility(
        snack != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        val s = snack ?: return@AnimatedVisibility
        val c = Haul.colors
        Row(
            Modifier
                .padding(horizontal = Gutter, vertical = 8.dp)
                .fillMaxWidth()
                .clip(ShapeMd)
                .background(c.surface3, ShapeMd)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T(s.message, sans(14.sp), c.textHi, Modifier.weight(1f))
            if (s.action != null) {
                T(
                    s.action, sans(14.sp, FontWeight.SemiBold), c.signal,
                    Modifier.clickable { s.onAction?.invoke(); state.dismiss() }.padding(4.dp),
                )
            }
        }
    }
}

/** Error card: err-tint, alert icon, plain-language first line, the fix second. */
@Composable
fun ErrorCard(
    title: String,
    fix: String,
    modifier: Modifier = Modifier,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Haul.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(ShapeMd)
            .background(c.errTint, ShapeMd)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HIcon(HaulIcon.Alert, c.err, size = 20.dp, modifier = Modifier.padding(top = 1.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T(title, sans(15.sp, FontWeight.SemiBold), c.textHi)
                T(fix, sans(13.sp, lineHeight = 18.sp), c.errBody)
            }
        }
        content?.invoke(this)
    }
}

/** "Technical details" disclosure inside error cards. */
@Composable
fun TechnicalDetails(text: String, open: Boolean, onToggle: () -> Unit) {
    val c = Haul.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.heightIn(min = 32.dp).clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HIcon(if (open) HaulIcon.ChevDown else HaulIcon.ChevRight, c.errBody, size = 14.dp, stroke = 2f)
            T("Technical details", sans(13.sp, FontWeight.SemiBold), c.errBody)
        }
        if (open) {
            T(
                text, mono(11.sp, lineHeight = 16.sp), c.errBody,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.errCode).padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}
