package app.haul.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon

/**
 * Choice row. Selected: signal-tint fill, 1dp signal border, filled radio.
 * Size is the estimated final file, mono, right aligned.
 */
@Composable
fun ChoiceRow(
    title: String,
    spec: String,
    size: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeOutlined: Boolean = true,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Haul.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(ShapeMd)
            .background(if (selected) c.signalTint else Color.Transparent, ShapeMd)
            .border(1.dp, if (selected) c.signal else Color.Transparent, ShapeMd)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (extra != null && selected) 14.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Radio(selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(title, sans(15.sp, FontWeight.SemiBold), c.textHi)
                    if (badge != null) {
                        if (badgeOutlined) Tag(badge, TagKind.Best) else Tag(badge, TagKind.Neutral)
                    }
                }
                T(spec, mono(11.sp), if (selected) c.textMid else c.textLow)
            }
            T(size, mono(12.sp), if (selected) c.textHi else c.textMid)
        }
        if (extra != null && selected) extra()
    }
}

/** Settings group: label above in mono 11 caps, rows on surface-1 radius 12, inner dividers. */
@Composable
fun SettingsGroup(label: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) SectionLabel(label)
        Column(
            Modifier.fillMaxWidth().clip(ShapeMd).background(Haul.colors.surface1, ShapeMd),
            content = content,
        )
    }
}

enum class Trailing { Chevron, External, None }

@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)?,
    first: Boolean = false,
    monoSubtitle: Boolean = false,
    trailing: Trailing = Trailing.Chevron,
    switch: Boolean? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    hint: String? = null,
) {
    val c = Haul.colors
    Column {
        if (!first) Divider(color = c.divider)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .then(if (onClick != null) Modifier.clickable(role = if (switch != null) Role.Switch else Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(title, sans(15.sp, FontWeight.Medium), c.textHi)
                if (subtitle != null) {
                    T(subtitle, if (monoSubtitle) mono(12.sp) else sans(13.sp, lineHeight = 18.sp), c.textLow)
                }
            }
            if (hint != null) T(hint, mono(11.sp), c.textLow)
            when {
                trailingContent != null -> trailingContent()
                switch != null -> HSwitch(switch)
                trailing == Trailing.Chevron -> HIcon(HaulIcon.ChevRight, c.textLow, size = 20.dp)
                trailing == Trailing.External -> HIcon(HaulIcon.External, c.textLow, size = 18.dp)
                else -> Unit
            }
        }
    }
}

/** − 2 + stepper used for "Downloads at the same time". */
@Composable
fun Stepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val c = Haul.colors
    Row(
        Modifier.clip(ShapeSmall10).background(c.surface2, ShapeSmall10).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(40.dp, 36.dp).clip(ShapeSm).clickable(enabled = value > min, role = Role.Button) { onChange(value - 1) },
            contentAlignment = Alignment.Center,
        ) { T("−", sans(18.sp), if (value > min) c.textHi else c.textMid) }
        T(value.toString(), mono(14.sp, FontWeight.SemiBold), c.textHi, Modifier.width(20.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        Box(
            Modifier.size(40.dp, 36.dp).clip(ShapeSm).clickable(enabled = value < max, role = Role.Button) { onChange(value + 1) },
            contentAlignment = Alignment.Center,
        ) { T("+", sans(18.sp), if (value < max) c.textHi else c.textMid) }
    }
}

/** Key/value row in detail lists (dl). */
@Composable
fun DetailRow(label: String, value: String, trailing: (@Composable () -> Unit)? = null) {
    val c = Haul.colors
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = if (trailing != null) 4.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(label, sans(13.sp), c.textLow)
            Box(Modifier.weight(1f))
            T(value, mono(12.sp), c.textHi, maxLines = 1)
            trailing?.invoke()
        }
        Divider(color = c.divider)
    }
}

/** Card on surface-1 with a 1dp line (#2A2925). */
@Composable
fun Card(modifier: Modifier = Modifier, padding: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = Haul.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(ShapeMd)
            .background(c.surface1, ShapeMd)
            .border(1.dp, c.divider, ShapeMd)
            .padding(padding),
        content = content,
    )
}

@Composable
fun IconTile(icon: HaulIcon, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Haul.colors.surface2, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) { HIcon(icon, tint, size = 22.dp) }
}
