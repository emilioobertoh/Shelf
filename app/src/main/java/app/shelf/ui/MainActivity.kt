package app.shelf.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.shelf.reader.ReaderActivity
import app.shelf.reminder.ReadingReminder
import app.shelf.shelf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: LibraryViewModel by viewModels()
    private val readingList: ReadingListViewModel by viewModels()

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShelfTheme {
                var showList by rememberSaveable { mutableStateOf(false) }
                if (showList) {
                    ReadingListScreen(
                        viewModel = readingList,
                        onBack = { showList = false },
                        onOpenBook = { viewModel.open(it, ::startReader) },
                    )
                } else {
                    LibraryScreen(
                        viewModel = viewModel,
                        readingList = readingList,
                        onOpenReadingList = { showList = true },
                        onOpenReader = ::startReader,
                    )
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
        askForNotificationsOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun startReader() = startActivity(Intent(this, ReaderActivity::class.java))

    private fun handleIntent(intent: Intent) {
        // Debug builds only: `adb shell am start -n app.shelf/.ui.MainActivity --ez debug_reminder true`
        // posts the morning reminder right away.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent.getBooleanExtra("debug_reminder", false)) {
            intent.removeExtra("debug_reminder")
            shelf.appScope.launch { ReadingReminder.show(applicationContext, force = true) }
            moveTaskToBack(true)
            return
        }

        if (intent.action != ReadingReminder.ACTION_OPEN_BOOK) return
        val bookId = intent.getLongExtra(ReadingReminder.EXTRA_BOOK_ID, -1)
        intent.action = null // don't reopen the book after a rotation or back navigation
        if (bookId > 0) viewModel.openById(bookId, ::startReader)
    }

    /** Asks for notification permission the first time, so the morning reminder can be shown. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || ReadingReminder.canNotify(this)) return
        lifecycleScope.launch {
            val settings = shelf.settings
            if (settings.askedNotifications.first() || !settings.reminder.first().enabled) return@launch
            settings.setAskedNotifications()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
