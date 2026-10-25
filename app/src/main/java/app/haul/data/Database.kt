package app.haul.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class MediaType { VIDEO, AUDIO }

enum class Status {
    /** Ready to start when a slot frees. */
    QUEUED,
    /** Queued while offline or (with mobile data off) on mobile data. */
    WAITING_NETWORK,
    DOWNLOADING,
    /** FFmpeg step: merging, converting, embedding. */
    PROCESSING,
    PAUSED,
    FAILED,
    DONE,
}

enum class ErrorKind { EXTRACTOR, NETWORK, STORAGE, UNSUPPORTED, PRIVATE, UNKNOWN }

/**
 * One row per download. A row stays after it finishes and becomes a Library item.
 * The fields under "request" are what the engine needs to (re)start the download.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String = "",
    val uploader: String = "",
    val site: String = "",
    val thumbnailUrl: String? = null,
    val durationSec: Int = 0,
    val mediaType: MediaType,
    /** Format tag as shown, e.g. "1080P MP4" or "MP3 320". */
    val formatLabel: String,

    // request
    val formatSelector: String,
    val container: String = "",
    val audioFormat: String = "",
    val audioBitrate: Int = 0,
    val embedCover: Boolean = false,
    val metaTitle: String? = null,
    val metaArtist: String? = null,
    val estimatedBytes: Long = 0,

    // progress
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBps: Long = 0,
    val etaSec: Long = -1,
    val position: Long = 0,

    // failure
    val errorKind: ErrorKind? = null,
    val errorLog: String? = null,
    val failedAtProgress: Float = 0f,

    // result
    val fileUri: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val mimeType: String? = null,
    val codecs: String? = null,
    val folderLabel: String? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
) {
    val isActive: Boolean
        get() = status == Status.DOWNLOADING || status == Status.PROCESSING || status == Status.PAUSED
    val isWaiting: Boolean get() = status == Status.QUEUED || status == Status.WAITING_NETWORK
}

class Converters {
    @TypeConverter fun fromStatus(s: Status): String = s.name
    @TypeConverter fun toStatus(s: String): Status = Status.valueOf(s)
    @TypeConverter fun fromType(t: MediaType): String = t.name
    @TypeConverter fun toType(s: String): MediaType = MediaType.valueOf(s)
    @TypeConverter fun fromKind(k: ErrorKind?): String? = k?.name
    @TypeConverter fun toKind(s: String?): ErrorKind? = s?.let { ErrorKind.valueOf(it) }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status != 'DONE' ORDER BY position ASC, createdAt ASC")
    fun observeQueue(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'DONE' ORDER BY finishedAt DESC")
    fun observeLibrary(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY position ASC, createdAt ASC")
    suspend fun queued(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','WAITING_NETWORK','DOWNLOADING','PROCESSING')")
    suspend fun pending(): List<DownloadEntity>

    @Query("SELECT id FROM downloads WHERE status = 'PAUSED'")
    suspend fun pausedIds(): List<Long>

    @Query("SELECT fileUri FROM downloads WHERE status = 'DONE' AND fileUri IS NOT NULL")
    suspend fun libraryUris(): List<String>

    @Query("SELECT COALESCE(MAX(position), 0) FROM downloads")
    suspend fun maxPosition(): Long

    @Query("SELECT COALESCE(MIN(position), 0) FROM downloads WHERE status IN ('QUEUED','WAITING_NETWORK')")
    suspend fun minWaitingPosition(): Long

    @Insert suspend fun insert(e: DownloadEntity): Long
    @Update suspend fun update(e: DownloadEntity)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: Status)

    @Query("UPDATE downloads SET status = 'QUEUED' WHERE status IN ('DOWNLOADING','PROCESSING')")
    suspend fun requeueInterrupted()

    @Query("UPDATE downloads SET status = 'QUEUED' WHERE status = 'WAITING_NETWORK'")
    suspend fun releaseWaiting()

    @Query("UPDATE downloads SET status = 'WAITING_NETWORK' WHERE status = 'QUEUED'")
    suspend fun holdQueued()

    @Query("UPDATE downloads SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Long)

    @Query(
        "UPDATE downloads SET progress = :progress, downloadedBytes = :downloaded, totalBytes = :total, " +
            "speedBps = :speed, etaSec = :eta, status = :status " +
            "WHERE id = :id AND status IN ('DOWNLOADING','PROCESSING')"
    )
    suspend fun setProgress(id: Long, progress: Float, downloaded: Long, total: Long, speed: Long, eta: Long, status: Status)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class HaulDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        fun create(context: Context): HaulDatabase =
            Room.databaseBuilder(context, HaulDatabase::class.java, "haul.db").build()
    }
}
