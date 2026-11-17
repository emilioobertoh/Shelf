package app.shelf.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.shelf.data.HighlightEntity
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.json.JSONObject

/** Highlight colours, like Apple Books. Readium draws them see-through over the text. */
enum class HighlightColor(val id: String, val label: String, val swatch: Color) {
    Yellow("yellow", "Yellow", Color(0xFFFFD60A)),
    Green("green", "Green", Color(0xFF5BD46B)),
    Blue("blue", "Blue", Color(0xFF4DA3FF)),
    Pink("pink", "Pink", Color(0xFFFF6B9A)),
    Purple("purple", "Purple", Color(0xFFB57BEA));

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: Yellow
    }
}

/** A highlight ready for the reader: the row plus its parsed locator. */
class Highlight(val entity: HighlightEntity, val locator: Locator) {
    val id get() = entity.id
    val color get() = HighlightColor.byId(entity.color)
}

fun HighlightEntity.toHighlight(): Highlight? =
    runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull()?.let { Highlight(this, it) }

const val HIGHLIGHT_GROUP = "highlights"

fun Highlight.toDecoration() = Decoration(
    id = id.toString(),
    locator = locator,
    style = Decoration.Style.Highlight(tint = color.swatch.toArgb()),
)

/** Where a locator falls in the book: chapter index plus how far into the chapter. */
fun Publication.sortKey(locator: Locator): Double {
    val index = readingOrder.indexOfFirst { it.url() == locator.href }.coerceAtLeast(0)
    return index + (locator.locations.progression ?: 0.0).coerceIn(0.0, 0.999)
}

/** The table-of-contents title for a locator's chapter, if there is one. */
fun Publication.chapterTitle(locator: Locator): String? {
    fun search(links: List<org.readium.r2.shared.publication.Link>): String? {
        var found: String? = null
        for (link in links) {
            if (link.url().removeFragment() == locator.href) found = link.title ?: found
            search(link.children)?.let { found = it }
        }
        return found
    }
    return search(tableOfContents)?.takeIf { it.isNotBlank() } ?: locator.title
}
