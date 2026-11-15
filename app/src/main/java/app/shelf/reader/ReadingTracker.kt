package app.shelf.reader

import app.shelf.data.ReadingSessionEntity
import app.shelf.data.SessionDao
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Counts active reading time while the reader is on screen.
 *
 * A second counts as reading when the last page turn was less than [idleTimeoutMs] ago,
 * so leaving the phone open on a page stops counting after 2 minutes.
 * The session is written to the database every [flushEverySeconds] and on [stop],
 * so a crash loses at most that much time.
 */
class ReadingTracker(
    private val bookId: Long,
    private val sessions: SessionDao,
    private val scope: CoroutineScope,
    private val idleTimeoutMs: Long = 2 * 60 * 1000L,
    private val flushEverySeconds: Long = 30,
) {
    private val mutex = Mutex()
    private var ticker: Job? = null

    private var sessionId: Long? = null
    private var startedAt = 0L
    private var day = 0L
    private var activeSeconds = 0L
    private var pagesTurned = 0
    private var lastActivityAt = 0L

    /** Call when the reader becomes visible. */
    fun start() {
        if (ticker?.isActive == true) return
        lastActivityAt = System.currentTimeMillis()
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                if (System.currentTimeMillis() - lastActivityAt <= idleTimeoutMs) {
                    activeSeconds++
                    if (activeSeconds % flushEverySeconds == 0L) flush()
                }
            }
        }
    }

    fun onPageTurn() {
        pagesTurned++
        lastActivityAt = System.currentTimeMillis()
    }

    /** Call when the reader leaves the screen. Saves and closes the current session. */
    suspend fun stop() {
        ticker?.cancel()
        ticker = null
        flush()
        mutex.withLock {
            sessionId = null
            activeSeconds = 0
            pagesTurned = 0
        }
    }

    private suspend fun flush() = mutex.withLock {
        if (activeSeconds == 0L) return@withLock
        val now = System.currentTimeMillis()
        val id = sessionId
        if (id == null) {
            startedAt = now - activeSeconds * 1000
            day = LocalDate.now().toEpochDay()
            sessionId = sessions.insert(
                ReadingSessionEntity(
                    bookId = bookId,
                    day = day,
                    startedAt = startedAt,
                    endedAt = now,
                    activeSeconds = activeSeconds,
                    pagesTurned = pagesTurned,
                )
            )
        } else {
            sessions.update(id, endedAt = now, activeSeconds = activeSeconds, pagesTurned = pagesTurned)
        }
    }
}
