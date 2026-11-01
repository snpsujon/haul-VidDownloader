package app.haul.ui.grab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.engine.Trim
import app.haul.ui.components.ShapeMd
import app.haul.ui.components.T
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.Haul

/** Start and end fields with a range slider, for the download sheet and the Library's Trim. */
@Composable
fun TrimEditor(
    start: String,
    end: String,
    durationSec: Int,
    onStart: (String) -> Unit,
    onEnd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Haul.colors
    val error = Trim.error(start, end, durationSec)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField("Start", start, onStart, Modifier.weight(1f))
            TimeField("End", end, onEnd, Modifier.weight(1f))
        }
        if (durationSec > 0) {
            val s = (Trim.parse(start) ?: 0).coerceIn(0, durationSec)
            val e = (Trim.parse(end) ?: durationSec).coerceIn(0, durationSec)
            RangeSlider(
                value = s.toFloat()..maxOf(s, e).toFloat(),
                onValueChange = { r ->
                    onStart(Trim.format(r.start.toInt()))
                    onEnd(Trim.format(r.endInclusive.toInt()))
                },
                valueRange = 0f..durationSec.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = c.signal,
                    activeTrackColor = c.signal,
                    inactiveTrackColor = c.surface2,
                ),
            )
        }
        if (error != null) T(error, sans(12.sp, lineHeight = 17.sp), c.err)
        else T("Exact cuts take a little longer to process.", sans(12.sp, lineHeight = 17.sp), c.textLow)
    }
}

@Composable
private fun TimeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val c = Haul.colors
    Column(
        modifier.clip(ShapeMd).background(c.surface2, ShapeMd).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        T(label, sans(12.sp), c.textLow)
        BasicTextField(
            value = value,
            onValueChange = { v -> onChange(v.filter { it.isDigit() || it == ':' }.take(8)) },
            singleLine = true,
            textStyle = mono(15.sp).copy(color = c.textHi),
            cursorBrush = SolidColor(c.signal),
            modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
        )
    }
}
