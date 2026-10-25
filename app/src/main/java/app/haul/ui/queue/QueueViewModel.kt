package app.haul.ui.queue

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.haul.HaulApp
import app.haul.data.DownloadEntity
import app.haul.data.Status
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class QueueViewModel(app: Application) : AndroidViewModel(app) {
    private val g = HaulApp.graph(app)

    data class Sections(
        val failed: List<DownloadEntity> = emptyList(),
        val active: List<DownloadEntity> = emptyList(),
        val waiting: List<DownloadEntity> = emptyList(),
        val doneToday: List<DownloadEntity> = emptyList(),
    ) {
        val isEmpty get() = failed.isEmpty() && active.isEmpty() && waiting.isEmpty() && doneToday.isEmpty()
        val allPaused get() = active.isNotEmpty() && active.all { it.status == Status.PAUSED } && waiting.isEmpty()
    }

    val sections: StateFlow<Sections> = combine(
        g.dao.observeQueue(),
        g.dao.observeLibrary().map { list -> val t = startOfToday(); list.filter { it.finishedAt >= t } },
    ) { queue, done ->
        Sections(
            failed = queue.filter { it.status == Status.FAILED },
            active = queue.filter { it.isActive },
            waiting = queue.filter { it.isWaiting },
            doneToday = done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Sections())

    /** Items whose technical details are expanded. Open by default, as in the design. */
    val collapsedDetails = mutableStateListOf<Long>()
    var updatingFor by mutableStateOf<Long?>(null)
        private set

    fun pause(id: Long) = g.queue.pause(id)
    fun resume(id: Long) = g.queue.resume(id)
    fun cancel(id: Long) = g.queue.cancel(id)
    fun retry(id: Long) = g.queue.retry(id)
    fun moveToFront(id: Long) = g.queue.moveToFront(id)

    fun togglePauseAll(allPaused: Boolean) {
        if (allPaused) g.queue.resumeAll() else g.queue.pauseAll()
    }

    fun updateEngineAndRetry(id: Long, onResult: (String) -> Unit) {
        if (updatingFor != null) return
        updatingFor = id
        viewModelScope.launch {
            val v = runCatching { g.engine.update(g.settings.current().channel) }
            g.settings.setYtdlpLastCheck(System.currentTimeMillis())
            updatingFor = null
            onResult(
                when {
                    v.isFailure -> "Couldn't update the engine. Retrying anyway."
                    v.getOrNull() != null -> "yt-dlp updated to ${v.getOrNull()}. Retrying."
                    else -> "yt-dlp is already up to date. Retrying."
                },
            )
            g.queue.retry(id)
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
