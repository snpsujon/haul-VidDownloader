package app.haul.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SavedFile(val uri: String, val name: String, val size: Long, val mime: String, val folderLabel: String)

/**
 * Where finished files go. Default is Download/Haul through MediaStore, so gallery, music and
 * file apps can open them. A folder the user picks is written through the Storage Access Framework.
 */
class Storage(private val context: Context) {

    val subfolder = "Haul"

    suspend fun save(src: File, settings: AppSettings): SavedFile = withContext(Dispatchers.IO) {
        val mime = mimeFor(src)
        val tree = settings.saveTreeUri
        val saved = if (tree != null) {
            saveToTree(src, Uri.parse(tree), mime, settings.saveLabel)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(src, mime)
        } else {
            saveToPublicDir(src, mime)
        }
        src.delete()
        saved
    }

    private fun saveToMediaStore(src: File, mime: String): SavedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subfolder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("Could not create the file in Download/$subfolder")
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: error("Could not write the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        val name = queryName(uri) ?: src.name
        return SavedFile(uri.toString(), name, src.length(), mime, "Download/$subfolder")
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicDir(src: File, mime: String): SavedFile {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subfolder)
        dir.mkdirs()
        val dest = uniqueFile(dir, src.name)
        src.copyTo(dest, overwrite = false)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        return SavedFile(Uri.fromFile(dest).toString(), dest.name, dest.length(), mime, "Download/$subfolder")
    }

    private fun saveToTree(src: File, tree: Uri, mime: String, label: String): SavedFile {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: error("The save folder is no longer available")
        val base = src.nameWithoutExtension
        val doc = dir.createFile(mime, base) ?: error("Could not create the file in $label")
        context.contentResolver.openOutputStream(doc.uri)?.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
            ?: error("Could not write the file")
        return SavedFile(doc.uri.toString(), doc.name ?: src.name, src.length(), mime, label)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        while (f.exists()) {
            f = File(dir, "${name.substringBeforeLast('.')} ($i).${name.substringAfterLast('.', "")}")
            i++
        }
        return f
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /** A content:// uri other apps can open. */
    fun shareUri(item: DownloadEntity): Uri? {
        val s = item.fileUri ?: return null
        val u = Uri.parse(s)
        return if (u.scheme == "file") {
            FileProvider.getUriForFile(context, "${context.packageName}.files", File(u.path!!))
        } else u
    }

    fun exists(item: DownloadEntity): Boolean {
        val s = item.fileUri ?: return false
        val u = Uri.parse(s)
        return if (u.scheme == "file") File(u.path!!).exists() else runCatching {
            context.contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    suspend fun delete(item: DownloadEntity): Boolean = withContext(Dispatchers.IO) {
        val s = item.fileUri ?: return@withContext true
        val u = Uri.parse(s)
        runCatching {
            when {
                u.scheme == "file" -> File(u.path!!).delete()
                DocumentsContract.isDocumentUri(context, u) -> DocumentsContract.deleteDocument(context.contentResolver, u)
                else -> context.contentResolver.delete(u, null, null) > 0
            }
        }.getOrDefault(false)
    }

    /** Renames the file, keeping its extension. Returns the new uri and name. */
    suspend fun rename(item: DownloadEntity, newBase: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val s = item.fileUri ?: return@withContext null
        val u = Uri.parse(s)
        val ext = item.fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        val clean = newBase.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { return@withContext null }
        val newName = if (ext != null) "$clean.$ext" else clean
        runCatching {
            when {
                u.scheme == "file" -> {
                    val f = File(u.path!!)
                    val dest = File(f.parentFile, newName)
                    if (f.renameTo(dest)) Uri.fromFile(dest).toString() to newName else null
                }
                DocumentsContract.isDocumentUri(context, u) && u.authority != MediaStore.AUTHORITY -> {
                    val nu = DocumentsContract.renameDocument(context.contentResolver, u, newName)
                    (nu ?: u).toString() to newName
                }
                else -> {
                    val ok = context.contentResolver.update(
                        u, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null,
                    ) > 0
                    if (ok) s to (queryName(u) ?: newName) else null
                }
            }
        }.getOrNull()
    }

    /** Media files already in Download/Haul that the Library does not know about. */
    suspend fun findImportable(known: Set<String>): List<SavedFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<SavedFile>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collections = listOf(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL),
            )
            val seen = HashSet<String>()
            for (c in collections) {
                runCatching {
                    context.contentResolver.query(
                        c,
                        arrayOf(
                            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE,
                        ),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$subfolder%"),
                        null,
                    )?.use { cur ->
                        while (cur.moveToNext()) {
                            val id = cur.getLong(0)
                            val name = cur.getString(1) ?: continue
                            val mime = cur.getString(3) ?: mimeForName(name)
                            if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                            if (!seen.add(name)) continue
                            val uri = android.content.ContentUris.withAppendedId(c, id).toString()
                            if (uri in known) continue
                            out += SavedFile(uri, name, cur.getLong(2), mime, "Download/$subfolder")
                        }
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subfolder)
            dir.listFiles()?.forEach { f ->
                val mime = mimeForName(f.name)
                val uri = Uri.fromFile(f).toString()
                if (f.isFile && (mime.startsWith("video/") || mime.startsWith("audio/")) && uri !in known) {
                    out += SavedFile(uri, f.name, f.length(), mime, "Download/$subfolder")
                }
            }
        }
        out
    }

    companion object {
        fun mimeFor(f: File): String = mimeForName(f.name)

        fun mimeForName(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "opus" -> "audio/ogg"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
        }

        /** "primary:Download/Haul" from a tree uri, for display. */
        fun labelForTree(uri: Uri): String {
            val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
            val path = id.substringAfter(':', id)
            return path.ifEmpty { "Phone storage" }
        }
    }
}
