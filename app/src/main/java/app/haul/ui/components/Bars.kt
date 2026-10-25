package app.haul.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon

/** Top bar 56 with the wordmark and Settings at top right (Grab only). */
@Composable
fun WordmarkBar(onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Wordmark(24.sp)
        IconButton44(HaulIcon.Settings, "Settings", onSettings)
    }
}

/** Top bar 56 with back and a Title 24. */
@Composable
fun TitleBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(start = if (onBack != null) 8.dp else 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) IconButton44(HaulIcon.Back, "Back", onBack)
        T(title, Haul.type.title, Haul.colors.textHi, Modifier.weight(1f), maxLines = 1)
        actions()
    }
}

enum class Tab(val label: String, val icon: HaulIcon) {
    Grab("Grab", HaulIcon.Link), Queue("Queue", HaulIcon.Queue), Library("Library", HaulIcon.Library)
}

/** Three tabs only. Active: signal icon, text-hi label 600. Queue badge counts active plus waiting. */
@Composable
fun BottomNav(current: Tab, queueBadge: Int, queueAlert: Boolean, onTab: (Tab) -> Unit) {
    val c = Haul.colors
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxWidth().background(c.ground)) {
        Divider(color = c.divider)
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = maxOf(24.dp, navInset + 8.dp)),
        ) {
            Tab.entries.forEach { tab ->
                val on = tab == current
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Tab) { onTab(tab) }
                        .semantics { selected = on },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        Modifier.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        HIcon(tab.icon, if (on) c.signal else c.textLow, size = 24.dp)
                        T(tab.label, sans(12.sp, if (on) FontWeight.SemiBold else FontWeight.Medium), if (on) c.textHi else c.textLow)
                    }
                    if (tab == Tab.Queue && (queueBadge > 0 || queueAlert)) {
                        Badge(
                            text = if (queueAlert) "!" else queueBadge.toString(),
                            color = if (queueAlert) c.err else c.signal,
                            modifier = Modifier.offset(x = 17.dp, y = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier
            .heightIn(min = 18.dp)
            .widthIn(min = 18.dp)
            .background(color, shape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, mono(10.sp, FontWeight.SemiBold), Haul.colors.onSignal)
    }
}

@Composable
fun VSpace(h: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: androidx.compose.ui.unit.Dp) = Spacer(Modifier.width(w))
