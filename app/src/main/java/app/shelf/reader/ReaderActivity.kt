package app.shelf.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
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
import app.shelf.data.HighlightEntity
import app.shelf.data.ReaderSettings
import app.shelf.shelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

enum class ReaderPanel { None, Appearance, Contents, Highlight, Selection }

/** Text the reader has selected, shown in the Selection panel. */
class SelectionInfo(val text: String, val locator: Locator, val atTop: Boolean)

/** Compose-observable state for the reader overlay. */
class ReaderUiState(settings: ReaderSettings) {
    var controlsVisible by mutableStateOf(false)
    var panel by mutableStateOf(ReaderPanel.None)
    var locator by mutableStateOf<Locator?>(null)
    var settings by mutableStateOf(settings)
    var highlights by mutableStateOf<List<Highlight>>(emptyList())
    /** The highlight shown in the Highlight panel. */
    var activeHighlightId by mutableStateOf<Long?>(null)
    /** Open the note field with the keyboard up. */
    var editNote by mutableStateOf(false)
    /** Show the Highlight panel at the top, when the highlight is in the lower half of the page. */
    var highlightAtTop by mutableStateOf(false)
    /** The current text selection, while the Selection panel is up. */
    var selection by mutableStateOf<SelectionInfo?>(null)
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
    /** Chrome's selection mode, while text is selected and no handle is being dragged. */
    private var selectionMode: ActionMode? = null
    private var selectionJob: Job? = null

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
                paginationListener = object : EpubNavigatorFragment.PaginationListener {
                    // A chapter finished loading. The first page change can fire before that,
                    // and a script sent then is lost, so send them again here.
                    override fun onPageLoaded() {
                        if (::navigator.isInitialized) lifecycleScope.launch { injectPageScripts() }
                    }
                },
                configuration = readerConfiguration(selectionCallback),
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
                    onOpenHighlight = { goToHighlight(it) },
                    onHighlightColor = { h, color -> setHighlightColor(h, color) },
                    onHighlightNote = { h, note -> setHighlightNote(h, note) },
                    onDeleteHighlight = { deleteHighlight(it) },
                    onCopyHighlight = { copy(it.entity.text) },
                    onShareHighlight = { shareQuote(it.entity.text) },
                    onSelectionColor = { highlightSelection(it, editNote = false) },
                    onSelectionNote = { highlightSelection(null, editNote = true) },
                    onCopySelection = { copySelection() },
                    onShareSelection = { shareSelection() },
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

