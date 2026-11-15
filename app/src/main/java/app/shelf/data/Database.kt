package app.shelf.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One row per EPUB file found in the library folder.
 * The file itself is never copied: [uri] points at it in shared storage.
 */
@Entity(tableName = "books", indices = [Index(value = ["uri"], unique = true)])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val fileName: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    /** Readium Locator as JSON: the exact place you stopped reading. */
    val locatorJson: String? = null,
    /** 0.0 – 1.0 through the whole book. */
    val progress: Double = 0.0,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    val finishedAt: Long? = null,
    /** True when the file is no longer in the folder. Kept so stats survive. */
    val missing: Boolean = false,
)

/** A stretch of active reading. Daily minutes, streaks and goals are computed from these. */
@Entity(
    tableName = "reading_sessions",
    indices = [Index("bookId"), Index("day")],
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** Local calendar day the session started on, as LocalDate.toEpochDay(). */
    val day: Long,
    val startedAt: Long,
    val endedAt: Long,
    val activeSeconds: Long,
    val pagesTurned: Int,
)

data class DayTotal(
    @ColumnInfo(name = "day") val day: Long,
    @ColumnInfo(name = "seconds") val seconds: Long,
)

@Dao
interface BookDao {
    @Query(
        """SELECT * FROM books WHERE missing = 0
           ORDER BY CASE WHEN lastOpenedAt IS NULL THEN 1 ELSE 0 END, lastOpenedAt DESC, addedAt DESC"""
    )
    fun observeLibrary(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    /** The book the reader was last in, if it isn't finished. */
    @Query(
        """SELECT * FROM books WHERE missing = 0 AND finishedAt IS NULL AND lastOpenedAt IS NOT NULL
           ORDER BY lastOpenedAt DESC LIMIT 1"""
    )
    suspend fun lastReading(): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: BookEntity): Long

    @Query("UPDATE books SET missing = :missing WHERE id = :id")
    suspend fun setMissing(id: Long, missing: Boolean)

    @Query("UPDATE books SET locatorJson = :locatorJson, progress = :progress WHERE id = :id")
    suspend fun savePosition(id: Long, locatorJson: String, progress: Double)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id")
    suspend fun markOpened(id: Long, time: Long)

    @Query("UPDATE books SET finishedAt = :time WHERE id = :id")
    suspend fun setFinished(id: Long, time: Long?)

    @Query("UPDATE books SET finishedAt = :time WHERE id = :id AND finishedAt IS NULL")
    suspend fun setFinishedIfNot(id: Long, time: Long)

    @Query("SELECT COUNT(*) FROM books WHERE finishedAt >= :from AND finishedAt < :until")
    fun observeFinishedBetween(from: Long, until: Long): Flow<Int>
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: ReadingSessionEntity): Long

    @Query(
        "UPDATE reading_sessions SET endedAt = :endedAt, activeSeconds = :activeSeconds, pagesTurned = :pagesTurned WHERE id = :id"
    )
    suspend fun update(id: Long, endedAt: Long, activeSeconds: Long, pagesTurned: Int)

    @Query("SELECT day, SUM(activeSeconds) AS seconds FROM reading_sessions GROUP BY day ORDER BY day")
    fun observeDailyTotals(): Flow<List<DayTotal>>

    @Query("SELECT COALESCE(SUM(activeSeconds), 0) FROM reading_sessions WHERE day = :day")
    suspend fun secondsOn(day: Long): Long
}

@Database(
    entities = [BookEntity::class, ReadingSessionEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ShelfDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun sessions(): SessionDao

    companion object {
        fun create(context: Context): ShelfDatabase =
            Room.databaseBuilder(context, ShelfDatabase::class.java, "shelf.db").build()
    }
}
