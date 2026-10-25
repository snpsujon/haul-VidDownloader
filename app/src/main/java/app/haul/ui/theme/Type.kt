package app.haul.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.haul.R

// Static instances cut from the OFL variable fonts (licences in assets/licenses; how they were cut is in the README).
// Bricolage Grotesque uses optical size 96 for 34sp and up, 24 below.
val BricolageDisplay = FontFamily(Font(R.font.bricolage_display_bold, FontWeight.Bold))
val Bricolage = FontFamily(
    Font(R.font.bricolage_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_bold, FontWeight.Bold),
)
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
)
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

private val tight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

fun style(
    family: FontFamily,
    size: TextUnit,
    weight: FontWeight,
    lineHeight: TextUnit = TextUnit.Unspecified,
    tracking: TextUnit = 0.em,
) = TextStyle(
    fontFamily = family,
    fontSize = size,
    fontWeight = weight,
    lineHeight = lineHeight,
    letterSpacing = tracking,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = tight,
)

/** The type scale from "00 Foundations", plus the few sizes individual screens call for. */
@Immutable
data class HaulType(
    val display: TextStyle = style(BricolageDisplay, 34.sp, FontWeight.Bold, 38.sp, (-0.02).em),
    val title: TextStyle = style(Bricolage, 24.sp, FontWeight.SemiBold, 28.sp, (-0.01).em),
    val headline: TextStyle = style(InstrumentSans, 17.sp, FontWeight.SemiBold, 22.sp),
    val body: TextStyle = style(InstrumentSans, 15.sp, FontWeight.Normal, 22.sp),
    val label: TextStyle = style(InstrumentSans, 14.sp, FontWeight.SemiBold, 20.sp),
    val caption: TextStyle = style(InstrumentSans, 12.sp, FontWeight.Medium, 16.sp),
    val data: TextStyle = style(JetBrainsMono, 12.sp, FontWeight.Medium, 16.sp, 0.04.em),
    /** Mono 11 caps section labels (+8%). */
    val section: TextStyle = style(JetBrainsMono, 11.sp, FontWeight.Medium, 16.sp, 0.08.em),
    /** Mono 11 (+4%) for status lines under progress. */
    val status: TextStyle = style(JetBrainsMono, 11.sp, FontWeight.Medium, 16.sp, 0.04.em),
)

val LocalHaulType = staticCompositionLocalOf { HaulType() }
