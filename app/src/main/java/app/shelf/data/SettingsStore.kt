package app.shelf.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Reader appearance, shared by every book (like Apple Books). */
data class ReaderSettings(
    val themeId: String = "sepia",
    val fontId: String = "charter",
    val fontSize: Double = 1.0,
    val lineHeight: Double = 1.5,
    val margins: Double = 1.0,
    val justify: Boolean = true,
    /**
     * Off by default: with it on, Chrome misplaces the start handle when a selection begins
     * at the very start of a paragraph that has hyphenated lines.
     */
    val hyphenate: Boolean = false,
)

data class Goals(
    val dailyMinutes: Int = 15,
    val yearlyBooks: Int = 12,
)

/** Morning notification with the daily goal and the book in progress. */
data class Reminder(
    val enabled: Boolean = true,
    val hour: Int = 8,
    val minute: Int = 0,
)

enum class LibraryLayout { GRID, LIST }

/** Order of All Books. Recently Read keeps what you're reading at the top, like Apple Books. */
enum class LibrarySort(val label: String) {
    RECENT("Recently Read"),
    TITLE("Title"),
    AUTHOR("Author"),
    NEWEST("Newest Added"),
    OLDEST("Oldest Added"),
}

/** Order of the Want to Read list. */
enum class ListSort(val label: String) {
    NEWEST("Newest Added"),
    OLDEST("Oldest Added"),
    TITLE("Title"),
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("reader_theme")
        val font = stringPreferencesKey("reader_font")
        val fontSize = doublePreferencesKey("reader_font_size")
        val lineHeight = doublePreferencesKey("reader_line_height")
        val margins = doublePreferencesKey("reader_margins")
        val justify = booleanPreferencesKey("reader_justify")
        val hyphenate = booleanPreferencesKey("reader_hyphenate")
        val dailyMinutes = intPreferencesKey("goal_daily_minutes")
        val yearlyBooks = intPreferencesKey("goal_yearly_books")
        val libraryTree = stringPreferencesKey("library_tree_uri")
        val layout = stringPreferencesKey("library_layout")
        val librarySort = stringPreferencesKey("library_sort")
        val listSort = stringPreferencesKey("reading_list_sort")
        val reminderOn = booleanPreferencesKey("reminder_enabled")
        val reminderHour = intPreferencesKey("reminder_hour")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val askedNotifications = booleanPreferencesKey("asked_notification_permission")
    }

    val reader: Flow<ReaderSettings> = context.dataStore.data.map { p ->
        val d = ReaderSettings()
        ReaderSettings(
            themeId = p[Keys.theme] ?: d.themeId,
            fontId = p[Keys.font] ?: d.fontId,
            fontSize = p[Keys.fontSize] ?: d.fontSize,
            lineHeight = p[Keys.lineHeight] ?: d.lineHeight,
            margins = p[Keys.margins] ?: d.margins,
            justify = p[Keys.justify] ?: d.justify,
            hyphenate = p[Keys.hyphenate] ?: d.hyphenate,
        )
    }

    val goals: Flow<Goals> = context.dataStore.data.map { p ->
        val d = Goals()
        Goals(
            dailyMinutes = p[Keys.dailyMinutes] ?: d.dailyMinutes,
            yearlyBooks = p[Keys.yearlyBooks] ?: d.yearlyBooks,
        )
    }

    val libraryTreeUri: Flow<String?> = context.dataStore.data.map { it[Keys.libraryTree] }

    val layout: Flow<LibraryLayout> = context.dataStore.data.map { p ->
        if (p[Keys.layout] == LibraryLayout.LIST.name) LibraryLayout.LIST else LibraryLayout.GRID
    }

    val librarySort: Flow<LibrarySort> = context.dataStore.data.map { p ->
        LibrarySort.entries.firstOrNull { it.name == p[Keys.librarySort] } ?: LibrarySort.RECENT
    }

    val listSort: Flow<ListSort> = context.dataStore.data.map { p ->
        ListSort.entries.firstOrNull { it.name == p[Keys.listSort] } ?: ListSort.NEWEST
    }

    suspend fun setLibrarySort(sort: LibrarySort) = context.dataStore.edit { it[Keys.librarySort] = sort.name }

    suspend fun setListSort(sort: ListSort) = context.dataStore.edit { it[Keys.listSort] = sort.name }

    val reminder: Flow<Reminder> = context.dataStore.data.map { p ->
        val d = Reminder()
        Reminder(
            enabled = p[Keys.reminderOn] ?: d.enabled,
            hour = p[Keys.reminderHour] ?: d.hour,
            minute = p[Keys.reminderMinute] ?: d.minute,
        )
    }

    val askedNotifications: Flow<Boolean> = context.dataStore.data.map { it[Keys.askedNotifications] ?: false }

    suspend fun setLayout(layout: LibraryLayout) = context.dataStore.edit { it[Keys.layout] = layout.name }

    suspend fun saveReminder(r: Reminder) = context.dataStore.edit { p ->
        p[Keys.reminderOn] = r.enabled
        p[Keys.reminderHour] = r.hour
        p[Keys.reminderMinute] = r.minute
    }

    suspend fun setAskedNotifications() = context.dataStore.edit { it[Keys.askedNotifications] = true }

    suspend fun saveReader(s: ReaderSettings) = context.dataStore.edit { p ->
        p[Keys.theme] = s.themeId
        p[Keys.font] = s.fontId
        p[Keys.fontSize] = s.fontSize
        p[Keys.lineHeight] = s.lineHeight
        p[Keys.margins] = s.margins
        p[Keys.justify] = s.justify
        p[Keys.hyphenate] = s.hyphenate
    }

    suspend fun saveGoals(g: Goals) = context.dataStore.edit { p ->
        p[Keys.dailyMinutes] = g.dailyMinutes
        p[Keys.yearlyBooks] = g.yearlyBooks
    }

    suspend fun setLibraryTreeUri(uri: String) = context.dataStore.edit { p ->
        p[Keys.libraryTree] = uri
    }
}
