package app.haul.player

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.SessionCommand
import app.haul.data.DownloadEntity
import app.haul.ui.components.Divider
import app.haul.ui.components.IconButton44
import app.haul.ui.components.SnackState
import app.haul.ui.components.T
import app.haul.ui.components.Tag
import app.haul.ui.components.TagKind
import app.haul.ui.components.mono
import app.haul.ui.components.sans
import app.haul.ui.theme.HIcon
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulIcon
import app.haul.ui.theme.MoreDots
import app.haul.util.Pretty
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Screen 17. [queue] is the Library's audio, newest first, so previous and next walk it. */
@Composable
fun AudioPlayerScreen(
    item: DownloadEntity,
    queue: List<DownloadEntity>,
    snack: SnackState,
    onClose: () -> Unit,
    onMore: (DownloadEntity) -> Unit,
) {
    val c = Haul.colors
    val controller = rememberMediaController()
    val ui = rememberPlayerUi(controller)

    // Start this item unless it is already the one playing.
    LaunchedEffect(controller, item.id) {
        val p = controller ?: return@LaunchedEffect
        if (p.currentMediaItem?.mediaId == item.id.toString()) return@LaunchedEffect
        val list = queue.ifEmpty { listOf(item) }
        val start = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        p.setMediaItems(list.map { it.toMediaItem() }, start, 0L)
        p.prepare()
        p.play()
    }
    val current = queue.firstOrNull { it.id.toString() == ui.mediaId } ?: item

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton44(HaulIcon.ChevDown, "Close player", onClose, Modifier.offset(x = (-12).dp))
            T("PLAYING FROM LIBRARY", mono(11.sp, tracking = 0.08f), c.textLow, Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center)
            Box(
                Modifier.offset(x = 12.dp).size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { onMore(current) }
                    .semantics { contentDescription = "More actions" },
                contentAlignment = Alignment.Center,
            ) { MoreDots(c.textHi) }
        }
        Spacer(Modifier.height(20.dp))
        Cover(current)
        Spacer(Modifier.height(28.dp))
        T(current.title.ifBlank { current.fileName.orEmpty() }, Haul.type.title, c.textHi, maxLines = 3)
        if (current.uploader.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            T(current.uploader, sans(15.sp), c.textMid, maxLines = 1)
        }
        Spacer(Modifier.height(24.dp))
        val durMs = ui.duration.takeIf { it > 0 } ?: (current.durationSec * 1000L)
        var scrub by remember { mutableStateOf<Float?>(null) }
        val frac = scrub ?: if (durMs > 0) (ui.position.toFloat() / durMs).coerceIn(0f, 1f) else 0f
        Waveform(
            seed = current.id,
            progress = frac,
            label = "Position ${Pretty.duration((frac * durMs / 1000).toInt()).ifEmpty { "0:00" }} of ${Pretty.duration((durMs / 1000).toInt())}",
            onScrub = { scrub = it },
            onSeek = { f -> scrub = null; controller?.seekTo((f * durMs).toLong()) },
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            val posSec = (frac * durMs / 1000).toInt()
            T(Pretty.duration(posSec).ifEmpty { "0:00" }, mono(11.sp), c.tape, Modifier.weight(1f))
            T("−" + Pretty.duration(((durMs / 1000) - posSec).toInt().coerceAtLeast(0)).ifEmpty { "0:00" }, mono(11.sp), c.textMid)
        }
        Spacer(Modifier.height(20.dp))
        val sleepUntil by PlaybackService.sleepUntil.collectAsState()
        val sleepEnd by PlaybackService.sleepEndOfTrack.collectAsState()
        val sleepOn = sleepUntil > 0 || sleepEnd
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(48.dp, 44.dp).clip(RoundedCornerShape(8.dp)).clickable {
                    controller?.setPlaybackSpeed(nextSpeed(ui.speed))
                }.semantics { contentDescription = "Playback speed ${speedLabel(ui.speed)}" },
                contentAlignment = Alignment.Center,
            ) { T(speedLabel(ui.speed), mono(12.sp, FontWeight.SemiBold), if (ui.speed != 1f) c.tape else c.textMid) }
            IconButton44(
                HaulIcon.PrevFilled, "Previous",
                { controller?.let { if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() } },
                filled = true, size = 52.dp, iconSize = 24.dp,
            )
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.signal)
                    .clickable {
                        controller?.let {
                            if (ui.playing) it.pause() else { if (ui.ended) it.seekTo(0); it.play() }
                        }
                    }
                    .semantics { contentDescription = if (ui.playing) "Pause" else "Play" },
                contentAlignment = Alignment.Center,
            ) {
                if (ui.playing) HIcon(HaulIcon.Pause, c.onSignal, size = 30.dp, stroke = 2.5f)
                else HIcon(HaulIcon.Play, c.onSignal, size = 30.dp, filled = true)
            }
            IconButton44(
                HaulIcon.NextFilled, "Next", { controller?.seekToNextMediaItem() },
                filled = true, size = 52.dp, iconSize = 24.dp, color = if (ui.hasNext) c.textHi else c.disabled,
            )
            val sleepLabel = when {
                sleepEnd -> "Sleep timer: end of track"
                sleepUntil > 0 -> "Sleep timer on"
                else -> "Sleep timer off"
            }
            IconButton44(
                HaulIcon.Moon, sleepLabel,
                {
                    val next = when {
                        !sleepOn -> 15
                        sleepEnd -> 0
                        else -> when (((sleepUntil - System.currentTimeMillis()) / 60_000L + 1).toInt()) {
                            in 0..15 -> 30
                            in 16..30 -> 60
                            else -> -1
                        }
                    }
                    controller?.sendCustomCommand(
                        SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY),
                        Bundle().apply { putInt(PlaybackService.ARG_MINUTES, next) },
                    )
                    snack.show(
                        when (next) {
                            0 -> "Sleep timer off."
                            -1 -> "Stops at the end of this track."
                            else -> "Stops in $next minutes."
                        },
                    )
                },
                color = if (sleepOn) c.tape else c.textMid, iconSize = 22.dp, size = 48.dp,
            )
        }
        Spacer(Modifier.height(28.dp))
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tag("AUDIO", TagKind.Audio)
            T(
                listOf(current.formatLabel, Pretty.bytes(current.fileSize)).filter { it.isNotBlank() && it != "—" }.joinToString(" · "),
                mono(11.sp), c.textMid, Modifier.weight(1f), maxLines = 1,
            )
            T("Keeps playing with screen off", sans(12.sp), c.textLow, maxLines = 1)
        }
    }
}

