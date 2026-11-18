package app.shelf.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shelf.data.BookEntity
import app.shelf.data.Goals
import app.shelf.data.LibraryLayout
import app.shelf.data.LibrarySort
import app.shelf.data.Reminder
import app.shelf.reminder.ReadingReminder
import android.Manifest
import android.os.Build
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import app.shelf.R
import app.shelf.data.ReadingStats
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    readingList: ReadingListViewModel,
    gridState: LazyGridState,
    onOpenReadingList: () -> Unit,
    onOpenReader: () -> Unit,
) {
    val books by viewModel.books.collectAsState()
    val wantToRead by readingList.entries.collectAsState()
    val continueBook by viewModel.continueReading.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val folder by viewModel.libraryFolder.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val goals by viewModel.goals.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val opening by viewModel.opening.collectAsState()
    val message by viewModel.message.collectAsState()
    val layout by viewModel.layout.collectAsState()
    val reminder by viewModel.reminder.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel::onFolderChosen)
    }
    var showGoals by remember { mutableStateOf(false) }
    val open: (BookEntity) -> Unit = { viewModel.open(it, onOpenReader) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (folder == null) {
                EmptyLibrary(onChooseFolder = { pickFolder.launch(null) })
            } else {
                val continueBook = continueBook
                val list = layout == LibraryLayout.LIST
                LazyVerticalGrid(
                    state = gridState,
                    columns = if (list) GridCells.Fixed(1) else GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(if (list) 16.dp else 22.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Header(
                            scanning = scanning,
                            layout = layout,
                            onToggleLayout = {
                                viewModel.setLayout(if (list) LibraryLayout.GRID else LibraryLayout.LIST)
                            },
                            onRescan = viewModel::rescan,
                            onChangeFolder = { pickFolder.launch(null) },
                            onOpenReadingList = onOpenReadingList,
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        stats?.let { GoalsCard(it) { showGoals = true } }
                    }
                    if (continueBook != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ContinueReading(continueBook) { open(continueBook) }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        WantToReadRow(wantToRead, onSeeAll = onOpenReadingList, onOpenBook = open)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (books.isEmpty()) "No EPUBs found in this folder yet" else "All Books",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            if (books.size > 1) {
                                SortMenu(sort, LibrarySort.entries, { it.label }, viewModel::setSort)
                            }
                        }
                    }
                    items(books, key = { it.id }) { book ->
                        BookTile(
                            book = book,
                            list = list,
                            modifier = Modifier.animateItem(),
                            onOpen = { open(book) },
                            onSetFinished = { viewModel.setFinished(book, it) },
                        )
                    }
                }
            }

            if (opening) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }

    if (showGoals) {
        ModalBottomSheet(
            onDismissRequest = { showGoals = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            GoalsEditor(
                goals = goals,
                stats = stats,
                reminder = reminder,
                onSave = viewModel::saveGoals,
                onSaveReminder = viewModel::saveReminder,
            )
        }
    }
}

@Composable
private fun EmptyLibrary(onChooseFolder: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Shelf", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Choose the folder where you keep your EPUB files. Shelf reads them where they are and never copies or uploads them.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onChooseFolder) { Text("Choose Folder") }
    }
}

@Composable
private fun Header(
    scanning: Boolean,
    layout: LibraryLayout,
    onToggleLayout: () -> Unit,
    onRescan: () -> Unit,
    onChangeFolder: () -> Unit,
    onOpenReadingList: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Library", fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        IconButton(onClick = onToggleLayout) {
            // Shows the layout you'll switch to.
            if (layout == LibraryLayout.LIST) {
                Icon(painterResource(R.drawable.ic_view_grid), contentDescription = "Show as grid", Modifier.size(22.dp))
            } else {
                Icon(painterResource(R.drawable.ic_view_list), contentDescription = "Show as list", Modifier.size(22.dp))
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Want to Read") }, onClick = { menu = false; onOpenReadingList() })
                DropdownMenuItem(text = { Text("Scan for New Books") }, onClick = { menu = false; onRescan() })
                DropdownMenuItem(text = { Text("Change Folder") }, onClick = { menu = false; onChangeFolder() })
            }
        }
    }
}

