package app.shelf.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shelf.OpenBook
import app.shelf.data.BookEntity
import app.shelf.data.Goals
import app.shelf.data.LibraryLayout
import app.shelf.data.Reminder
import app.shelf.reminder.ReadingReminder
import app.shelf.data.ReadingStats
import app.shelf.data.StatsCalculator
import app.shelf.data.locator
import app.shelf.shelf
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.services.positions

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val shelf = app.shelf

    val books: StateFlow<List<BookEntity>> =
        shelf.db.books().observeLibrary()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val libraryFolder: StateFlow<String?> =
        shelf.settings.libraryTreeUri.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val goals: StateFlow<Goals> =
        shelf.settings.goals.stateIn(viewModelScope, SharingStarted.Eagerly, Goals())

    val layout: StateFlow<LibraryLayout> =
        shelf.settings.layout.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryLayout.GRID)

    val reminder: StateFlow<Reminder> =
        shelf.settings.reminder.stateIn(viewModelScope, SharingStarted.Eagerly, Reminder())

    fun setLayout(layout: LibraryLayout) {
        viewModelScope.launch { shelf.settings.setLayout(layout) }
    }

    fun saveReminder(reminder: Reminder) {
        viewModelScope.launch {
            shelf.settings.saveReminder(reminder)
            ReadingReminder.schedule(getApplication(), reminder)
        }
    }

    /** Opens a book by id (from the reminder notification). */
    fun openById(id: Long, onReady: () -> Unit) {
        viewModelScope.launch {
            val book = shelf.db.books().get(id)
            if (book == null || book.missing) {
                _message.value = "That book is no longer in your library"
                return@launch
            }
            open(book, onReady)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val stats: StateFlow<ReadingStats?> = run {
        val year = LocalDate.now().year
        val zone = ZoneId.systemDefault()
        val from = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val until = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        combine(
            shelf.db.sessions().observeDailyTotals(),
            shelf.settings.goals,
            shelf.db.books().observeFinishedBetween(from, until),
        ) { totals, goals, finished -> StatsCalculator.compute(totals, goals, finished) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    private val _opening = MutableStateFlow(false)
    val opening = _opening.asStateFlow()

    init {
        // Pick up new or removed files every time the app starts.
        viewModelScope.launch {
            shelf.settings.libraryTreeUri.first()?.let { scan(Uri.parse(it), quiet = true) }
        }
    }

    fun onFolderChosen(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch {
            shelf.settings.setLibraryTreeUri(uri.toString())
            scan(uri, quiet = false)
        }
    }

    fun rescan() {
        val uri = libraryFolder.value ?: return
        viewModelScope.launch { scan(Uri.parse(uri), quiet = false) }
    }

    private suspend fun scan(uri: Uri, quiet: Boolean) {
        if (_scanning.value) return
        _scanning.value = true
        try {
            val result = shelf.scanner.scan(uri)
            if (!quiet || result.added > 0 || result.failed > 0) {
                _message.value = buildString {
                    append(
                        when (result.added) {
                            0 -> "No new books"
                            1 -> "Added 1 book"
                            else -> "Added ${result.added} books"
                        }
                    )
                    if (result.failed > 0) append(" · ${result.failed} couldn't be opened")
                }
            }
        } finally {
            _scanning.value = false
        }
    }

    fun setFinished(book: BookEntity, finished: Boolean) {
        viewModelScope.launch {
            shelf.db.books().setFinished(book.id, if (finished) System.currentTimeMillis() else null)
        }
    }

    fun saveGoals(goals: Goals) {
        viewModelScope.launch { shelf.settings.saveGoals(goals) }
    }

    /** Opens the EPUB with Readium and hands it to the reader. Calls [onReady] on success. */
    fun open(book: BookEntity, onReady: () -> Unit) {
        if (_opening.value) return
        _opening.value = true
        viewModelScope.launch {
            try {
                shelf.openBook?.let {
                    it.publication.close()
                    shelf.openBook = null
                }
                val publication = shelf.readium.open(Uri.parse(book.uri))
                if (publication == null) {
                    _message.value = "Couldn't open “${book.title}”. Was the file moved?"
                    return@launch
                }
                val positions = runCatching { publication.positions() }.getOrDefault(emptyList())
                shelf.openBook = OpenBook(
                    bookId = book.id,
                    title = book.title,
                    publication = publication,
                    initialLocator = book.locator(),
                    positions = positions,
                    settings = shelf.settings.reader.first(),
                )
                shelf.db.books().markOpened(book.id, System.currentTimeMillis())
                onReady()
            } finally {
                _opening.value = false
            }
        }
    }
}
