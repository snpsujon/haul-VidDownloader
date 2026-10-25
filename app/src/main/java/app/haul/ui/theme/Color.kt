package app.haul.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Design tokens from "00 Foundations". Token names match the design system; values are final.
 * The extra tokens at the end are values the screens use that sit between the named ones.
 */
@Immutable
data class HaulColors(
    val isDark: Boolean,
    val ground: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val scrim: Color,
    val textHi: Color,
    val textMid: Color,
    val textLow: Color,
    val signal: Color,
    val onSignal: Color,
    val signalTint: Color,
    val signalPressed: Color,
    val tape: Color,
    val tapeTint: Color,
    val ok: Color,
    val err: Color,
    val errTint: Color,
    val info: Color,
    // In-between values used by the screens.
    val divider: Color,        // inner dividers in groups and lists (#2A2925)
    val rowDivider: Color,     // dense table rows (#1F1E1B)
    val dashed: Color,         // empty link slot, sheet handle, switch-off border (#4A4740)
    val disabled: Color,       // disabled label, empty radio (#6B675E)
    val errBody: Color,        // second line in error cards (#E6C3BF)
    val errLine: Color,        // outlined buttons inside error cards (#5A3431)
    val errCode: Color,        // technical details block (#1E1110)
    val videoTagText: Color,   // video tag text (signal in dark, darker in light)
    val tickOff: Color,        // unlit tape ticks
    val inputDivider: Color,   // dividers inside surface-2 field groups (#2F2D28)
    val termsBody: Color,      // terms of use paragraph on About (#D9D3C6)
)

val DarkHaulColors = HaulColors(
    isDark = true,
    ground = Color(0xFF141311),
    surface1 = Color(0xFF1C1B18),
    surface2 = Color(0xFF252420),
    surface3 = Color(0xFF2F2D28),
    line = Color(0xFF3A3832),
    scrim = Color(0xFF080706),
    textHi = Color(0xFFF3EFE6),
    textMid = Color(0xFFB5AFA2),
    textLow = Color(0xFF9A9488),
    signal = Color(0xFFFF7A3D),
    onSignal = Color(0xFF1A0E06),
    signalTint = Color(0xFF3A2216),
    signalPressed = Color(0xFFE8662B),
    tape = Color(0xFF8FD0C1),
    tapeTint = Color(0xFF1B302B),
    ok = Color(0xFFA9D68C),
    err = Color(0xFFFF8B85),
    errTint = Color(0xFF3B1D1B),
    info = Color(0xFFA9C4F5),
    divider = Color(0xFF2A2925),
    rowDivider = Color(0xFF1F1E1B),
    dashed = Color(0xFF4A4740),
    disabled = Color(0xFF6B675E),
    errBody = Color(0xFFE6C3BF),
    errLine = Color(0xFF5A3431),
    errCode = Color(0xFF1E1110),
    videoTagText = Color(0xFFFF7A3D),
    tickOff = Color(0xFF3A3832),
    inputDivider = Color(0xFF2F2D28),
    termsBody = Color(0xFFD9D3C6),
)

/** Light theme, same token names ("04 Home · with recents (light theme)"). */
val LightHaulColors = HaulColors(
    isDark = false,
    ground = Color(0xFFF4F1EA),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEAE6DC),
    surface3 = Color(0xFFDFDACE),
    line = Color(0xFFD6D0C3),
    scrim = Color(0xFF080706),
    textHi = Color(0xFF1A1915),
    textMid = Color(0xFF57534A),
    textLow = Color(0xFF6E695E),
    signal = Color(0xFFC2440F),
    onSignal = Color(0xFFFFFFFF),
    signalTint = Color(0xFFF8E1D5),
    signalPressed = Color(0xFFA63A0C),
    tape = Color(0xFF1F6E5E),
    tapeTint = Color(0xFFD7EDE7),
    ok = Color(0xFF3F7A26),
    err = Color(0xFFB3261E),
    errTint = Color(0xFFF9DEDC),
    info = Color(0xFF2F5DA8),
    divider = Color(0xFFE2DDD1),
    rowDivider = Color(0xFFEAE6DC),
    dashed = Color(0xFFB9B2A3),
    disabled = Color(0xFF8A8478),
    errBody = Color(0xFF6B2621),
    errLine = Color(0xFFE5B5B0),
    errCode = Color(0xFFF2D3CF),
    videoTagText = Color(0xFFA63A0C),
    tickOff = Color(0xFFD6D0C3),
    inputDivider = Color(0xFFDFDACE),
    termsBody = Color(0xFF3A3731),
)

val LocalHaulColors = staticCompositionLocalOf { DarkHaulColors }
