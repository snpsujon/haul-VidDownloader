package app.haul.player

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.haul.HaulApp
import app.haul.data.DownloadEntity
import app.haul.ui.components.ButtonKind
import app.haul.ui.components.DetailRow
import app.haul.ui.components.Divider
import app.haul.ui.components.HButton
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
import app.haul.util.Pretty
import kotlinx.coroutines.delay

/** Screen 16. Video plays in this screen only; leaving it stops playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    item: DownloadEntity,
    snack: SnackState,
    onClose: () -> Unit,
    onDelete: (DownloadEntity) -> Unit,
    onRegrab: (String) -> Unit,
) {
    val c = Haul.colors
    val ctx = LocalContext.current
    val player = remember(item.id) {
        ExoPlayer.Builder(ctx)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(item.fileUri)))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val ui = rememberPlayerUi(player)
    var fullscreen by remember { mutableStateOf(false) }
    FullscreenEffect(fullscreen)
    BackHandler(fullscreen) { fullscreen = false }

    var controlsShown by remember { mutableStateOf(true) }
    var poke by remember { mutableStateOf(0) }
    LaunchedEffect(controlsShown, ui.playing, poke) {
        if (controlsShown && ui.playing) { delay(3000); controlsShown = false }
    }

    val video: @Composable (Modifier) -> Unit = { m ->
        Box(
            m.background(Color.Black)
                .clickable(remember { MutableInteractionSource() }, null) { controlsShown = !controlsShown },
        ) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            if (controlsShown || !ui.playing) {
                Controls(
                    ui = ui,
                    fullscreen = fullscreen,
                    onBack = { if (fullscreen) fullscreen = false else onClose() },
                    onSpeed = { player.setPlaybackSpeed(nextSpeed(ui.speed)); poke++ },
                    onToggle = { if (ui.playing) player.pause() else { if (ui.ended) player.seekTo(0); player.play() }; poke++ },
                    onSeekBy = { d -> player.seekTo((player.currentPosition + d).coerceIn(0, player.duration.coerceAtLeast(0))); poke++ },
                    onSeekTo = { f -> player.seekTo((f * ui.duration).toLong()); poke++ },
                    onFullscreen = { fullscreen = !fullscreen },
                )
            }
        }
    }

    if (fullscreen) {
        video(Modifier.fillMaxSize())
        return
    }
    Column(Modifier.fillMaxSize()) {
        video(Modifier.fillMaxWidth().height(219.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        ) {
            T(item.title.ifBlank { item.fileName.orEmpty() }, Haul.type.title.copy(fontSize = 22.sp, lineHeight = 27.sp), c.textHi, maxLines = 3)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("VIDEO", TagKind.Video)
                if (item.formatLabel.isNotBlank()) Tag(item.formatLabel, TagKind.Neutral)
                if (item.uploader.isNotBlank()) T(item.uploader, sans(13.sp), c.textLow, maxLines = 1)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val storage = HaulApp.graph(ctx).storage
                Tile(HaulIcon.Share, "Share", c.textHi, Modifier.weight(1f)) {
                    storage.shareUri(item)?.let { u ->
                        ctx.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType(item.mimeType ?: "video/*").putExtra(Intent.EXTRA_STREAM, u)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                "Share file",
                            ),
                        )
                    }
                }
                Tile(HaulIcon.External, "Open with", c.textHi, Modifier.weight(1f)) {
                    storage.shareUri(item)?.let { u ->
                        player.pause()
                        try {
                            ctx.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(u, item.mimeType ?: "video/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                    "Open with",
                                ),
                            )
                        } catch (_: ActivityNotFoundException) { snack.show("No app on this phone can open this file.") }
                    }
                }
                Tile(HaulIcon.Folder, "Folder", c.textHi, Modifier.weight(1f)) {
                    try {
                        ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) { snack.show("Saved in ${item.folderLabel ?: "Download/Haul"}.") }
                }
                Tile(HaulIcon.Delete, "Delete", c.err, Modifier.weight(1f)) { player.pause(); onDelete(item) }
            }
            Spacer(Modifier.height(20.dp))
            Divider()
            DetailRow("Saved", Pretty.savedAt(item.finishedAt))
            DetailRow("File", listOfNotNull(Pretty.bytes(item.fileSize), item.codecs?.takeIf { it.isNotBlank() }?.uppercase()).joinToString(" · "))
            DetailRow("Location", item.folderLabel ?: "Download/Haul")
            if (item.url.isNotBlank()) {
                DetailRow("Original link", Pretty.shortUrl(item.url)) {
                    IconButton44(
                        HaulIcon.Copy, "Copy link",
                        {
                            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Link", item.url))
                            snack.show("Link copied.")
                        },
                        color = c.signal, size = 40.dp, iconSize = 18.dp,
                    )
                }
                Spacer(Modifier.height(20.dp))
                HButton(
                    "Save again as audio or other quality",
                    { player.pause(); onRegrab(item.url) },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.Secondary,
                )
            }
        }
    }
}

@Composable
private fun Controls(
    ui: PlayerUi,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onSpeed: () -> Unit,
    onToggle: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Float) -> Unit,
    onFullscreen: () -> Unit,
) {
    val c = Haul.colors
    Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.45f))) {
        IconButton44(
            if (fullscreen) HaulIcon.Back else HaulIcon.ChevDown, if (fullscreen) "Exit full screen" else "Back", onBack,
            Modifier.align(Alignment.TopStart).padding(4.dp), color = c.textHi,
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.scrim.copy(alpha = 0.6f))
                .clickable(onClick = onSpeed)
                .padding(horizontal = 10.dp)
                .semantics { contentDescription = "Playback speed ${speedLabel(ui.speed)}" },
            contentAlignment = Alignment.Center,
        ) { T(speedLabel(ui.speed), mono(12.sp, FontWeight.SemiBold), Color(0xFFF3EFE6)) }
        Row(
            Modifier.align(Alignment.Center).offset(y = if (fullscreen) 0.dp else (-10).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            IconButton44(HaulIcon.Retry, "Back 10 seconds", { onSeekBy(-10_000) }, size = 48.dp, color = Color(0xFFF3EFE6))
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(c.signal).clickable(onClick = onToggle)
                    .semantics { contentDescription = if (ui.playing) "Pause" else "Play" },
                contentAlignment = Alignment.Center,
            ) {
                if (ui.playing) HIcon(HaulIcon.Pause, c.onSignal, size = 28.dp, stroke = 2.5f)
                else HIcon(HaulIcon.Play, c.onSignal, size = 28.dp, filled = true)
            }
            IconButton44(HaulIcon.Forward, "Forward 10 seconds", { onSeekBy(10_000) }, size = 48.dp, color = Color(0xFFF3EFE6))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SeekBar(if (ui.duration > 0) ui.position.toFloat() / ui.duration else 0f, onSeekTo)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bone = Color(0xFFF3EFE6)
                T(Pretty.duration((ui.position / 1000).toInt()).ifEmpty { "0:00" }, mono(11.sp), bone, Modifier.weight(1f))
                T(Pretty.duration((ui.duration / 1000).toInt()).ifEmpty { "0:00" }, mono(11.sp), bone)
                Spacer(Modifier.width(4.dp))
                IconButton44(HaulIcon.Fullscreen, if (fullscreen) "Exit full screen" else "Full screen", onFullscreen, size = 32.dp, iconSize = 18.dp, color = bone)
            }
        }
    }
}

@Composable
private fun SeekBar(progress: Float, onSeek: (Float) -> Unit) {
    val c = Haul.colors
    var drag by remember { mutableStateOf<Float?>(null) }
    val p = (drag ?: progress).coerceIn(0f, 1f)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f)
                setProgress { onSeek(it); true }
            }
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(onSeek); drag = null },
                    onDragCancel = { drag = null },
                ) { ch, _ -> drag = (ch.position.x / size.width).coerceIn(0f, 1f) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val track = RoundedCornerShape(2.dp)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(track).background(Color(0xFFF3EFE6).copy(alpha = 0.25f)))
        Box(Modifier.fillMaxWidth(p).height(4.dp).clip(track).background(c.signal))
        val x: Dp = maxWidth * p - 8.dp
        Box(
            Modifier.offset(x = x).size(16.dp).clip(RoundedCornerShape(8.dp)).background(c.signal)
                .border(3.dp, c.onSignal, RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun Tile(icon: HaulIcon, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = Haul.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.height(68.dp).clip(shape).background(c.surface1, shape).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HIcon(icon, color, size = 22.dp)
        T(label, sans(12.sp, FontWeight.Medium), color, maxLines = 1)
    }
}

@Composable
private fun FullscreenEffect(on: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(on) {
        val window = activity.window
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (on) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ctl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}
