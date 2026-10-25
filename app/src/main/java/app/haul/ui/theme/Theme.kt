package app.haul.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.haul.data.ThemeMode

object Haul {
    val colors: HaulColors
        @Composable get() = LocalHaulColors.current
    val type: HaulType
        @Composable get() = LocalHaulType.current
}

@Composable
fun HaulTheme(
    mode: ThemeMode = ThemeMode.DARK,
    wallpaperColours: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val base = if (dark) DarkHaulColors else LightHaulColors
    val colors = remember(dark, wallpaperColours) {
        if (wallpaperColours && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // "Use wallpaper colours" swaps only the signal family for the Material You accent.
            val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            base.copy(
                signal = scheme.primary,
                onSignal = scheme.onPrimary,
                signalTint = scheme.primaryContainer.copy(alpha = if (dark) 0.45f else 1f),
                signalPressed = scheme.primary.copy(alpha = 0.85f),
                videoTagText = scheme.primary,
            )
        } else base
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    val material = if (dark) {
        darkColorScheme(
            primary = colors.signal, onPrimary = colors.onSignal,
            background = colors.ground, onBackground = colors.textHi,
            surface = colors.surface1, onSurface = colors.textHi,
            surfaceVariant = colors.surface2, onSurfaceVariant = colors.textMid,
            outline = colors.line, error = colors.err,
        )
    } else {
        lightColorScheme(
            primary = colors.signal, onPrimary = colors.onSignal,
            background = colors.ground, onBackground = colors.textHi,
            surface = colors.surface1, onSurface = colors.textHi,
            surfaceVariant = colors.surface2, onSurfaceVariant = colors.textMid,
            outline = colors.line, error = colors.err,
        )
    }

    MaterialTheme(colorScheme = material) {
        CompositionLocalProvider(
            LocalHaulColors provides colors,
            LocalHaulType provides HaulType(),
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = colors.signal,
                backgroundColor = colors.signal.copy(alpha = 0.3f),
            ),
            content = content,
        )
    }
}

val Transparent = Color.Transparent
