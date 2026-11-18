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

    suspend fun search(query: String): List<BookResult> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isEmpty()) return@withContext emptyList()
        val isbn = isbnOf(text)
        val open = runCatching { openLibrary(text, isbn) }
            .onFailure { Log.w(TAG, "Open Library search failed", it) }
        val results = open.getOrDefault(emptyList())
        if (results.isNotEmpty() || googleKey == null) {
            // Surface a network failure only when nothing else can answer.
            if (results.isEmpty()) open.exceptionOrNull()?.let { throw it }
            return@withContext results
        }
        google(if (isbn != null) "isbn:$isbn" else text).map { it.first }
    }

    /** Description and a better cover, if either source has them. */
    suspend fun details(result: BookResult): BookDetails = withContext(Dispatchers.IO) {
        var description: String? = null
        var cover = result.coverUrl
        if (result.key.startsWith("ol:")) {
            description = runCatching {
                val work = getJson("https://openlibrary.org${result.key.removePrefix("ol:")}.json")
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

    private fun openLibrary(text: String, isbn: String?): List<BookResult> {
        val fields = "key,title,author_name,first_publish_year,cover_i,number_of_pages_median"
        val query = if (isbn != null) "isbn=$isbn" else "q=${enc(text)}"
        val json = getJson("https://openlibrary.org/search.json?$query&fields=$fields&limit=25")
        val docs = json.optJSONArray("docs") ?: return emptyList()
        // For an ISBN, show that edition's own title and cover rather than the work's.
        val edition = isbn?.let { runCatching { getJson("https://openlibrary.org/isbn/$it.json") }.getOrNull() }
        val editionTitle = edition?.optString("title")?.takeIf { it.isNotBlank() }
        val editionCover = edition?.optJSONArray("covers")?.optLong(0, 0)?.takeIf { it > 0 }
        return (0 until docs.length()).mapNotNull { i ->
            val doc = docs.getJSONObject(i)
            val title = doc.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val coverId = editionCover ?: doc.optLong("cover_i", 0).takeIf { it > 0 }
            BookResult(
                key = "ol:${doc.optString("key")}",
                title = editionTitle ?: title,
                authors = doc.optJSONArray("author_name").strings(),
                year = doc.optInt("first_publish_year", 0).takeIf { it > 0 },
                pages = doc.optInt("number_of_pages_median", 0).takeIf { it > 0 },
                // A work lists every edition's ISBN; only keep one when you searched for it.
                isbn = isbn,
                thumbnailUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
                coverUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
            )
        }
    }

    /** Google Books results, each with its description. */
    private fun google(q: String, limit: Int = 25): List<Pair<BookResult, String?>> {
        val key = googleKey ?: return emptyList()
        val json = getJson(
            "https://www.googleapis.com/books/v1/volumes?q=${enc(q)}&maxResults=$limit&printType=books&key=$key"
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
        readTimeout = 15_000
        setRequestProperty("User-Agent", USER_AGENT)
        instanceFollowRedirects = true
    }

    companion object {
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
