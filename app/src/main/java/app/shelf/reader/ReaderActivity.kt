package app.shelf.reader

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.shelf.OpenBook
import app.shelf.R
import app.shelf.data.ReaderSettings
import app.shelf.shelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

enum class ReaderPanel { None, Appearance, Contents }

/** Compose-observable state for the reader overlay. */
class ReaderUiState(settings: ReaderSettings) {
    var controlsVisible by mutableStateOf(false)
    var panel by mutableStateOf(ReaderPanel.None)
    var locator by mutableStateOf<Locator?>(null)
    var settings by mutableStateOf(settings)
}

@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : AppCompatActivity() {

    private lateinit var book: OpenBook
    private lateinit var navigator: EpubNavigatorFragment
    private lateinit var tracker: ReadingTracker
    private lateinit var ui: ReaderUiState
    private lateinit var root: FrameLayout
    private lateinit var overlay: ComposeView
    private var hideJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val openBook = shelf.openBook
        if (openBook == null) {
            // The app process was restarted while reading: go back to the library.
            supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        book = openBook
        ui = ReaderUiState(openBook.settings)

        supportFragmentManager.fragmentFactory =
            EpubNavigatorFactory(openBook.publication).createFragmentFactory(
                initialLocator = openBook.initialLocator,
                initialPreferences = openBook.settings.toEpubPreferences(),
                configuration = readerConfiguration(),
            )

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep the screen on while a book is open. The flag belongs to this window only,
        // so it no longer applies once the reader closes or goes to the background.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        val container = FrameLayout(this).apply { id = R.id.reader_container }
        root.addView(container, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlay = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                ReaderControls(
                    state = ui,
                    title = book.title,
                    positions = book.positions,
                    tableOfContents = book.publication.tableOfContents,
                    onClose = { finish() },
                    onHide = { hideControls() },
                    onSeek = { index -> goToPosition(index) },
                    onOpenLink = { link -> goToLink(link) },
                    onSettingsChange = { updateSettings(it) },
                )
            }
        }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        // Keep the page area the same size whether or not the system bars are showing,
        // so text never reflows when the controls appear.
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.commitNow {
                add(R.id.reader_container, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
            }
        }
        navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as EpubNavigatorFragment

        // Left / right 30% of the page turn pages with Readium's slide; anything else shows the controls.
        navigator.addInputListener(DirectionalNavigationAdapter(navigator, animatedTransition = true))
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                if (ui.controlsVisible) hideControls() else showControls()
                return true
            }
        })

        tracker = ReadingTracker(book.bookId, shelf.db.sessions(), lifecycleScope)
        observeLocation()
        applyPageTheme()
        setSystemBarsVisible(false)

        onBackPressedDispatcher.addCallback(this) {
            if (ui.panel != ReaderPanel.None) ui.panel = ReaderPanel.None else finish()
        }
    }

    private fun observeLocation() {
        val books = shelf.db.books()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var first = true
                navigator.currentLocator.collect { locator ->
                    ui.locator = locator
                    if (first) first = false else tracker.onPageTurn()

                    val progress = locator.locations.totalProgression ?: 0.0
                    shelf.appScope.launch {
                        books.savePosition(book.bookId, locator.toJSON().toString(), progress)
                        val position = locator.locations.position
                        val atEnd = progress >= 0.995 ||
                            (position != null && book.positions.isNotEmpty() && position >= book.positions.size)
                        if (atEnd) books.setFinishedIfNot(book.bookId, System.currentTimeMillis())
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::tracker.isInitialized) tracker.start()
    }

    override fun onStop() {
        super.onStop()
        if (::tracker.isInitialized) {
            shelf.appScope.launch(Dispatchers.Main.immediate) { tracker.stop() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && ::book.isInitialized) {
            book.publication.close()
            if (shelf.openBook === book) shelf.openBook = null
        }
    }

    private fun showControls() {
        hideJob?.cancel()
        overlay.visibility = View.VISIBLE
        ui.controlsVisible = true
        setSystemBarsVisible(true)
    }

    private fun hideControls() {
        ui.controlsVisible = false
        ui.panel = ReaderPanel.None
        setSystemBarsVisible(false)
        hideJob?.cancel()
        hideJob = lifecycleScope.launch {
            delay(260) // let the exit animation finish
            if (!ui.controlsVisible) overlay.visibility = View.GONE
        }
    }

    private fun goToPosition(index: Int) {
        book.positions.getOrNull(index)?.let { navigator.go(it, animated = false) }
    }

    private fun goToLink(link: Link) {
        navigator.go(link, animated = false)
        hideControls()
    }

    private fun updateSettings(settings: ReaderSettings) {
        ui.settings = settings
        navigator.submitPreferences(settings.toEpubPreferences())
        applyPageTheme()
        shelf.appScope.launch { shelf.settings.saveReader(settings) }
    }

    private fun applyPageTheme() {
        val theme = PageThemes.byId(ui.settings.themeId)
        root.setBackgroundColor(theme.background.toArgb())
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = !theme.isDark
            isAppearanceLightNavigationBars = !theme.isDark
        }
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (visible) show(WindowInsetsCompat.Type.systemBars())
            else hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        private const val NAVIGATOR_TAG = "navigator"
    }
}
