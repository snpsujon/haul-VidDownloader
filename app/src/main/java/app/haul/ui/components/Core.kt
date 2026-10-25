package app.haul.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.haul.ui.theme.BricolageDisplay
import app.haul.ui.theme.Bricolage
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.ui.theme.InstrumentSans
import app.haul.ui.theme.JetBrainsMono
import app.haul.ui.theme.style

// Shapes from "Shape": xs 6 tags, sm 8 thumbnails, md 12 buttons/cards, lg 24 sheet tops.
val ShapeXs = RoundedCornerShape(6.dp)
val ShapeSm = RoundedCornerShape(8.dp)
val ShapeMd = RoundedCornerShape(12.dp)
val ShapeSmall10 = RoundedCornerShape(10.dp)
val ShapeLgTop = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/** Screen gutter. */
val Gutter = 20.dp

@Composable
fun T(
    text: String,
    style: TextStyle,
    color: Color = Haul.colors.textHi,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = align,
    )
}

fun sans(size: TextUnit, weight: FontWeight = FontWeight.Normal, lineHeight: TextUnit = TextUnit.Unspecified) =
    style(InstrumentSans, size, weight, lineHeight)

fun mono(size: TextUnit, weight: FontWeight = FontWeight.Medium, tracking: Float = 0f, lineHeight: TextUnit = TextUnit.Unspecified) =
    style(JetBrainsMono, size, weight, lineHeight, tracking.em)

fun grotesk(size: TextUnit, weight: FontWeight = FontWeight.SemiBold, tracking: Float = -0.01f, lineHeight: TextUnit = TextUnit.Unspecified) =
    style(if (size.value >= 34f) BricolageDisplay else Bricolage, size, weight, lineHeight, tracking.em)

/** The "haul." wordmark. The full stop is always signal. Swap the word when the name is final. */
@Composable
fun Wordmark(size: TextUnit = 24.sp, modifier: Modifier = Modifier, dark: Color = Haul.colors.textHi) {
    val tracking = when {
        size.value >= 80 -> -0.04f
        size.value >= 50 -> -0.045f
        else -> -0.03f
    }
    val signal = Haul.colors.signal
    Text(
        text = buildAnnotatedString {
            append("haul")
            withStyle(SpanStyle(color = signal)) { append(".") }
        },
        style = style(if (size.value >= 34f) BricolageDisplay else Bricolage, size, FontWeight.Bold, (size.value * 0.9f).sp, tracking.em),
        color = dark,
        modifier = modifier.semantics { contentDescription = "Haul" },
    )
}

/** Section labels: mono 11 caps, text-low. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Haul.colors.textLow) {
    T(text.uppercase(), Haul.type.section, color, modifier)
}

// Buttons --------------------------------------------------------------------------------------

enum class ButtonKind { Primary, Secondary, Ghost, Destructive }

@Composable
fun HButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    icon: HaulIcon? = null,
    iconStroke: Float = 2f,
    textColor: Color? = null,
    fontSize: TextUnit = if (height >= 48.dp) 15.sp else 14.sp,
    radius: Dp = if (height >= 48.dp) 12.dp else 10.dp,
    border: Color? = null,
) {
    val c = Haul.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg = when {
        !enabled -> if (kind == ButtonKind.Ghost) Color.Transparent else c.surface2
        kind == ButtonKind.Primary -> if (pressed) c.signalPressed else c.signal
        kind == ButtonKind.Secondary -> if (pressed) c.surface3 else c.surface2
        kind == ButtonKind.Ghost -> if (pressed) c.surface3 else Color.Transparent
        else -> if (pressed) c.surface3 else c.errTint
    }
    val animatedBg by animateColorAsState(bg, tween(120), label = "btn")
    val fg = textColor ?: when {
        !enabled -> c.disabled
        kind == ButtonKind.Primary -> c.onSignal
        kind == ButtonKind.Secondary -> c.textHi
        kind == ButtonKind.Ghost -> c.signal
        else -> c.err
    }
    val shape = RoundedCornerShape(radius)
    val b = border ?: if (kind == ButtonKind.Secondary && enabled) c.line else null
    Row(
        modifier
            .heightIn(min = height)
            .height(height)
            .clip(shape)
            .background(animatedBg, shape)
            .then(if (b != null) Modifier.border(1.dp, b, shape) else Modifier)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            HIcon(icon, fg, size = if (height >= 48.dp) 20.dp else 18.dp, stroke = iconStroke)
            Spacer(Modifier.width(8.dp))
        }
        T(text, sans(fontSize, FontWeight.SemiBold), fg, maxLines = 1)
    }
}

/** 44×44 touch target holding an icon. */
@Composable
fun IconButton44(
    icon: HaulIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Haul.colors.textHi,
    iconSize: Dp = 22.dp,
    stroke: Float = 1.75f,
    filled: Boolean = false,
    size: Dp = 44.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(ShapeSmall10)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        HIcon(icon, color, size = iconSize, stroke = stroke, filled = filled)
    }
}

/** Plain text action (e.g. "Skip", "Use it", "See all", "Pause all"). */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Haul.colors.signal,
    size: TextUnit = 14.sp,
    height: Dp = 44.dp,
) {
    Box(
        modifier
            .height(height)
            .clip(ShapeSm)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, sans(size, FontWeight.SemiBold), color, maxLines = 1)
    }
}

// Tags ------------------------------------------------------------------------------------------

enum class TagKind { Video, Audio, Neutral, Best, BestOnTint }

