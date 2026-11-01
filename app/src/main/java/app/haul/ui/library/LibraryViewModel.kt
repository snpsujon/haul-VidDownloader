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
import app.haul.engine.Trim
import app.haul.engine.TrimRange
import app.haul.util.Pretty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.MimeTypeMap
import java.io.File
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
    var trimming by mutableStateOf<DownloadEntity?>(null)
    var trimBusy by mutableStateOf(false)
        private set
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

    /** Cuts [range] out of a saved file into "<name> (trim).ext", saved next to it and added to the Library. */
    fun trim(item: DownloadEntity, range: TrimRange, onDone: (String) -> Unit) {
        if (trimBusy) return
        trimBusy = true
        viewModelScope.launch {
            val dir = File(getApplication<Application>().cacheDir, "trim/${item.id}")
            val msg = runCatching {
                val name = item.fileName ?: "${item.title}.${MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mimeType) ?: "mp4"}"
                val output = File(dir, Trim.trimmedName(name))
                val exact = withContext(Dispatchers.IO) {
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val input = File(dir, "source.${name.substringAfterLast('.', "bin")}")
                    val uri = g.storage.shareUri(item) ?: error("missing")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { src ->
                        input.outputStream().use { src.copyTo(it, 1 shl 16) }
                    } ?: error("unreadable")
                    g.engine.trimFile(input, output, range, item.mediaType == MediaType.AUDIO).also { input.delete() }
                }
                val saved = g.storage.save(output, g.settings.current())
                val now = System.currentTimeMillis()
                g.dao.insert(
                    item.copy(
                        id = 0, title = "${item.title.ifBlank { name.substringBeforeLast('.') }} (trim)", durationSec = range.lengthSec,
                        fileUri = saved.uri, fileName = saved.name, fileSize = saved.size, mimeType = saved.mime,
                        folderLabel = saved.folderLabel, trim = range.encode(), createdAt = now, finishedAt = now,
                    ),
                )
                if (exact) "Saved ${saved.name}." else "Saved ${saved.name}. The cuts are at the nearest keyframe."
            }.getOrElse { "Couldn't trim this file. Check that it's still on your phone." }
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            trimBusy = false
            trimming = null
            onDone(msg)
        }
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
