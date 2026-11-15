package app.shelf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFE8772E)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFFBF9F5),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFF1ECE3),
    onSurfaceVariant = Color(0xFF6E6A64),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF0A05A),
    onPrimary = Color(0xFF2B1600),
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1E1E20),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2A2A2D),
    onSurfaceVariant = Color(0xFFA5A5AA),
)

@Composable
fun ShelfTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
