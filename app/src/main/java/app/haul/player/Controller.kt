package app.haul.player

import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.haul.data.DownloadEntity
import kotlinx.coroutines.delay

/** Connects to [PlaybackService] for as long as the calling screen is shown. */
@Composable
fun rememberMediaController(): MediaController? {
    val ctx = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }
    return controller
}

/** Player state the controls read, refreshed on events and every 250ms while playing. */
class PlayerUi {
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var speed by mutableStateOf(1f)
    var mediaId by mutableStateOf<String?>(null)
    var hasPrev by mutableStateOf(false)
    var hasNext by mutableStateOf(false)
    var ended by mutableStateOf(false)

    fun read(p: Player) {
        playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
        position = p.currentPosition.coerceAtLeast(0)
        duration = p.duration.takeIf { it > 0 } ?: 0
        speed = p.playbackParameters.speed
        mediaId = p.currentMediaItem?.mediaId
        hasPrev = p.hasPreviousMediaItem()
        hasNext = p.hasNextMediaItem()
        ended = p.playbackState == Player.STATE_ENDED
    }
}

@Composable
fun rememberPlayerUi(player: Player?): PlayerUi {
    val ui = remember { PlayerUi() }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = ui.read(p)
        }
        ui.read(player)
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player, ui.playing) {
        while (player != null && ui.playing) {
            ui.read(player)
            delay(250)
        }
    }
    return ui
}

fun DownloadEntity.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(fileUri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title.ifBlank { fileName })
            .setArtist(uploader.ifBlank { null })
            .setArtworkUri(thumbnailUrl?.let(Uri::parse))
            .build(),
    )
    .build()

val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

fun nextSpeed(current: Float): Float {
    val i = SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return SPEEDS[(i + 1) % SPEEDS.size]
}

fun speedLabel(s: Float): String = when {
    kotlin.math.abs(s - s.toInt()) < 0.01f -> "${s.toInt()}.0×"
    else -> "${s.toString().trimEnd('0')}×"
}
