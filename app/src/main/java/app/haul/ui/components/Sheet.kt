package app.haul.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.haul.ui.theme.Haul

private val Decelerate = CubicBezierEasing(0f, 0f, 0f, 1f)

/**
 * Bottom sheet: scrim #080706 at 72%, surface-1, top corners 24, 36×4 handle.
 * Slides in over 280ms (decelerate).
 */
@Composable
fun HaulSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    fullHeight: Boolean = false,
    horizontalPadding: Dp = 20.dp,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Haul.colors
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    if (!state.currentState && !state.targetState && state.isIdle) return
    if (visible) BackHandler(enabled = dismissible) { onDismiss() }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(state, enter = fadeIn(tween(280)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(c.scrim.copy(alpha = 0.72f))
                    .clickable(remember { MutableInteractionSource() }, null, enabled = dismissible) { onDismiss() },
            )
        }
        AnimatedVisibility(
            state,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(280, easing = Decelerate)) { it },
            exit = slideOutVertically(tween(200)) { it },
        ) {
            Column(
                modifier
                    .fillMaxWidth()
                    .then(if (fullHeight) Modifier.padding(top = top).fillMaxSize() else Modifier.padding(top = top))
                    .clip(ShapeLgTop)
                    .background(c.surface1, ShapeLgTop)
                    // Swallow taps so they don't reach the scrim.
                    .pointerInput(Unit) { detectTapGesturesNoop() }
                    .padding(start = horizontalPadding, end = horizontalPadding, top = 10.dp, bottom = maxOf(28.dp, bottomInset + 12.dp)),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .background(c.dashed, RoundedCornerShape(2.dp)),
                )
                content()
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesNoop() {
    androidx.compose.foundation.gestures.detectTapGestures { }
}

/** Small centred dialog on surface-3 (confirmations, rename). */
@Composable
fun HaulDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    BackHandler { onDismiss() }
    val c = Haul.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(c.scrim.copy(alpha = 0.72f))
            .clickable(remember { MutableInteractionSource() }, null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(ShapeMd)
                .background(c.surface3, ShapeMd)
                .pointerInput(Unit) { detectTapGesturesNoop() }
                .padding(20.dp),
            content = content,
        )
    }
}

@Composable
fun SheetHandleSpacer() = Box(Modifier.size(1.dp))
