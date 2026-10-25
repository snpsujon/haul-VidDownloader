package app.haul.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.haul.ui.theme.Haul
import kotlinx.coroutines.delay
import kotlin.math.floor

enum class TapeState { Downloading, Paused, Merging, Done, Failed }

/** True when the system "Remove animations" setting is on. */
@Composable
fun reduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) == 0f }
            .getOrDefault(false)
    }
}

/**
 * Tape progress, the signature component. A row of ticks fills the width; each tick is 1/N of
 * the file and lights only when its slice is complete, so progress moves in clear steps.
 * Never a smooth bar.
 */
@Composable
fun TapeProgress(
    progress: Float,
    state: TapeState,
    modifier: Modifier = Modifier,
    ticks: Int = 48,
    height: Dp = 14.dp,
    gap: Dp = 2.dp,
    radius: Dp = 1.dp,
    onColor: Color? = null,
) {
    val c = Haul.colors
    val noMotion = reduceMotion()
    val target = when (state) {
        TapeState.Merging, TapeState.Done -> ticks
        else -> floor(ticks * progress.coerceIn(0f, 1f)).toInt()
    }
    // Ticks fill one at a time, 80ms per tick.
    var lit by remember { mutableIntStateOf(target) }
    LaunchedEffect(target, noMotion) {
        if (noMotion || target < lit) { lit = target; return@LaunchedEffect }
        while (lit < target) { delay(80); lit++ }
    }
    val pulse = if (state == TapeState.Merging && !noMotion) {
        val t = rememberInfiniteTransition(label = "merge")
        val a by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "a")
        a
    } else 1f
    val on = onColor ?: when (state) {
        TapeState.Downloading -> c.signal
        TapeState.Paused -> c.textMid
        TapeState.Merging -> c.signal
        TapeState.Done -> c.ok
        TapeState.Failed -> c.err
    }
    val off = c.tickOff
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f) },
    ) {
        val g = gap.toPx()
        val w = (size.width - g * (ticks - 1)) / ticks
        val r = CornerRadius(radius.toPx())
        for (i in 0 until ticks) {
            val color = if (i < lit) on.copy(alpha = on.alpha * pulse) else off
            drawRoundRect(color, Offset(i * (w + g), 0f), Size(w, size.height), r)
        }
    }
}

/** Indeterminate "scan" while reading a link: a 6-tick window sweeps left to right, 900ms per pass. */
@Composable
fun ScanTicks(modifier: Modifier = Modifier, ticks: Int = 48, height: Dp = 6.dp) {
    val c = Haul.colors
    val noMotion = reduceMotion()
    val pos = if (noMotion) 17f else {
        val t = rememberInfiniteTransition(label = "scan")
        val p by t.animateFloat(-7f, ticks.toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p")
        p
    }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val g = 2.dp.toPx()
        val w = (size.width - g * (ticks - 1)) / ticks
        val start = floor(pos).toInt()
        for (i in 0 until ticks) {
            val color = when (i) {
                in start until start + 6 -> c.signal
                start - 1, start + 6 -> c.signal.copy(alpha = 0.45f)
                else -> c.tickOff
            }
            drawRect(color, Offset(i * (w + g), 0f), Size(w, size.height))
        }
    }
}

/** Decorative tick row with varying heights (welcome screen). */
@Composable
fun TickSkyline(modifier: Modifier = Modifier, ticks: Int = 48, lit: Int = 29, height: Dp = 28.dp) {
    val c = Haul.colors
    Canvas(modifier.fillMaxWidth().height(height)) {
        val g = 2.dp.toPx()
        val w = (size.width - g * (ticks - 1)) / ticks
        for (i in 0 until ticks) {
            val hDp = 8 + Math.round(20 * kotlin.math.abs(kotlin.math.sin(i * 0.55) * kotlin.math.cos(i * 0.17))).toInt()
            val h = hDp.dp.toPx()
            drawRoundRect(
                if (i < lit) c.signal else c.tickOff,
                Offset(i * (w + g), size.height - h), Size(w, h), CornerRadius(1.dp.toPx()),
            )
        }
    }
}