        // Tapping a highlight opens its panel: colour, note, copy, share, delete.
        navigator.addDecorationListener(HIGHLIGHT_GROUP, object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                val id = event.decoration.id.toLongOrNull() ?: return false
                // Readium calls this on the WebView's JavaScript thread.
                val atTop = event.rect?.let { isLowerHalf(it.centerY()) } ?: false
                runOnUiThread { showHighlight(id, editNote = false, atTop = atTop) }
                return true
            }
        })

        tracker = ReadingTracker(book.bookId, shelf.db.sessions(), lifecycleScope)
        observeLocation()
        observeHighlights()
        applyPageTheme()
        setSystemBarsVisible(false)

        onBackPressedDispatcher.addCallback(this) {
            when {
                // Back clears a selection first, as in other apps.
                ui.panel == ReaderPanel.Selection || selectionMode != null -> hideControls()
                ui.panel == ReaderPanel.Highlight -> hideControls()
                ui.panel != ReaderPanel.None -> ui.panel = ReaderPanel.None
                else -> finish()
            }
        }
    }

    /**
     * Chrome starts a selection mode after a long press and again each time a handle is let go
     * (it ends it while a handle is dragged). Instead of its floating menu, which doesn't come
     * back after a drag in Readium, each settled selection opens Shelf's Selection panel.
     * The menu is left empty so Chrome draws no toolbar.
     */
    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            selectionMode = mode
            refreshSelection()
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false

        override fun onDestroyActionMode(mode: ActionMode) {
            if (selectionMode === mode) selectionMode = null
            refreshSelection()
        }
    }

    /** Shows, updates or closes the Selection panel once the selection has settled. */
    private fun refreshSelection() {
        selectionJob?.cancel()
        selectionJob = lifecycleScope.launch {
            delay(200)
            val selection = navigator.currentSelection()
            val text = selection?.locator?.text?.highlight
            if (selection == null || text.isNullOrBlank()) {
                // The selection was cleared, e.g. by tapping the page.
                if (ui.panel == ReaderPanel.Selection) hideControls()
                return@launch
            }
            // A handle is being dragged: keep the panel as it is until it's let go.
            if (selectionMode == null) return@launch

            val atTop = selection.rect?.let { isLowerHalf(it.centerY()) } ?: false
            ui.selection = SelectionInfo(text, selection.locator, atTop)
            hideJob?.cancel()
            ui.controlsVisible = false
            ui.panel = ReaderPanel.Selection
            setSystemBarsVisible(false)
            overlay.visibility = View.VISIBLE
        }
    }

    private fun isLowerHalf(y: Float) = y > (navigator.view?.height ?: 0) / 2f

    private fun clearSelection() {
        selectionJob?.cancel()
        navigator.clearSelection()
        selectionMode?.finish()
        selectionMode = null
        ui.selection = null
    }

    /**
     * The selection as it is on the page right now. Chrome doesn't always restart its selection
     * mode when a handle is let go, so the copy taken when the panel opened can be out of date.
     */
    private suspend fun liveSelection(): SelectionInfo? {
        val selection = navigator.currentSelection()
        val text = selection?.locator?.text?.highlight
        if (selection == null || text.isNullOrBlank()) return ui.selection
        val atTop = selection.rect?.let { isLowerHalf(it.centerY()) } ?: ui.selection?.atTop ?: false
        return SelectionInfo(text, selection.locator, atTop)
    }

    private fun highlightSelection(color: HighlightColor?, editNote: Boolean) {
        lifecycleScope.launch {
            val selection = liveSelection() ?: return@launch
            clearSelection()
            hideControls()
            addHighlight(selection.locator, color, editNote, atTop = selection.atTop)
        }
    }

    private fun copySelection() {
        lifecycleScope.launch {
            liveSelection()?.let { copy(it.text) }
            hideControls()
        }
    }

    private fun shareSelection() {
        lifecycleScope.launch {
            // A quote that's too long keeps its selection so it can be shortened.
            val selection = liveSelection() ?: return@launch
            if (shareQuote(selection.text)) hideControls()
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(book.title, text))
    }

    /** Opens the Story preview, or explains the limit and returns false if the quote is too long. */
    private fun shareQuote(raw: String): Boolean {
        val quote = QuoteText.clean(raw)
        if (quote.length > QuoteText.MAX_CHARS) {
            Toast.makeText(
                this,
                getString(R.string.quote_too_long, quote.length, QuoteText.MAX_CHARS),
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        startActivity(QuoteShareActivity.intent(this, book.bookId, quote))
        return true
    }

    private fun observeHighlights() {
        lifecycleScope.launch {
            shelf.db.highlights().observeForBook(book.bookId).collect { rows ->
                val highlights = rows.mapNotNull { it.toHighlight() }
                ui.highlights = highlights
                navigator.applyDecorations(highlights.map { it.toDecoration() }, HIGHLIGHT_GROUP)
                // Close the panel if its highlight was deleted.
                if (ui.panel == ReaderPanel.Highlight && highlights.none { it.id == ui.activeHighlightId }) {
                    hideControls()
                }
            }
        }
    }

    private fun addHighlight(locator: Locator, color: HighlightColor?, editNote: Boolean, atTop: Boolean) {
        val text = locator.text.highlight?.trim().orEmpty()
        val publication = book.publication
        lifecycleScope.launch {
            val dao = shelf.db.highlights()
            val now = System.currentTimeMillis()
            val id = dao.insert(
                HighlightEntity(
                    bookId = book.bookId,
                    locatorJson = locator.toJSON().toString(),
                    text = text,
                    color = color?.id ?: dao.lastColor() ?: HighlightColor.Yellow.id,
                    chapter = publication.chapterTitle(locator),
                    sortKey = publication.sortKey(locator),
                    createdAt = now,
                    updatedAt = now,
                )
            )
            if (editNote) showHighlight(id, editNote = true, atTop = atTop)
        }
    }

    private fun showHighlight(id: Long, editNote: Boolean, atTop: Boolean) {
        hideJob?.cancel()
        ui.controlsVisible = false
        ui.activeHighlightId = id
        ui.editNote = editNote
        ui.highlightAtTop = atTop
        ui.panel = ReaderPanel.Highlight
        overlay.visibility = View.VISIBLE
    }

    private fun goToHighlight(highlight: Highlight) {
        navigator.go(highlight.locator, animated = false)
        hideControls()
    }

    private fun setHighlightColor(highlight: Highlight, color: HighlightColor) {
        shelf.appScope.launch {
            shelf.db.highlights().setColor(highlight.id, color.id, System.currentTimeMillis())
        }
    }

    private fun setHighlightNote(highlight: Highlight, note: String) {
        shelf.appScope.launch {
            shelf.db.highlights().setNote(highlight.id, note.trim().ifEmpty { null }, System.currentTimeMillis())
        }
    }

    private fun deleteHighlight(highlight: Highlight) {
        shelf.appScope.launch { shelf.db.highlights().delete(highlight.id) }
    }

    private fun observeLocation() {
        val books = shelf.db.books()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var first = true
                navigator.currentLocator.collect { locator ->
                    ui.locator = locator
                    injectPageScripts()
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

    /** Each chapter is its own WebView; both scripts guard against running twice. */
    private suspend fun injectPageScripts() {
        navigator.evaluateJavascript(SELECTION_SCROLL_LOCK)
        navigator.evaluateJavascript(REPAINT_ONCE)
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

    /** Hides the controls and any panel. Closing the Selection panel also clears the selection. */
    private fun hideControls() {
        if (ui.panel == ReaderPanel.Selection || selectionMode != null) clearSelection()
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

        /**
         * Chrome sometimes paints a chapter from a stale first layout: a list item that runs onto
         * the next page gets drawn over the item after it, though the layout itself is right.
         * Repainting the page once, after the fonts have loaded, draws it where it belongs.
         */
        private val REPAINT_ONCE = """
            (function() {
              if (window.__shelfRepainted) return;
              window.__shelfRepainted = true;
              var frame = function() {
                return new Promise(function(r) { requestAnimationFrame(function() { requestAnimationFrame(r); }); });
              };
              document.fonts.ready.then(frame).then(function() {
                document.body.style.setProperty('opacity', '0.999');
                return frame();
              }).then(function() {
                document.body.style.removeProperty('opacity');
              });
            })();
        """.trimIndent()

        /**
         * Paginated chapters are one wide page of CSS columns. While a selection handle is dragged,
         * Chrome scrolls that page sideways, so the handle stops following the finger and the
         * selection jumps (readium/kotlin-toolkit#325). Hold the page still while text is selected.
         */
        private val SELECTION_SCROLL_LOCK = """
            (function() {
              if (window.__shelfSelectionLock) return;
              window.__shelfSelectionLock = true;
              var page = document.scrollingElement;
              var locked = null;
              document.addEventListener('selectionchange', function() {
                var s = window.getSelection();
                if (s && !s.isCollapsed) { if (locked === null) locked = page.scrollLeft; }
                else locked = null;
              });
              window.addEventListener('scroll', function() {
                if (locked !== null && page.scrollLeft !== locked) page.scrollLeft = locked;
              }, true);
            })();
        """.trimIndent()
    }
}
