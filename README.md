# Shelf

A local-first EPUB reader for Android, modelled on Apple Books. Point it at a folder of EPUBs and read. No accounts, no cloud, and no copying or uploading of your books.

## Features

- **Library:** scans a folder you choose (and its subfolders) for EPUBs. Shows covers, a Continue Reading card, progress per book, and a grid or list view sorted by recently read, title, author or date added.
- **Reader:** paginated reading with tap and swipe page turns, a table of contents, and a scrubber to jump anywhere.
  - Themes: Original, Sepia, Quiet and Night.
  - Fonts: the book's own, Charter, Literata, Source Serif or Sans Serif.
  - Text size, line spacing, margins and justification.
- **Highlights and notes:** select text to highlight it in one of five colours or add a note. Tap a highlight to change or delete it, and find them all, in book order, under Contents → Highlights.
- **Want to Read:** search for books by title, author or ISBN and keep a list of what to read next, with covers and descriptions saved for offline use. English editions come first, then Spanish ones. Books you already have show as In Library.
- **Reading goals:** a daily minutes goal with a progress ring, the last 7 days, streaks, and a yearly books goal. Reading time is only counted while you're actually turning pages.
- **Morning reminder:** a daily notification with your goal and current book; tap it to open the book where you left off.
- **Share a quote:** select up to 400 characters and turn them into an Instagram Story image, coloured from the book's cover.

## Building

Needs JDK 21 and the Android SDK (compile SDK 37). Shelf runs on Android 8.0 (API 26) and later.

```bash
./gradlew installDebug
```

This builds a debug APK and installs it on a connected device. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Book search

The Want to Read search uses [Open Library](https://openlibrary.org/developers/api), which needs no key. It's the only part of Shelf that uses the network, and it sends only the text you search for.

To also fall back to Google Books, add an API key to `local.properties` (not committed):

```properties
GOOGLE_BOOKS_KEY=your-key
```

## Built with

- Kotlin and Jetpack Compose
- [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit) for opening and rendering EPUBs
- Room for the library and reading history
- [Open Library](https://openlibrary.org) for book search and covers

## Licence

The code is under the [MIT licence](LICENSE). The bundled fonts keep their own licences, found next to them in `app/src/main/assets/fonts`.
