package app.shelf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/** Bottom sheet for one highlight: colour, note, copy, share, delete. */
@Composable
internal fun HighlightSheet(
    highlight: Highlight,
    atTop: Boolean,
    startEditing: Boolean,
    theme: PageTheme,
    onColor: (HighlightColor) -> Unit,
    onNote: (String) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    var note by remember(highlight.id) { mutableStateOf(highlight.entity.note.orEmpty()) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(highlight.id, startEditing) {
        if (startEditing) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    val done = {
        keyboard?.hide()
        onDone()
    }

    val shape = if (atTop) RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
    else RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(16.dp, shape)
            .clip(shape)
            .background(theme.chrome)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .then(
                if (atTop) Modifier.windowInsetsPadding(WindowInsets.displayCutout)
                else Modifier.windowInsetsPadding(WindowInsets.navigationBars).imePadding()
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HighlightColor.entries.forEach { color ->
                val selected = color == highlight.color
                Box(
                    Modifier
                        .padding(end = 12.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(color.swatch)
                        .border(if (selected) 3.dp else 0.dp, theme.chromeText.copy(alpha = 0.85f), CircleShape)
                        .clickable { onColor(color) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Filled.Check, "${color.label}, selected", tint = CheckInk, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            RoundButton(theme, onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete highlight", tint = theme.chromeText, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(14.dp))

        QuoteWithBar(highlight, theme, maxLines = 4)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = note,
            onValueChange = {
                note = it
                onNote(it)
            },
            placeholder = { Text("Add a note", color = theme.chromeText.copy(alpha = 0.45f)) },
            textStyle = androidx.compose.ui.text.TextStyle(color = theme.chromeText, fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = theme.accent,
                unfocusedBorderColor = theme.chromeText.copy(alpha = 0.2f),
                cursorColor = theme.accent,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp, max = 180.dp)
                .focusRequester(focus),
        )
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Copy", theme, onCopy)
            Pill("Share Quote", theme, onShare)
            Spacer(Modifier.weight(1f))
            Pill("Done", theme, done, primary = true)
        }
    }
}

/** Dark check mark that reads on every swatch. */
private val CheckInk = androidx.compose.ui.graphics.Color(0xE6000000)

@Composable
private fun Pill(label: String, theme: PageTheme, onClick: () -> Unit, primary: Boolean = false) {
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (primary) theme.accent else theme.chromeText.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) androidx.compose.ui.graphics.Color.White else theme.chromeText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The highlighted text with a bar in its colour on the left. */
@Composable
private fun QuoteWithBar(highlight: Highlight, theme: PageTheme, maxLines: Int) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(highlight.color.swatch)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            highlight.entity.text,
            color = theme.chromeText,
            fontSize = 15.sp,
            fontFamily = FontFamily.Serif,
            lineHeight = 21.sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The Highlights tab of the contents panel, in book order. */
@Composable
internal fun HighlightsList(
    highlights: List<Highlight>,
    theme: PageTheme,
    onOpen: (Highlight) -> Unit,
) {
    if (highlights.isEmpty()) {
        Text(
            "No highlights yet. Select text and tap Highlight or Note.",
            color = theme.chromeText.copy(alpha = 0.6f),
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    androidx.compose.foundation.lazy.LazyColumn {
        items(highlights.size, key = { highlights[it].id }) { i ->
            val h = highlights[i]
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(h) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(
                    listOfNotNull(h.entity.chapter, dates.format(Date(h.entity.createdAt))).joinToString(" · "),
                    color = theme.chromeText.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                QuoteWithBar(h, theme, maxLines = 5)
                h.entity.note?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = theme.chromeText.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
            androidx.compose.material3.HorizontalDivider(
                color = theme.chromeText.copy(alpha = 0.06f),
                modifier = Modifier.padding(start = 20.dp),
            )
        }
    }
}

/**
 * Compact panel for selected text: a colour saves a highlight, Note saves one and opens its note,
 * plus Copy and Share Quote. Sits at the top of the screen when the selection is in the lower half.
 */
@Composable
internal fun SelectionSheet(
    atTop: Boolean,
    theme: PageTheme,
    onColor: (HighlightColor) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDone: () -> Unit,
) {
    val shape = if (atTop) RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
    else RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(16.dp, shape)
            .clip(shape)
            .background(theme.chrome)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(if (atTop) WindowInsets.displayCutout else WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HighlightColor.entries.forEach { color ->
                Box(
                    Modifier
                        .padding(end = 12.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(color.swatch)
                        .clickable { onColor(color) }
                )
            }
            Spacer(Modifier.weight(1f))
            Pill("Note", theme, onNote)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Copy", theme, onCopy)
            Pill("Share Quote", theme, onShare)
            Spacer(Modifier.weight(1f))
            Pill("Done", theme, onDone, primary = true)
        }
    }
}
