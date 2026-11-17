package app.shelf.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shelf.data.ReaderSettings
import kotlin.math.roundToInt
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/** Everything drawn on top of the page while the controls are showing. */
@Composable
fun ReaderControls(
    state: ReaderUiState,
    title: String,
    positions: List<Locator>,
    tableOfContents: List<Link>,
    onClose: () -> Unit,
    onHide: () -> Unit,
    onSeek: (Int) -> Unit,
    onOpenLink: (Link) -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit,
    onOpenHighlight: (Highlight) -> Unit,
    onHighlightColor: (Highlight, HighlightColor) -> Unit,
    onHighlightNote: (Highlight, String) -> Unit,
    onDeleteHighlight: (Highlight) -> Unit,
    onCopyHighlight: (Highlight) -> Unit,
    onShareHighlight: (Highlight) -> Unit,
    onSelectionColor: (HighlightColor) -> Unit,
    onSelectionNote: () -> Unit,
    onCopySelection: () -> Unit,
    onShareSelection: () -> Unit,
) {
    val theme = PageThemes.byId(state.settings.themeId)
    val visible = state.controlsVisible

    Box(Modifier.fillMaxSize()) {
        // Tapping the page while the controls are up hides them (like Apple Books).
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    when (state.panel) {
                        ReaderPanel.None, ReaderPanel.Highlight, ReaderPanel.Selection -> onHide()
                        else -> state.panel = ReaderPanel.None
                    }
                }
        )

        AnimatedVisibility(
            visible = visible && state.panel != ReaderPanel.Contents,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                title = title,
                theme = theme,
                onClose = onClose,
                onContents = { state.panel = ReaderPanel.Contents },
                onAppearance = {
                    state.panel = if (state.panel == ReaderPanel.Appearance) ReaderPanel.None else ReaderPanel.Appearance
                },
            )
        }

        AnimatedVisibility(
            visible = visible && state.panel == ReaderPanel.None,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomBar(state.locator, positions, theme, onSeek)
        }

        AnimatedVisibility(
            visible = visible && state.panel == ReaderPanel.Appearance,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            AppearancePanel(state.settings, theme, onSettingsChange)
        }

        AnimatedVisibility(
            visible = visible && state.panel == ReaderPanel.Contents,
            enter = fadeIn() + slideInVertically { it / 8 },
            exit = fadeOut() + slideOutVertically { it / 8 },
        ) {
            ContentsPanel(
                title = title,
                toc = tableOfContents,
                current = state.locator,
                highlights = state.highlights,
                theme = theme,
                onClose = { state.panel = ReaderPanel.None },
                onOpenLink = onOpenLink,
                onOpenHighlight = onOpenHighlight,
            )
        }

        // Remember the side while the panel slides away, after the selection is gone.
        val selectionAtTop = remember { mutableStateOf(false) }
        state.selection?.let { selectionAtTop.value = it.atTop }
        val atTop = selectionAtTop.value
        AnimatedVisibility(
            visible = state.panel == ReaderPanel.Selection,
            enter = slideInVertically { if (atTop) -it else it },
            exit = slideOutVertically { if (atTop) -it else it },
            modifier = Modifier.align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter),
        ) {
            SelectionSheet(
                atTop = atTop,
                theme = theme,
                onColor = onSelectionColor,
                onNote = onSelectionNote,
                onCopy = onCopySelection,
                onShare = onShareSelection,
                onDone = onHide,
            )
        }

        val active = state.highlights.firstOrNull { it.id == state.activeHighlightId }
        val highlightAtTop = state.highlightAtTop
        AnimatedVisibility(
            visible = state.panel == ReaderPanel.Highlight && active != null,
            enter = slideInVertically { if (highlightAtTop) -it else it },
            exit = slideOutVertically { if (highlightAtTop) -it else it },
            modifier = Modifier.align(if (highlightAtTop) Alignment.TopCenter else Alignment.BottomCenter),
        ) {
            // Keep showing the last highlight while the sheet slides away.
            val last = remember { mutableStateOf(active) }
            if (active != null) last.value = active
            last.value?.let { h ->
                HighlightSheet(
                    highlight = h,
                    atTop = highlightAtTop,
                    startEditing = state.editNote,
                    theme = theme,
                    onColor = { onHighlightColor(h, it) },
                    onNote = { onHighlightNote(h, it) },
                    onCopy = { onCopyHighlight(h); onHide() },
                    onShare = { onShareHighlight(h) },
                    onDelete = { onDeleteHighlight(h) },
                    onDone = onHide,
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    theme: PageTheme,
    onClose: () -> Unit,
    onContents: () -> Unit,
    onAppearance: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.chrome.copy(alpha = 0.97f))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(theme, onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close book", tint = theme.chromeText, modifier = Modifier.size(20.dp))
        }
        Text(
            title,
            color = theme.chromeText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        RoundButton(theme, onContents) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents", tint = theme.chromeText, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        RoundButton(theme, onAppearance) {
            Text("Aa", color = theme.chromeText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun RoundButton(theme: PageTheme, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(theme.chromeText.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun Locator.positionIndex(positions: List<Locator>): Int {
    locations.position?.let { return (it - 1).coerceIn(0, (positions.size - 1).coerceAtLeast(0)) }
    val p = locations.totalProgression ?: return 0
    return (p * (positions.size - 1)).roundToInt().coerceAtLeast(0)
}

@Composable
private fun BottomBar(
    locator: Locator?,
    positions: List<Locator>,
    theme: PageTheme,
    onSeek: (Int) -> Unit,
) {
    val total = positions.size
    val current = locator?.positionIndex(positions) ?: 0
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragValue.roundToInt() else current

    val pagesLeftInChapter = remember(locator, positions) {
        if (locator == null) 0
        else positions.count { it.href == locator.href && (it.locations.position ?: 0) > current + 1 }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.chrome.copy(alpha = 0.97f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        locator?.title?.takeIf { it.isNotBlank() && !dragging }?.let {
            Text(it, color = theme.chromeText.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (dragging) {
            positions.getOrNull(shown)?.title?.let {
                Text(it, color = theme.chromeText.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (total > 1) {
            Slider(
                value = shown.toFloat(),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeek(dragValue.roundToInt())
                },
                valueRange = 0f..(total - 1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = theme.accent,
                    activeTrackColor = theme.accent,
                    inactiveTrackColor = theme.chromeText.copy(alpha = 0.18f),
                ),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (total > 0) "${shown + 1} of $total" else "",
                color = theme.chromeText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
            Text(
                when (pagesLeftInChapter) {
                    0 -> "Last page in chapter"
                    1 -> "1 page left in chapter"
                    else -> "$pagesLeftInChapter pages left in chapter"
                },
                color = theme.chromeText.copy(alpha = 0.7f), fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun AppearancePanel(
    settings: ReaderSettings,
    theme: PageTheme,
    onChange: (ReaderSettings) -> Unit,
) {
    val context = LocalContext.current
    val previewFonts = remember {
        BookFonts.all.associate { font ->
            font.id to (font.assetRegular?.let { FontFamily(Font(it, context.assets)) }
                ?: if (font.id == "sans") FontFamily.SansSerif else FontFamily.Serif)
        }
    }
    var customize by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(theme.chrome)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("Themes & Settings", color = theme.chromeText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))

        // Text size
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(theme.chromeText.copy(alpha = 0.07f)),
        ) {
            SizeButton("A", 15, theme, Modifier.weight(1f)) {
                onChange(settings.copy(fontSize = (settings.fontSize - 0.1).coerceAtLeast(0.6).round2()))
            }
            Box(Modifier.width(1.dp).height(44.dp).background(theme.chromeText.copy(alpha = 0.12f)))
            SizeButton("A", 24, theme, Modifier.weight(1f)) {
                onChange(settings.copy(fontSize = (settings.fontSize + 0.1).coerceAtMost(2.5).round2()))
            }
        }
        Spacer(Modifier.height(16.dp))

        // Themes
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PageThemes.all.forEach { t ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { onChange(settings.copy(themeId = t.id)) },
                ) {
                    Box(
                        Modifier
                            .size(width = 70.dp, height = 56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(t.background)
                            .border(
                                width = if (t.id == settings.themeId) 2.5.dp else 1.dp,
                                color = if (t.id == settings.themeId) theme.accent else theme.chromeText.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(14.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Aa", color = t.text, fontSize = 20.sp, fontFamily = previewFonts[settings.fontId])
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(t.name, color = theme.chromeText, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Fonts
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(theme.chromeText.copy(alpha = 0.07f)),
        ) {
            BookFonts.all.forEachIndexed { i, font ->
                if (i > 0) HorizontalDivider(color = theme.chromeText.copy(alpha = 0.1f), modifier = Modifier.padding(start = 16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onChange(settings.copy(fontId = font.id)) }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        font.name,
                        color = theme.chromeText,
                        fontSize = 17.sp,
                        fontFamily = previewFonts[font.id],
                        modifier = Modifier.weight(1f),
                    )
                    if (font.id == settings.fontId) {
                        Icon(Icons.Filled.Check, contentDescription = "Selected", tint = theme.accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Text(
            if (customize) "Hide Customize" else "Customize",
            color = theme.accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable { customize = !customize }
                .padding(vertical = 6.dp),
        )
        if (customize) {
            Spacer(Modifier.height(6.dp))
            Stepper("Line Spacing", "%.1f".format(settings.lineHeight), theme,
                onMinus = { onChange(settings.copy(lineHeight = (settings.lineHeight - 0.1).coerceAtLeast(1.0).round2())) },
                onPlus = { onChange(settings.copy(lineHeight = (settings.lineHeight + 0.1).coerceAtMost(2.2).round2())) },
            )
            Stepper("Margins", "%.2f".format(settings.margins), theme,
                onMinus = { onChange(settings.copy(margins = (settings.margins - 0.25).coerceAtLeast(0.5).round2())) },
                onPlus = { onChange(settings.copy(margins = (settings.margins + 0.25).coerceAtMost(2.5).round2())) },
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Justify Text", color = theme.chromeText, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.justify,
                    onCheckedChange = { onChange(settings.copy(justify = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = theme.accent),
                )
            }
        }
    }
}

private fun Double.round2() = (this * 100).roundToInt() / 100.0

@Composable
private fun SizeButton(label: String, size: Int, theme: PageTheme, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = theme.chromeText, fontSize = size.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Stepper(label: String, value: String, theme: PageTheme, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = theme.chromeText, fontSize = 15.sp, modifier = Modifier.weight(1f))
        RoundButton(theme, onMinus) { Text("−", color = theme.chromeText, fontSize = 20.sp) }
        Text(value, color = theme.chromeText, fontSize = 14.sp, modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        RoundButton(theme, onPlus) { Text("+", color = theme.chromeText, fontSize = 20.sp) }
    }
}

private data class TocRow(val link: Link, val depth: Int)

private fun flatten(links: List<Link>, depth: Int = 0): List<TocRow> =
    links.flatMap { listOf(TocRow(it, depth)) + flatten(it.children, depth + 1) }

private fun Link.baseHref(): String = href.toString().substringBefore('#')

@Composable
private fun ContentsPanel(
    title: String,
    toc: List<Link>,
    current: Locator?,
    highlights: List<Highlight>,
    theme: PageTheme,
    onClose: () -> Unit,
    onOpenLink: (Link) -> Unit,
    onOpenHighlight: (Highlight) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val rows = remember(toc) { flatten(toc) }
    val currentHref = current?.href?.toString()
    val currentIndex = rows.indexOfLast { it.link.baseHref() == currentHref }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.chrome)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (tab == 0) "Contents" else "Highlights", color = theme.chromeText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(title, color = theme.chromeText.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundButton(theme, onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close contents", tint = theme.chromeText, modifier = Modifier.size(20.dp))
            }
        }
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(theme.chromeText.copy(alpha = 0.07f))
                .padding(3.dp),
        ) {
            listOf("Contents", if (highlights.isEmpty()) "Highlights" else "Highlights (${highlights.size})")
                .forEachIndexed { i, label ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (tab == i) theme.chrome else Color.Transparent)
                            .clickable { tab = i }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = theme.chromeText,
                            fontSize = 14.sp,
                            fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
        }
        HorizontalDivider(color = theme.chromeText.copy(alpha = 0.1f))
        if (tab == 1) {
            HighlightsList(highlights, theme, onOpenHighlight)
            return@Column
        }
        if (rows.isEmpty()) {
            Text("This book has no table of contents.", color = theme.chromeText.copy(alpha = 0.6f), modifier = Modifier.padding(20.dp))
        }
        LazyColumn(state = listState) {
            itemsIndexed(rows) { i, row ->
                val isCurrent = i == currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenLink(row.link) }
                        .background(if (isCurrent) theme.accent.copy(alpha = 0.12f) else Color.Transparent)
                        .padding(start = (20 + row.depth * 18).dp, end = 20.dp, top = 13.dp, bottom = 13.dp),
                ) {
                    Text(
                        row.link.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                        color = if (isCurrent) theme.accent else theme.chromeText,
                        fontSize = if (row.depth == 0) 16.sp else 15.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                HorizontalDivider(color = theme.chromeText.copy(alpha = 0.06f), modifier = Modifier.padding(start = 20.dp))
            }
        }
    }
}
