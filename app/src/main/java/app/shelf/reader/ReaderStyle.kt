package app.shelf.reader

import androidx.compose.ui.graphics.Color
import app.shelf.data.ReaderSettings
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.navigator.preferences.Color as ReadiumColor

/** Page themes modelled on Apple Books. [chrome] tints the reader controls to match the page. */
data class PageTheme(
    val id: String,
    val name: String,
    val background: Color,
    val text: Color,
    val chrome: Color,
    val chromeText: Color,
    val accent: Color,
    val isDark: Boolean,
)

object PageThemes {
    val original = PageTheme(
        "original", "Original",
        background = Color(0xFFFFFFFF), text = Color(0xFF1C1C1E),
        chrome = Color(0xFFF4F4F6), chromeText = Color(0xFF1C1C1E),
        accent = Color(0xFFE8772E), isDark = false,
    )
    val sepia = PageTheme(
        "sepia", "Sepia",
        background = Color(0xFF423B30), text = Color(0xFFF9ECDB),
        chrome = Color(0xFF383228), chromeText = Color(0xFFF3E6D3),
        accent = Color(0xFFE3A363), isDark = true,
    )
    val quiet = PageTheme(
        "quiet", "Quiet",
        background = Color(0xFF4A4A4D), text = Color(0xFFD9D9DE),
        chrome = Color(0xFF3A3A3D), chromeText = Color(0xFFE8E8ED),
        accent = Color(0xFFF0A05A), isDark = true,
    )
    val night = PageTheme(
        "night", "Night",
        background = Color(0xFF121212), text = Color(0xFFC9C9CE),
        chrome = Color(0xFF1E1E20), chromeText = Color(0xFFE5E5EA),
        accent = Color(0xFFF0A05A), isDark = true,
    )
    val all = listOf(original, sepia, quiet, night)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: sepia
}

/** Fonts offered in the Aa panel. [assetRegular] is used to preview the font in the picker. */
data class BookFont(
    val id: String,
    val name: String,
    val family: FontFamily?,
    val assetRegular: String?,
)

object BookFonts {
    val original = BookFont("original", "Original", null, null)
    val charter = BookFont("charter", "Charter", FontFamily("Charter"), "fonts/XCharter-Roman.otf")
    val literata = BookFont("literata", "Literata", FontFamily("Literata"), "fonts/Literata.ttf")
    val sourceSerif = BookFont("sourceserif", "Source Serif", FontFamily("Source Serif 4"), "fonts/SourceSerif4.ttf")
    val sans = BookFont("sans", "Sans Serif", FontFamily.SANS_SERIF, null)
    val all = listOf(original, charter, literata, sourceSerif, sans)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: charter
}

private fun Color.toReadium() = ReadiumColor(
    android.graphics.Color.argb(
        (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
    )
)

@OptIn(ExperimentalReadiumApi::class)
fun ReaderSettings.toEpubPreferences(): EpubPreferences {
    val theme = PageThemes.byId(themeId)
    return EpubPreferences(
        theme = if (theme.isDark) Theme.DARK else Theme.LIGHT,
        backgroundColor = theme.background.toReadium(),
        textColor = theme.text.toReadium(),
        fontFamily = BookFonts.byId(fontId).family,
        fontSize = fontSize,
        lineHeight = lineHeight,
        pageMargins = margins,
        textAlign = if (justify) TextAlign.JUSTIFY else TextAlign.START,
        publisherStyles = false,
        scroll = false,
    )
}

/** Registers the bundled font files with Readium so books can use them. */
@OptIn(ExperimentalReadiumApi::class)
fun readerConfiguration(): EpubNavigatorFragment.Configuration =
    EpubNavigatorFragment.Configuration {
        servedAssets = listOf("fonts/.*")

        addFontFamilyDeclaration(BookFonts.charter.family!!) {
            addFontFace {
                addSource("fonts/XCharter-Roman.otf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(FontWeight.NORMAL)
            }
            addFontFace {
                addSource("fonts/XCharter-Italic.otf")
                setFontStyle(FontStyle.ITALIC)
                setFontWeight(FontWeight.NORMAL)
            }
            addFontFace {
                addSource("fonts/XCharter-Bold.otf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(FontWeight.BOLD)
            }
            addFontFace {
                addSource("fonts/XCharter-BoldItalic.otf")
                setFontStyle(FontStyle.ITALIC)
                setFontWeight(FontWeight.BOLD)
            }
        }
        addFontFamilyDeclaration(BookFonts.literata.family!!) {
            addFontFace {
                addSource("fonts/Literata.ttf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(200..900)
            }
            addFontFace {
                addSource("fonts/Literata-Italic.ttf")
                setFontStyle(FontStyle.ITALIC)
                setFontWeight(200..900)
            }
        }
        addFontFamilyDeclaration(BookFonts.sourceSerif.family!!) {
            addFontFace {
                addSource("fonts/SourceSerif4.ttf")
                setFontStyle(FontStyle.NORMAL)
                setFontWeight(200..900)
            }
            addFontFace {
                addSource("fonts/SourceSerif4-Italic.ttf")
                setFontStyle(FontStyle.ITALIC)
                setFontWeight(200..900)
            }
        }
    }