@Composable
private fun GoalsCard(stats: ReadingStats, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            GoalRing(
                progress = stats.todayMinutes / stats.dailyGoalMinutes.toFloat(),
                modifier = Modifier.size(84.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${stats.todayMinutes}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("of ${stats.dailyGoalMinutes} min", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text("Reading Goals", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        stats.todayMinutes >= stats.dailyGoalMinutes -> "Today's goal complete"
                        else -> "${stats.dailyGoalMinutes - stats.todayMinutes} min to go today"
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                val today = remember { java.time.LocalDate.now() }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    stats.lastSevenDays.forEachIndexed { i, met ->
                        val day = today.minusDays((6 - i).toLong())
                        val isToday = i == 6
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(if (met) Accent else MaterialTheme.colorScheme.surfaceVariant)
                                    .then(
                                        if (isToday && !met) {
                                            Modifier.border(1.5.dp, Accent, CircleShape)
                                        } else Modifier
                                    )
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                day.dayOfWeek.getDisplayName(
                                    java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()
                                ),
                                fontSize = 10.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isToday) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Streak ${stats.currentStreak} ${if (stats.currentStreak == 1) "day" else "days"}  ·  " +
                        "${stats.booksFinishedThisYear} of ${stats.yearlyGoalBooks} books this year",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GoalRing(progress: Float, modifier: Modifier, content: @Composable () -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                Accent, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        content()
    }
}

@Composable
private fun ContinueReading(book: BookEntity, onOpen: () -> Unit) {
    Column {
        Text("Continue Reading", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(book, Modifier.width(78.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    book.author?.let {
                        Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { book.progress.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color = Accent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("${(book.progress * 100).roundToInt()}% read", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    book: BookEntity,
    list: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onSetFinished: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val isNew = book.lastOpenedAt == null && book.finishedAt == null
    val status = when {
        book.finishedAt != null -> "Finished"
        book.lastOpenedAt == null -> "New"
        else -> "${(book.progress * 100).roundToInt()}%"
    }
    val clickable = Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true })
    Box(modifier) {
        if (list) {
            Column(clickable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(book, Modifier.width(56.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(book.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                        book.author?.let {
                            Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (book.lastOpenedAt != null && book.finishedAt == null) {
                                LinearProgressIndicator(
                                    progress = { book.progress.toFloat() },
                                    modifier = Modifier.width(72.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = Accent,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    drawStopIndicator = {},
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                status,
                                fontSize = 13.sp,
                                color = if (isNew) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isNew) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            }
        } else {
            Column(clickable) {
                Cover(book, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(book.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                Text(
                    status,
                    fontSize = 12.sp,
                    color = if (isNew) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isNew) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (book.finishedAt == null) {
                DropdownMenuItem(text = { Text("Mark as Finished") }, onClick = { menu = false; onSetFinished(true) })
            } else {
                DropdownMenuItem(text = { Text("Mark as Not Finished") }, onClick = { menu = false; onSetFinished(false) })
            }
        }
    }
}

@Composable
private fun Cover(book: BookEntity, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, book.coverPath) {
        value = book.coverPath?.let { path ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
        }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // Simple generated cover for books without one.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFFE9C9A0), Color(0xFFC98E58)))
                ).padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(book.title, color = Color(0xFF3B2614), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GoalsEditor(
    goals: Goals,
    stats: ReadingStats?,
    reminder: Reminder,
    onSave: (Goals) -> Unit,
    onSaveReminder: (Reminder) -> Unit,
) {
    var daily by remember { mutableIntStateOf(goals.dailyMinutes) }
    var yearly by remember { mutableIntStateOf(goals.yearlyBooks) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Text("Reading Goals", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        GoalStepper(
            title = "Daily Reading",
            value = "$daily min",
            subtitle = "Time counts while a book is open and you're turning pages.",
            onMinus = { daily = (daily - 5).coerceAtLeast(5); onSave(Goals(daily, yearly)) },
            onPlus = { daily = (daily + 5).coerceAtMost(240); onSave(Goals(daily, yearly)) },
        )
        Spacer(Modifier.height(20.dp))
        GoalStepper(
            title = "Books This Year",
            value = "$yearly",
            subtitle = "A book counts when you reach the end or mark it as finished.",
            onMinus = { yearly = (yearly - 1).coerceAtLeast(1); onSave(Goals(daily, yearly)) },
            onPlus = { yearly = (yearly + 1).coerceAtMost(365); onSave(Goals(daily, yearly)) },
        )

        stats?.let {
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatBlock("${it.currentStreak}", "Current streak")
                StatBlock("${it.bestStreak}", "Best streak")
                StatBlock("${it.booksFinishedThisYear}/${it.yearlyGoalBooks}", "Books this year")
            }
        }

        Spacer(Modifier.height(24.dp))
        ReminderSettings(reminder, onSaveReminder)
    }
}

@Composable
private fun GoalStepper(title: String, value: String, subtitle: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Accent)
            }
            OutlinedButton(onClick = onMinus, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) {
                Text("−", fontSize = 22.sp)
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = onPlus, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("+", fontSize = 22.sp) }
        }
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatBlock(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReminderSettings(reminder: Reminder, onSave: (Reminder) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ReadingReminder.canNotify(context)) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
    }
    val time = remember(reminder.hour, reminder.minute) {
        java.time.LocalTime.of(reminder.hour, reminder.minute)
            .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Morning Reminder", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(
                "Your goal and the book you're reading, every day. Skipped if you've already hit your goal.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = reminder.enabled,
            onCheckedChange = { on ->
                onSave(reminder.copy(enabled = on))
                if (on && !allowed && Build.VERSION.SDK_INT >= 33) {
                    askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
        )
    }
    if (reminder.enabled) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            android.app.TimePickerDialog(
                context,
                { _, h, m -> onSave(reminder.copy(hour = h, minute = m)) },
                reminder.hour, reminder.minute,
                android.text.format.DateFormat.is24HourFormat(context),
            ).show()
        }) { Text("Remind me at $time") }
        if (!allowed) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Notifications are off for Shelf. Turn them on in your phone's settings to get the reminder.",
                fontSize = 13.sp,
                color = Accent,
            )
        }
    }
}
