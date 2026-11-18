package app.shelf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shelf.data.BookEntity
import app.shelf.data.BookResult
import app.shelf.data.ReadingListEntity
import app.shelf.shelf
import java.io.File
import java.text.Normalizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A reading list entry, with the library book it matches once you have the EPUB. */
data class ListEntry(val entity: ReadingListEntity, val inLibrary: BookEntity?)

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<BookResult> = emptyList(),
    val error: String? = null,
    /** True once a search for [query] has finished, so "No results" can show. */
    val searched: Boolean = false,
)

class ReadingListViewModel(app: Application) : AndroidViewModel(app) {
    private val shelf = app.shelf
    private val dao = shelf.db.readingList()

    val entries: StateFlow<List<ListEntry>> =
        combine(dao.observe(), shelf.db.books().observeLibrary()) { list, books ->
            val byTitle = books.groupBy { normalizeTitle(it.title) }
            list.map { entry ->
                val match = byTitle[normalizeTitle(entry.title)]?.firstOrNull { sameAuthor(entry.authors, it.author) }
                ListEntry(entry, match)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Keys already on the list, to show a check instead of + in the results. */
    val savedKeys: StateFlow<Set<String>> =
        entries.map { list -> list.map { it.entity.key }.toSet() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _search = MutableStateFlow(SearchState())
    val search = _search.asStateFlow()

    private val _adding = MutableStateFlow<Set<String>>(emptySet())
    val adding = _adding.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    private var searchJob: Job? = null

    /** Searches as you type, after a short pause. */
    fun setQuery(query: String) {
        _search.update { it.copy(query = query, error = null) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _search.value = SearchState()
            return
        }
        searchJob = viewModelScope.launch {
            delay(450)
            runSearch(query)
        }
    }

    /** Searches straight away (keyboard search button). */
    fun searchNow() {
        val query = _search.value.query
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(query) }
    }

    private suspend fun runSearch(query: String) {
        if (query.trim().length < 2) return
        _search.update { it.copy(loading = true) }
        val result = runCatching { shelf.bookSearch.search(query) }
        if (_search.value.query != query) return
        _search.value = SearchState(
            query = query,
            results = result.getOrDefault(emptyList()),
            error = result.exceptionOrNull()?.let { "Couldn't search. Check your connection." },
            searched = true,
        )
    }

    fun add(result: BookResult) {
        if (result.key in _adding.value || result.key in savedKeys.value) return
        _adding.update { it + result.key }
        viewModelScope.launch {
            try {
                val details = shelf.bookSearch.details(result)
                val coverPath = details.coverUrl?.let { shelf.bookSearch.downloadCover(it, result.key) }
                    ?: result.thumbnailUrl?.let { shelf.bookSearch.downloadCover(it, result.key) }
                dao.insert(
                    ReadingListEntity(
                        key = result.key,
                        title = result.title,
                        authors = result.authors.joinToString(", ").ifBlank { null },
                        year = result.year,
                        pages = result.pages,
                        isbn = result.isbn,
                        description = details.description,
                        coverPath = coverPath,
                        addedAt = System.currentTimeMillis(),
                    )
                )
                _message.value = "Added “${result.title}”"
            } finally {
                _adding.update { it - result.key }
            }
        }
    }

    fun remove(entry: ReadingListEntity) {
        viewModelScope.launch {
            dao.delete(entry.id)
            entry.coverPath?.let { File(it).delete() }
        }
    }

    companion object {
        /** "The Way of Kings: Book One…" → "way of kings", accents and punctuation dropped. */
        fun normalizeTitle(title: String): String {
            val main = title.substringBefore(":").substringBefore(" (")
            return Normalizer.normalize(main, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
                .lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("^(the|a|an|el|la|los|las) "), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /** True when the two author strings share a surname, or either is missing. */
        fun sameAuthor(a: String?, b: String?): Boolean {
            if (a.isNullOrBlank() || b.isNullOrBlank()) return true
            fun words(s: String) = normalizeTitle(s.replace(",", " ")).split(" ").filter { it.length > 2 }.toSet()
            return words(a).intersect(words(b)).isNotEmpty()
        }
    }
}
