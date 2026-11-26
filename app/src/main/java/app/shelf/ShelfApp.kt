package app.shelf

import android.app.Application
import android.content.Context
import android.webkit.WebView
import app.shelf.data.BookSearch
import app.shelf.data.LibraryScanner
import app.shelf.data.ReadiumServices
import app.shelf.data.ReaderSettings
import app.shelf.data.SettingsStore
import app.shelf.data.ShelfDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.shelf.reminder.ReadingReminder
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** The book currently handed to the reader screen. */
class OpenBook(
    val bookId: Long,
    val title: String,
    val publication: Publication,
    val initialLocator: Locator?,
    val positions: List<Locator>,
    val settings: ReaderSettings,
)

class ShelfApp : Application() {
    lateinit var db: ShelfDatabase
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var readium: ReadiumServices
        private set
    lateinit var scanner: LibraryScanner
        private set
    lateinit var bookSearch: BookSearch
        private set

    /** Work that must finish even when a screen closes (saving the last reading session). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var openBook: OpenBook? = null

    override fun onCreate() {
        super.onCreate()
        // Lets Chrome's DevTools inspect the reader page in debug builds.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        db = ShelfDatabase.create(this)
        settings = SettingsStore(this)
        readium = ReadiumServices(this)
        scanner = LibraryScanner(this, db, readium)
        bookSearch = BookSearch(this)

        ReadingReminder.createChannel(this)
        appScope.launch { ReadingReminder.schedule(this@ShelfApp, settings.reminder.first()) }
    }
}

val Context.shelf: ShelfApp get() = applicationContext as ShelfApp