@Composable
private fun Cover(item: DownloadEntity) {
    val c = Haul.colors
    val ctx = LocalContext.current
    val embedded by produceState<ImageBitmap?>(null, item.fileUri, item.thumbnailUrl) {
        value = if (item.thumbnailUrl != null || item.fileUri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().run {
                    setDataSource(ctx, Uri.parse(item.fileUri))
                    val bytes = embeddedPicture
                    release()
                    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(c.tapeTint, shape)) {
        when {
            item.thumbnailUrl != null -> AsyncImage(item.thumbnailUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            embedded != null -> Image(embedded!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // Two rings, as in the design, when the file has no art.
                val big = maxWidth * (220f / 350f)
                val small = maxWidth * (120f / 350f)
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = maxWidth * (30f / 350f), y = -maxWidth * (30f / 350f))
                        .size(big).border(1.5.dp, c.tape.copy(alpha = 0.35f), RoundedCornerShape(50)),
                )
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = -20.dp, y = 20.dp)
                        .size(small).border(1.5.dp, c.tape.copy(alpha = 0.35f), RoundedCornerShape(50)),
                )
                HIcon(HaulIcon.Audio, c.tape, Modifier.align(Alignment.BottomStart).padding(20.dp), size = 28.dp)
            }
        }
    }
}

/**
 * Seek bar drawn as 64 bars. The shape is a fixed pattern per file (not decoded audio), so it
 * stays cheap; it only shows position.
 */
@Composable
private fun Waveform(seed: Long, progress: Float, label: String, onScrub: (Float) -> Unit, onSeek: (Float) -> Unit) {
    val c = Haul.colors
    val n = 64
    val heights = remember(seed) {
        val phase = (seed % 97) * 0.37
        List(n) { i ->
            val v = abs(sin(i * 0.41 + phase) * 0.7 + sin(i * 1.7 + phase * 2) * 0.3)
            ((6 + (38 * v).roundToInt()).coerceAtMost(44)) / 44f
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                contentDescription = label
                setProgress { onSeek(it.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = { o -> last = (o.x / size.width).coerceIn(0f, 1f); onScrub(last) },
                    onDragEnd = { onSeek(last) },
                    onDragCancel = { onSeek(last) },
                ) { change, _ -> last = (change.position.x / size.width).coerceIn(0f, 1f); onScrub(last) }
            },
    ) {
        val g = 2.dp.toPx()
        val w = (size.width - g * (n - 1)) / n
        val played = (n * progress).roundToInt()
        for (i in 0 until n) {
            val h = size.height * heights[i]
            drawRoundRect(
                if (i < played) c.tape else c.tickOff,
                Offset(i * (w + g), (size.height - h) / 2f),
                Size(w, h),
                CornerRadius(1.dp.toPx()),
            )
        }
    }
}