/** 22 high in the standard size, 20 inside dense rows (mono 10). */
@Composable
fun Tag(text: String, kind: TagKind, modifier: Modifier = Modifier, compact: Boolean = true) {
    val c = Haul.colors
    val (bg, fg) = when (kind) {
        TagKind.Video -> c.signalTint to c.videoTagText
        TagKind.Audio -> c.tapeTint to c.tape
        TagKind.Neutral -> c.surface2 to c.textMid
        TagKind.Best, TagKind.BestOnTint -> Color.Transparent to c.signal
    }
    val weight = if (kind == TagKind.Neutral && !compact) FontWeight.Medium else FontWeight.SemiBold
    val h = if (compact) 20.dp else 22.dp
    Box(
        modifier
            .height(h)
            .clip(ShapeXs)
            .background(bg, ShapeXs)
            .then(if (kind == TagKind.Best || kind == TagKind.BestOnTint) Modifier.border(1.dp, c.signal, ShapeXs) else Modifier)
            .padding(horizontal = if (compact) 6.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text.uppercase(), mono(if (compact) 10.sp else 11.sp, weight, 0.04f), fg, maxLines = 1)
    }
}

// Selection controls ---------------------------------------------------------------------------

/** Segmented control. Selection is shown in bone (text-hi fill), never signal. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<HaulIcon?> = emptyList(),
    compact: Boolean = false,
    monoLabels: Boolean = false,
    trackColor: Color = Haul.colors.surface2,
) {
    val c = Haul.colors
    val pad = if (compact) 3.dp else 4.dp
    val trackShape = RoundedCornerShape(if (compact) 10.dp else 12.dp)
    val segShape = RoundedCornerShape(if (compact) 8.dp else 9.dp)
    Row(
        modifier.fillMaxWidth().clip(trackShape).background(trackColor, trackShape).padding(pad),
        horizontalArrangement = Arrangement.spacedBy(pad),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.textHi else Color.Transparent, tween(120), label = "seg")
            val fg = if (on) c.ground else c.textMid
            Row(
                Modifier
                    .weight(1f)
                    .height(if (compact) 36.dp else 44.dp)
                    .clip(segShape)
                    .background(bg, segShape)
                    .clickable(role = Role.Tab) { onSelect(i) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icons.getOrNull(i)?.let {
                    HIcon(it, fg, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                }
                T(
                    label,
                    if (monoLabels) mono(12.sp, FontWeight.SemiBold) else sans(if (compact) 13.sp else 14.sp, FontWeight.SemiBold),
                    fg,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Filter chip, 36 high in a 48 touch area. */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    hPad: Dp = 14.dp,
    monoLabel: Boolean = false,
    height: Dp = 36.dp,
) {
    val c = Haul.colors
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Checkbox, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .height(height)
                .clip(ShapeSm)
                .background(if (selected) c.textHi else c.surface2, ShapeSm)
                .then(if (selected) Modifier else Modifier.border(1.dp, c.line, ShapeSm))
                .padding(horizontal = hPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            T(
                label,
                if (monoLabel) mono(11.sp, FontWeight.SemiBold) else sans(13.sp, if (selected) FontWeight.SemiBold else FontWeight.Medium),
                if (selected) c.ground else if (monoLabel) c.textMid else c.textHi,
                maxLines = 1,
            )
            if (count != null) T(count, mono(11.sp), if (selected) c.ground else c.textLow)
        }
    }
}

@Composable
fun Radio(selected: Boolean, modifier: Modifier = Modifier) {
    val c = Haul.colors
    Box(
        modifier
            .size(20.dp)
            .border(2.dp, if (selected) c.signal else c.disabled, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).background(c.signal, RoundedCornerShape(5.dp)))
    }
}

/** Square checkbox from the playlist picker (22, radius 6). */
@Composable
fun CheckBoxMark(checked: Boolean, partial: Boolean = false, modifier: Modifier = Modifier) {
    val c = Haul.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .size(22.dp)
            .then(
                if (checked || partial) Modifier.background(c.signal, shape)
                else Modifier.border(2.dp, c.disabled, shape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            partial -> Box(Modifier.width(10.dp).height(2.dp).background(c.onSignal, RoundedCornerShape(1.dp)))
            checked -> HIcon(HaulIcon.Check, c.onSignal, size = 14.dp, stroke = 3f)
        }
    }
}

/** Switch, 52×32. The only pill shape in the system. */
@Composable
fun HSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Haul.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .width(52.dp)
            .height(32.dp)
            .clip(shape)
            .background(if (checked) c.signal else c.surface2, shape)
            .then(if (checked) Modifier else Modifier.border(1.5.dp, c.dashed, shape))
            .padding(horizontal = if (checked) 4.dp else 5.dp)
            .semantics { contentDescription = if (checked) "On" else "Off" },
        horizontalArrangement = if (checked) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked) Box(Modifier.size(24.dp).background(c.onSignal, RoundedCornerShape(12.dp)))
        else Box(Modifier.size(20.dp).background(c.textLow, RoundedCornerShape(10.dp)))
    }
}

/** Grey placeholder block used while a link is being read. */
@Composable
fun Skeleton(modifier: Modifier, radius: Dp = 4.dp) {
    Box(modifier.background(Haul.colors.surface2, RoundedCornerShape(radius)))
}

@Composable
fun Divider(modifier: Modifier = Modifier, color: Color = Haul.colors.divider) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun RowScope.Fill() = Spacer(Modifier.weight(1f))

@Composable
fun Bullet(color: Color) = Box(Modifier.padding(top = 8.dp).size(6.dp).background(color))
