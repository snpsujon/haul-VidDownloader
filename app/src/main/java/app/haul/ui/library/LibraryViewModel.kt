package app.haul.ui.library

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.haul.HaulApp
import app.haul.data.DownloadEntity
import app.haul.data.MediaType
import app.haul.data.SavedFile
import app.haul.data.Status
import app.haul.util.Pretty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

enum class LibrarySort(val label: String) {
    Newest("Newest first"), Oldest("Oldest first"), Largest("Largest first"), Title("Title, A to Z")
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val g = HaulApp.graph(app)

    val items: StateFlow<List<DownloadEntity>?> = g.dao.observeLibrary()
        .map<List<DownloadEntity>, List<DownloadEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var filter by mutableStateOf<MediaType?>(null)
    var sort by mutableStateOf(LibrarySort.Newest)
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var sortSheet by mutableStateOf(false)
    var actionsFor by mutableStateOf<DownloadEntity?>(null)
    var renaming by mutableStateOf<DownloadEntity?>(null)
    var deleting by mutableStateOf<DownloadEntity?>(null)
    var importing by mutableStateOf(false)
        private set

    data class Group(val label: String?, val items: List<DownloadEntity>)

    fun visible(all: List<DownloadEntity>): List<DownloadEntity> {
        val q = query.trim().lowercase()
        val list = all.filter { filter == null || it.mediaType == filter }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.uploader.lowercase().contains(q) }
        return when (sort) {
            LibrarySort.Newest -> list.sortedByDescending { it.finishedAt }
            LibrarySort.Oldest -> list.sortedBy { it.finishedAt }
            LibrarySort.Largest -> list.sortedByDescending { it.fileSize }
            LibrarySort.Title -> list.sortedBy { it.title.lowercase() }
        }
    }

    /** Date groups only make sense when sorted by date. */
    fun groups(list: List<DownloadEntity>): List<Group> {
        if (sort != LibrarySort.Newest && sort != LibrarySort.Oldest) return listOf(Group(null, list))
        val now = Calendar.getInstance()
        val out = LinkedHashMap<String, MutableList<DownloadEntity>>()
        list.forEach {
            val cal = Calendar.getInstance().apply { timeInMillis = it.finishedAt }
            val days = Pretty.daysBetween(cal, now)
            val label = when {
                days == 0 -> "TODAY"
                days < 7 -> "THIS WEEK"
                days < 31 -> "THIS MONTH"
                cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> "EARLIER THIS YEAR"
                else -> cal.get(Calendar.YEAR).toString()
            }
            out.getOrPut(label) { mutableListOf() } += it
        }
        return out.map { (k, v) -> Group(k, v) }
    }

    fun exists(item: DownloadEntity) = g.storage.exists(item)

    fun delete(item: DownloadEntity, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = g.storage.delete(item)
        g.dao.delete(item.id)
        onDone(ok)
    }

    fun forget(item: DownloadEntity) = viewModelScope.launch { g.dao.delete(item.id) }

    fun rename(item: DownloadEntity, name: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val r = g.storage.rename(item, name)
        if (r != null) g.dao.update(item.copy(fileUri = r.first, fileName = r.second, title = name.trim()))
        onDone(r != null)
    }

    /** Adds media already in Download/Haul (from an earlier install) to the Library. */
    fun importExisting(onDone: (Int) -> Unit) {
        if (importing) return
        importing = true
        viewModelScope.launch {
            val known = g.dao.libraryUris().toSet()
            val found = runCatching { g.storage.findImportable(known) }.getOrDefault(emptyList())
            found.forEach { g.dao.insert(entityFor(it)) }
            importing = false
            onDone(found.size)
        }
    }

    private suspend fun entityFor(f: SavedFile): DownloadEntity = withContext(Dispatchers.IO) {
        val audio = f.mime.startsWith("audio/")
        var title = f.name.substringBeforeLast('.').replace(Regex("""\s*\[[^\]]+]$"""), "")
        var artist = ""
        var duration = 0
        var height = 0
        runCatching {
            MediaMetadataRetriever().apply {
                setDataSource(getApplication(), Uri.parse(f.uri))
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                artist = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                duration = (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L).div(1000).toInt()
                height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                release()
            }
        }
        val ext = f.name.substringAfterLast('.', "").uppercase()
        val label = if (!audio && height > 0) "${height}P $ext" else ext
        DownloadEntity(
            url = "",
            title = title,
            uploader = artist,
            durationSec = duration,
            mediaType = if (audio) MediaType.AUDIO else MediaType.VIDEO,
            formatLabel = label,
            formatSelector = "",
            status = Status.DONE,
            progress = 1f,
            fileUri = f.uri,
            fileName = f.name,
            fileSize = f.size,
            mimeType = f.mime,
            folderLabel = f.folderLabel,
            finishedAt = System.currentTimeMillis(),
        )
    }
}
