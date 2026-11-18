package app.shelf.data

import java.text.Collator
import java.util.Locale

/**
 * Alphabetical ordering for titles and authors: ignores case and accents, and a leading
 * "The", "A", "An", "El", "La", "Los", "Las", "Un", "Una" (so *The Martian* files under M).
 */
object Alphabetical {
    private val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    private val article = Regex("^(the|a|an|el|la|los|las|un|una)\\s+", RegexOption.IGNORE_CASE)

    fun titleKey(title: String): String =
        title.trim().replace(Regex("^[\\p{Punct}\\s]+"), "").replace(article, "")

    /** "Jonathan Haidt, Greg Lukianoff" → "Haidt Jonathan", so authors sort by surname. */
    fun authorKey(authors: String?): String {
        val first = authors?.substringBefore(",")?.trim().orEmpty()
        if (first.isEmpty()) return "￿" // no author: last
        val surname = first.substringAfterLast(" ")
        return "$surname ${first.substringBeforeLast(" ", "")}"
    }

    fun <T> byTitle(title: (T) -> String): Comparator<T> =
        Comparator { a, b -> collator.compare(titleKey(title(a)), titleKey(title(b))) }

    fun <T> byAuthor(authors: (T) -> String?, title: (T) -> String): Comparator<T> =
        Comparator<T> { a, b -> collator.compare(authorKey(authors(a)), authorKey(authors(b))) }
            .then(byTitle(title))
}

fun List<BookEntity>.sortedFor(sort: LibrarySort): List<BookEntity> = when (sort) {
    LibrarySort.RECENT -> this // the query already orders by last opened, then newest added
    LibrarySort.TITLE -> sortedWith(Alphabetical.byTitle { it.title })
    LibrarySort.AUTHOR -> sortedWith(Alphabetical.byAuthor({ it.author }, { it.title }))
    LibrarySort.NEWEST -> sortedByDescending { it.addedAt }
    LibrarySort.OLDEST -> sortedBy { it.addedAt }
}
