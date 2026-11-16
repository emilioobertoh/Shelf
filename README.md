# Shelf

A local-only EPUB reader for Android, modelled on Apple Books. Point it at a folder of EPUBs and read. No accounts, no cloud, and no copying or uploading of your books.

## Features

- **Library:** scans a folder you choose (and its subfolders) for EPUBs. Shows covers, a Continue Reading card, progress per book, and a grid or list view.
- **Reader:** paginated reading with tap and swipe page turns, a table of contents, and a scrubber to jump anywhere.
  - Themes: Original, Sepia, Quiet and Night.
  - Fonts: the book's own, Charter, Literata, Source Serif or Sans Serif.
  - Text size, line spacing, margins and justification.
- **Reading goals:** a daily minutes goal with a progress ring, the last 7 days, streaks, and a yearly books goal. Reading time is only counted while you're actually turning pages.
- **Morning reminder:** a daily notification with your goal and current book; tap it to open the book where you left off.
- **Share a quote:** select up to 400 characters and turn them into an Instagram Story image, coloured from the book's cover.

## Building

Needs JDK 21 and the Android SDK (compile SDK 37). Shelf runs on Android 8.0 (API 26) and later.

```bash
./gradlew installDebug
```

This builds a debug APK and installs it on a connected device. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Built with

- Kotlin and Jetpack Compose
- [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit) for opening and rendering EPUBs
- Room for the library and reading history

## Licence

The code is under the [MIT licence](LICENSE). The bundled fonts keep their own licences, found next to them in `app/src/main/assets/fonts`.
