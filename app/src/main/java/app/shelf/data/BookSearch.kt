package app.shelf.data

import android.content.Context
import android.util.Log
import app.shelf.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ShelfSearch"
private const val USER_AGENT = "Shelf/0.1 (Android EPUB reader)"

/** A book found online, before it's saved to the reading list. */
data class BookResult(
    /** Stable id for de-duplicating, e.g. "ol:/works/OL21745884W" or "gb:zyTCAlFPjgYC". */
    val key: String,
    val title: String,
    val authors: List<String>,
    val year: Int?,
    val pages: Int?,
    val isbn: String?,
    /** Small cover for search results. */
    val thumbnailUrl: String?,
    /** Larger cover, downloaded when the book is added. */
    val coverUrl: String?,
)

/** Extra details fetched when a book is added. */
data class BookDetails(val description: String?, val coverUrl: String?)

/**
 * Looks up books by title, author or ISBN. Open Library first (free, no key); Google Books only
 * when a key is set in local.properties as GOOGLE_BOOKS_KEY, because Google no longer answers
 * requests without one. Only the search text leaves the phone.
 */
class BookSearch(context: Context) {
    private val coversDir = File(context.filesDir, "list-covers").apply { mkdirs() }
    private val googleKey = BuildConfig.GOOGLE_BOOKS_KEY.takeIf { it.isNotBlank() }

