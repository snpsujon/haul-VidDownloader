package app.haul.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The icon set from "00 Foundations": 24dp grid, 1.75dp stroke, round caps and joins,
 * outline only. Paths are the exact SVG paths from the design.
 */
enum class HaulIcon(val d: String, val viewport: Float = 24f) {
    Link("M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1"),
    Queue("M12 4v11M7 10l5 5 5-5M5 20h14"),
    Library("M4 5h4v14H4zM10 5h4v14h-4zM16.5 5.5l3.5 13"),
    Settings("M4 7h10M18 7h2M4 17h4M12 17h8M16 5v4M10 15v4"),
    Clipboard("M8 5H6v16h12V5h-2M9 3h6v4H9z"),
    Video("M3 6h18v12H3zM3 10h18M3 14h18M8 6v4M16 6v4M8 14v4M16 14v4"),
    Audio("M9 18V6l10-2v12M9 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0zM19 16a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z"),
    Play("M7 5l12 7-12 7z"),
    Pause("M8 5v14M16 5v14"),
    Close("M6 6l12 12M18 6L6 18"),
    Retry("M4 12a8 8 0 1 0 2.5-5.8M4 4v4h4"),
    Forward("M20 12a8 8 0 1 1-2.5-5.8M20 4v4h-4"),
    Check("M5 12.5l4.5 4.5L19 7.5"),
    ChevRight("M9 6l6 6-6 6"),
    ChevDown("M6 9l6 6 6-6"),
    Back("M19 12H5M11 6l-6 6 6 6"),
    ArrowRight("M5 12h14M13 6l6 6-6 6"),
    Folder("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"),
    Bell("M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15zM10 21h4"),
    Share("M12 4v11M8 8l4-4 4 4M5 13v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5"),
    Delete("M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13"),
    Search("M17 11a6 6 0 1 1-12 0 6 6 0 0 1 12 0zM20 20l-4.5-4.5"),
    Offline("M3 3l18 18M8.5 16.5a5 5 0 0 1 7 0M5 12.5a10 10 0 0 1 4-2.3M14.5 10.2a10 10 0 0 1 4.5 2.3M12 20h.01"),
    OfflineLarge("M3 3l18 18M8.5 16.5a5 5 0 0 1 7 0M5 12.5a10 10 0 0 1 4-2.3M14.5 10.2a10 10 0 0 1 4.5 2.3M2 8.8a15 15 0 0 1 3.5-2.2M18.5 6.6A15 15 0 0 1 22 8.8M12 20h.01"),
    Alert("M12 4l9 16H3zM12 10v4M12 17.5v.01"),
    Engine("M4 5h16v14H4zM8 10l3 2-3 2M13 15h3"),
    Shield("M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6zM8.5 12l2.5 2.5 4.5-5"),
    Battery("M7 4h10v17H7zM10 2h4M10 15l2-4h-2l2-4"),
    External("M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"),
    Copy("M9 9h11v11H9zM5 15H4V4h11v1"),
    Rename("M4 20h4l11-11-4-4L4 16zM13.5 6.5l4 4"),
    Sort("M7 4v16M3 16l4 4 4-4M14 6h7M14 11h5M14 16h3"),
    Reorder("M5 9h14M5 15h14"),
    Fullscreen("M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"),
    Moon("M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5z"),
    // Filled glyphs (drawn with fill instead of stroke).
    PrevFilled("M18 5v14L8 12zM6 5h2v14H6z"),
    NextFilled("M6 5v14l10-7zM16 5h2v14h-2z"),
    // App mark, 64 unit grid, square caps.
    AppMark("M32 12v28M20 30l12 12 12-12M14 52h36", viewport = 64f),
}

@Composable
fun HIcon(
    icon: HaulIcon,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    stroke: Float = 1.75f,
    filled: Boolean = false,
    squareCaps: Boolean = false,
    contentDescription: String? = null,
) {
    val path = remember(icon) { PathParser().parsePathString(icon.d).toPath() }
    val semantics = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else Modifier
    Canvas(modifier.size(size).then(semantics)) {
        val s = this.size.minDimension / icon.viewport
        scale(s, s, pivot = Offset.Zero) {
            if (filled) {
                drawPath(path, color, style = Fill)
            } else {
                drawPath(
                    path, color,
                    style = Stroke(
                        width = stroke,
                        cap = if (squareCaps) StrokeCap.Square else StrokeCap.Round,
                        join = if (squareCaps) StrokeJoin.Miter else StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}

/** Three-dot "more" glyph (filled circles). */
@Composable
fun MoreDots(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val r = 1.6f * s
        drawCircle(color, r, Offset(12f * s, 5.5f * s))
        drawCircle(color, r, Offset(12f * s, 12f * s))
        drawCircle(color, r, Offset(12f * s, 18.5f * s))
    }
}
