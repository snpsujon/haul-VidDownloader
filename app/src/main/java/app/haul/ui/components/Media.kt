package app.haul.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haul.data.DownloadEntity
import app.haul.data.MediaType
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.util.Pretty
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** 16:9 thumbnail, radius 8, Coil crossfade 150ms, surface-2 while loading, duration badge. */
@Composable
fun Thumb(
    url: String?,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    durationSec: Int = 0,
    radius: Dp = 8.dp,
    badgeInset: Dp = 4.dp,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.size(width, height).clip(shape).background(Haul.colors.surface2, shape)) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(150).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        overlay?.invoke(this)
        if (durationSec > 0) DurationBadge(durationSec, Modifier.align(Alignment.BottomEnd).padding(badgeInset))
    }
}

@Composable
fun DurationBadge(sec: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color(0xCC080706), shape)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        T(Pretty.duration(sec), mono(10.sp), Color(0xFFF3EFE6))
    }
}

/** Audio files without cover art: tape-tint tile with the audio glyph. */
@Composable
fun AudioTile(size: Dp = 54.dp, modifier: Modifier = Modifier, iconSize: Dp = 20.dp, url: String? = null) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.size(size).clip(shape).background(Haul.colors.tapeTint, shape),
        contentAlignment = Alignment.Center,
    ) {
        HIcon(HaulIcon.Audio, Haul.colors.tape, size = iconSize)
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(150).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Square tile with a glyph (waiting and done rows in the queue). */
@Composable
fun GlyphTile(icon: HaulIcon, tint: Color, background: Color, size: Dp = 54.dp, iconSize: Dp = 20.dp, stroke: Float = 1.75f) {
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.size(size).clip(shape).background(background, shape), contentAlignment = Alignment.Center) {
        HIcon(icon, tint, size = iconSize, stroke = stroke)
    }
}

/** Media type tag first, then the format tag. */
@Composable
fun typeTag(item: DownloadEntity): Pair<String, TagKind> =
    if (item.mediaType == MediaType.AUDIO) "AUDIO" to TagKind.Audio else "VIDEO" to TagKind.Video