    /**
     * English editions first, then Spanish ones. Other languages only when neither finds anything.
     * A book with both an English and a Spanish edition is listed once for each.
     */
    suspend fun search(query: String): List<BookResult> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isEmpty()) return@withContext emptyList()
        val isbn = isbnOf(text)
        val open = runCatching {
            if (isbn != null) openLibraryIsbn(isbn) else openLibraryByLanguage(text)
        }.onFailure { Log.w(TAG, "Open Library search failed", it) }
        val results = open.getOrDefault(emptyList())
        if (results.isNotEmpty() || googleKey == null) {
            // Surface a network failure only when nothing else can answer.
            if (results.isEmpty()) open.exceptionOrNull()?.let { throw it }
            return@withContext results
        }
        if (isbn != null) return@withContext google("isbn:$isbn").map { it.first }
        val english = async { google(text, lang = "en") }
        val spanish = async { google(text, lang = "es") }
        val preferred = (english.await() + spanish.await()).map { it.first }.distinctBy { it.key }
        preferred.ifEmpty { google(text).map { it.first } }
    }

    /** Description and a better cover, if either source has them. */
    suspend fun details(result: BookResult): BookDetails = withContext(Dispatchers.IO) {
        var description: String? = null
        var cover = result.coverUrl
        if (result.key.startsWith("ol:")) {
            description = runCatching {
                val workKey = result.key.removePrefix("ol:").substringBefore("#")
                val work = getJson("https://openlibrary.org$workKey.json")
                when (val d = work.opt("description")) {
                    is JSONObject -> d.optString("value")
                    is String -> d
                    else -> null
                }
            }.getOrNull()
        }
        if ((description.isNullOrBlank() || cover == null) && googleKey != null) {
            val match = runCatching {
                google(result.isbn?.let { "isbn:$it" } ?: "intitle:${result.title}", limit = 1)
            }.getOrNull()?.firstOrNull()
            if (match != null) {
                if (cover == null) cover = match.first.coverUrl
                if (description.isNullOrBlank()) description = match.second
            }
        }
        BookDetails(description?.cleanDescription()?.ifBlank { null }, cover)
    }

    /** Saves a cover to app storage so the list works offline. */
    suspend fun downloadCover(url: String, key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val out = File(coversDir, "${key.hashCode().toUInt()}.jpg")
            val conn = open(url)
            try {
                if (conn.responseCode != 200) return@runCatching null
                conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
            // Open Library answers missing covers with a 1×1 GIF; treat tiny files as none.
            if (out.length() < 1000) {
                out.delete()
                null
            } else out.absolutePath
        }.onFailure { Log.w(TAG, "Cover download failed", it) }.getOrNull()
    }

    /** Fails only if every request failed, so one slow language doesn't hide the other. */
    private suspend fun openLibraryByLanguage(text: String): List<BookResult> = coroutineScope {
        val english = async { runCatching { openLibrary(text, "eng") } }
        val spanish = async { runCatching { openLibrary(text, "spa") } }
        val tries = listOf(english.await(), spanish.await())
        tries.mapNotNull { it.exceptionOrNull() }.forEach { Log.w(TAG, "Open Library language search failed", it) }
        val preferred = tries.flatMap { it.getOrDefault(emptyList()) }
        when {
            preferred.isNotEmpty() -> preferred
            tries.all { it.isFailure } -> throw tries.first().exceptionOrNull()!!
            else -> openLibrary(text, language = null)
        }
    }

    /**
     * One Open Library search. With a [language], only works with an edition in it are returned,
     * shown with that edition's title and cover.
     */
    private fun openLibrary(text: String, language: String?): List<BookResult> {
        val q = if (language != null) "$text language:$language" else text
        val json = getJson("https://openlibrary.org/search.json?q=${enc(q)}&fields=$SEARCH_FIELDS&limit=20")
        val docs = json.optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { i ->
            val doc = docs.getJSONObject(i)
            val edition = doc.optJSONObject("editions")?.optJSONArray("docs")?.optJSONObject(0)
            val title = edition?.optString("title")?.takeIf { it.isNotBlank() }
                ?: doc.optString("title").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val coverId = edition?.optLong("cover_i", 0)?.takeIf { it > 0 }
                ?: doc.optLong("cover_i", 0).takeIf { it > 0 }
            val editionKey = edition?.optString("key")?.takeIf { language != null && it.isNotBlank() }
            BookResult(
                key = "ol:${doc.optString("key")}" + (editionKey?.let { "#$it" } ?: ""),
                title = title,
                authors = latinAuthors(doc),
                year = doc.optInt("first_publish_year", 0).takeIf { it > 0 },
                pages = doc.optInt("number_of_pages_median", 0).takeIf { it > 0 },
                isbn = null, // a work lists every edition's ISBN, so none is reliable here
                thumbnailUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
                coverUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
            )
        }
    }

    /** An ISBN search, shown with that exact edition's title and cover. */
    private fun openLibraryIsbn(isbn: String): List<BookResult> {
        val json = getJson("https://openlibrary.org/search.json?isbn=$isbn&fields=$SEARCH_FIELDS&limit=5")
        val docs = json.optJSONArray("docs") ?: return emptyList()
        val edition = runCatching { getJson("https://openlibrary.org/isbn/$isbn.json") }.getOrNull()
        val editionTitle = edition?.optString("title")?.takeIf { it.isNotBlank() }
        val editionCover = edition?.optJSONArray("covers")?.optLong(0, 0)?.takeIf { it > 0 }
        return (0 until docs.length()).mapNotNull { i ->
            val doc = docs.getJSONObject(i)
            val title = editionTitle ?: doc.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val coverId = editionCover ?: doc.optLong("cover_i", 0).takeIf { it > 0 }
            BookResult(
                key = "ol:${doc.optString("key")}",
                title = title,
                authors = latinAuthors(doc),
                year = doc.optInt("first_publish_year", 0).takeIf { it > 0 },
                pages = doc.optInt("number_of_pages_median", 0).takeIf { it > 0 },
                isbn = isbn,
                thumbnailUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
                coverUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
            )
        }
    }

    /** Google Books results, each with its description. */
    private fun google(q: String, limit: Int = 20, lang: String? = null): List<Pair<BookResult, String?>> {
        val key = googleKey ?: return emptyList()
        val langParam = lang?.let { "&langRestrict=$it" } ?: ""
        val json = getJson(
            "https://www.googleapis.com/books/v1/volumes?q=${enc(q)}&maxResults=$limit&printType=books$langParam&key=$key"
        )
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            val info = item.optJSONObject("volumeInfo") ?: return@mapNotNull null
            val title = info.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ids = info.optJSONArray("industryIdentifiers")
            val isbn = ids?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .sortedBy { if (it.optString("type") == "ISBN_13") 0 else 1 }
                    .firstOrNull { it.optString("type").startsWith("ISBN") }
                    ?.optString("identifier")
            }
            val image = info.optJSONObject("imageLinks")?.optString("thumbnail")
                ?.takeIf { it.isNotBlank() }?.replace("http://", "https://")
            BookResult(
                key = "gb:${item.optString("id")}",
                title = listOfNotNull(title, info.optString("subtitle").takeIf { it.isNotBlank() })
                    .joinToString(": "),
                authors = info.optJSONArray("authors").strings(),
                year = info.optString("publishedDate").take(4).toIntOrNull(),
                pages = info.optInt("pageCount", 0).takeIf { it > 0 },
                isbn = isbn,
                thumbnailUrl = image,
                coverUrl = image?.replace("zoom=1", "zoom=2"),
            ) to info.optString("description").takeIf { it.isNotBlank() }
        }
    }

    private fun getJson(url: String): JSONObject {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 25_000 // Open Library's search can take 10 s or more
        setRequestProperty("User-Agent", USER_AGENT)
        instanceFollowRedirects = true
    }

    companion object {
        private const val SEARCH_FIELDS =
            "key,title,author_name,author_alternative_name,first_publish_year,cover_i,number_of_pages_median," +
                "editions,editions.key,editions.title,editions.cover_i"

        /**
         * Author names in Latin script. Open Library sometimes gives only the original spelling
         * (e.g. Cyrillic); then the cleanest Latin alternative name is used instead.
         */
        internal fun latinAuthors(doc: JSONObject): List<String> {
            val names = doc.optJSONArray("author_name").strings()
            val latin = names.filter(::isLatin)
            if (latin.isNotEmpty() || names.isEmpty()) return latin
            val alternatives = doc.optJSONArray("author_alternative_name").strings()
                .map { it.trim() }
                .filter { isLatin(it) && it.all { c -> c.isLetter() || c in " .'-" } && it.split(" ").size >= 2 }
            // The first name and surname most alternatives agree on, written plainly.
            fun first(n: String) = n.substringBefore(" ").lowercase()
            fun last(n: String) = n.substringAfterLast(" ").lowercase()
            val firstVotes = alternatives.groupingBy(::first).eachCount()
            val lastVotes = alternatives.groupingBy(::last).eachCount()
            val alternative = alternatives.minWithOrNull(
                compareByDescending<String> { (firstVotes[first(it)] ?: 0) + (lastVotes[last(it)] ?: 0) }
                    .thenBy { n -> n.split(" ").count { it.length > 1 && it == it.uppercase() } } // "SOLZHENITSYN"
                    .thenBy { it.count { c -> c == '.' } }
                    .thenBy { it.length }
            )
            return listOf(alternative ?: names.first())
        }

        private fun isLatin(s: String) = s.all { !it.isLetter() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }

        /** The ISBN if the text is one (10 or 13 digits, dashes and spaces allowed). */
        fun isbnOf(text: String): String? {
            val digits = text.replace("-", "").replace(" ", "").uppercase()
            return digits.takeIf {
                (it.length == 13 && it.all(Char::isDigit)) ||
                    (it.length == 10 && it.dropLast(1).all(Char::isDigit) && (it.last().isDigit() || it.last() == 'X'))
            }
        }
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

/** Strips the HTML and the "([source][1])" link lists some descriptions carry. */
private fun String.cleanDescription(): String =
    replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\\n-{3,}[\\s\\S]*$"), "")
        .replace(Regex("\\[([^\\]]+)]\\[\\d+]"), "$1")
        .replace(Regex("\\*{1,2}([^*\\n]+)\\*{1,2}"), "$1")
        .replace(Regex("(?m)^\\s*\\[\\d+]:.*$"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
