package app.shelf.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shelf.data.BookEntity
import app.shelf.data.BookResult
import app.shelf.data.ReadingListEntity
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Want to Read: search Open Library by title, author or ISBN and keep a list of books to get. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingListScreen(
    viewModel: ReadingListViewModel,
    onBack: () -> Unit,
    onOpenBook: (BookEntity) -> Unit,
) {
    val entries by viewModel.entries.collectAsState()
    val search by viewModel.search.collectAsState()
    val saved by viewModel.savedKeys.collectAsState()
    val adding by viewModel.adding.collectAsState()
    val message by viewModel.message.collectAsState()
    var selected by remember { mutableStateOf<ListEntry?>(null) }
    val focus = LocalFocusManager.current

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    BackHandler {
        if (search.query.isNotEmpty()) viewModel.setQuery("") else onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Library")
                }
                Text("Want to Read", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            TextField(
                value = search.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Title, author or ISBN") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    when {
                        search.loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        search.query.isNotEmpty() -> IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    viewModel.searchNow()
                    focus.clearFocus()
                }),
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Accent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            )

            if (search.query.isNotEmpty()) {
                SearchResults(search, saved, adding, onAdd = viewModel::add)
            } else {
                SavedList(entries, onSelect = { selected = it }, onOpenBook = onOpenBook)
            }
        }
    }

    selected?.let { entry ->
        // Keep the sheet in step with the list (e.g. the book turned up in the library).
        val current = entries.firstOrNull { it.entity.id == entry.entity.id } ?: entry
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            EntryDetails(
                current,
                onRead = { current.inLibrary?.let(onOpenBook); selected = null },
                onRemove = { viewModel.remove(current.entity); selected = null },
            )
        }
    }
}

@Composable
private fun SearchResults(
    search: SearchState,
    saved: Set<String>,
    adding: Set<String>,
    onAdd: (BookResult) -> Unit,
) {
    when {
        search.error != null -> Hint(search.error)
        search.searched && search.results.isEmpty() -> Hint("No books found for “${search.query.trim()}”.")
        else -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            items(search.results, key = { it.key }) { result ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(null, result.thumbnailUrl, result.title, Modifier.width(52.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(result.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                        if (result.authors.isNotEmpty()) {
                            Text(result.authors.joinToString(", "), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        facts(result.year, result.pages)?.let {
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    val isSaved = result.key in saved
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isSaved) MaterialTheme.colorScheme.surfaceVariant else Accent)
                            .clickable(enabled = !isSaved && result.key !in adding) { onAdd(result) },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            result.key in adding -> CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            isSaved -> Icon(Icons.Filled.Check, contentDescription = "On your list", tint = Accent, modifier = Modifier.size(20.dp))
                            else -> Icon(Icons.Filled.Add, contentDescription = "Add to Want to Read", tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            }
            if (search.results.isNotEmpty()) item {
                Text(
                    if (search.results.any { it.key.startsWith("gb:") }) "Results from Google Books" else "Results from Open Library",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SavedList(entries: List<ListEntry>, onSelect: (ListEntry) -> Unit, onOpenBook: (BookEntity) -> Unit) {
    if (entries.isEmpty()) {
        Hint("Search for books you'd like to read by title, author or ISBN, and tap + to add them here.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        items(entries, key = { it.entity.id }) { entry ->
            val e = entry.entity
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(entry) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(e.coverPath, null, e.title, Modifier.width(56.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                    e.authors?.let {
                        Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    facts(e.year, e.pages)?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                entry.inLibrary?.let { book ->
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Accent)
                            .clickable { onOpenBook(book) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) { Text("Read", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun EntryDetails(entry: ListEntry, onRead: () -> Unit, onRemove: () -> Unit) {
    val e = entry.entity
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        Row {
            BookCover(e.coverPath, null, e.title, Modifier.width(104.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                e.authors?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                listOfNotNull(facts(e.year, e.pages), e.isbn?.let { "ISBN $it" }).forEach {
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (entry.inLibrary != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("In your library", fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        e.description?.let {
            var expanded by remember(e.id) { mutableStateOf(false) }
            var overflows by remember(e.id) { mutableStateOf(false) }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.clickable(enabled = overflows || expanded) { expanded = !expanded }) {
                Text(
                    it,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 7,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout -> if (!expanded) overflows = layout.hasVisualOverflow },
                )
                if (overflows || expanded) {
                    Text(
                        if (expanded) "Less" else "More",
                        color = Accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (entry.inLibrary != null) {
                Button(onClick = onRead, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) { Text("Read Now") }
            }
            OutlinedButton(onClick = onRemove) { Text("Remove from Want to Read") }
        }
    }
}

/** The Want to Read row on the library screen. */
@Composable
fun WantToReadRow(entries: List<ListEntry>, onSeeAll: () -> Unit, onOpenBook: (BookEntity) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSeeAll).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Want to Read", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (entries.isEmpty()) "Find Books ›" else "See All ›", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        if (entries.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onSeeAll),
            ) {
                Text(
                    "Keep a list of books to get next. Search by title, author or ISBN.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(18.dp),
                )
            }
            return
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries.take(12), key = { it.entity.id }) { entry ->
                Column(
                    Modifier.width(92.dp).clickable {
                        val book = entry.inLibrary
                        if (book != null) onOpenBook(book) else onSeeAll()
                    },
                ) {
                    BookCover(entry.entity.coverPath, null, entry.entity.title, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(entry.entity.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                    if (entry.inLibrary != null) {
                        Text("In Library", fontSize = 11.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
    )
}

private fun facts(year: Int?, pages: Int?): String? =
    listOfNotNull(year?.toString(), pages?.let { "$it pages" }).joinToString(" · ").ifBlank { null }

/** Search thumbnails already fetched, so scrolling back doesn't download them again. */
private val thumbnails = LruCache<String, ImageBitmap>(80)

/** A cover from a saved file or a URL, with a plain generated cover while loading or if there is none. */
@Composable
private fun BookCover(path: String?, url: String?, title: String, modifier: Modifier) {
    val bitmap by produceState(url?.let { thumbnails.get(it) }, path, url) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            when {
                path != null -> BitmapFactory.decodeFile(path)?.asImageBitmap()
                url != null -> runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    try {
                        conn.inputStream.use { BitmapFactory.decodeStream(it) }
                            ?.takeIf { it.width > 2 } // Open Library's "no cover" is a 1×1 image
                            ?.asImageBitmap()
                            ?.also { thumbnails.put(url, it) }
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
                else -> null
            }
        }
    }
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .shadow(4.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFFD9DEE6), Color(0xFF9AA6B8))))
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(title, color = Color(0xFF26303D), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp)
            }
        }
    }
}
