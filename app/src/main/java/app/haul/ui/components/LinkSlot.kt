package app.haul.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon

/**
 * Link slot. Empty: 1.5 dashed line. Focused or filled: 1.5 solid signal.
 * Error: 1.5 solid err with a 12/500 message below. Input text is mono 14.
 */
@Composable
fun LinkSlot(
    value: String,
    onValueChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    placeholder: String = "https://",
    showPaste: Boolean = value.isEmpty(),
) {
    val c = Haul.colors
    var focused by remember { mutableStateOf(false) }
    val filled = value.isNotEmpty()
    val borderColor = when {
        error != null -> c.err
        focused || filled -> c.signal
        else -> c.dashed
    }
    val dashed = error == null && !focused && !filled
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(ShapeMd)
                .background(c.surface1, ShapeMd)
                .then(
                    if (dashed) Modifier.drawBehind {
                        val w = 1.5.dp.toPx()
                        drawRoundRect(
                            color = borderColor,
                            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                            style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                        )
                    } else Modifier.border(1.5.dp, borderColor, ShapeMd)
                )
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) T(placeholder, mono(14.sp), c.textLow)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = mono(14.sp).copy(color = c.textHi),
                    cursorBrush = SolidColor(c.signal),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { onGo() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
            }
            if (showPaste) {
                HButton(
                    "Paste", onPaste, height = 44.dp, icon = HaulIcon.Clipboard, iconStroke = 1.9f,
                    modifier = Modifier, fontSize = 14.sp, radius = 10.dp,
                )
            } else if (filled) {
                IconButton44(HaulIcon.Close, "Clear link", onClear, color = c.textMid, iconSize = 20.dp)
            }
        }
        if (error != null) T(error, sans(12.sp, FontWeight.Medium), c.err)
    }
}

/** Search field for the Library, same shell as the link slot. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Haul.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(ShapeMd)
            .background(c.surface1, ShapeMd)
            .border(1.5.dp, c.signal, ShapeMd)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) T("Search titles and channels", sans(15.sp), c.textLow)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = sans(15.sp).copy(color = c.textHi),
                cursorBrush = SolidColor(c.signal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton44(HaulIcon.Close, "Close search", onClose, color = c.textMid, iconSize = 20.dp)
    }
}

val NoColor = Color.Transparent
