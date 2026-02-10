package cz.preclikos.tvhstream.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(

    primary = Color(0xFF4FA3FF),
    onPrimary = Color(0xFFFFFFFF),

    primaryContainer = Color(0xFF143A66),
    onPrimaryContainer = Color(0xFFE6EAF0),

    secondary = Color(0xFF9CC9FF),
    onSecondary = Color(0xFF001B33),
    secondaryContainer = Color(0xFF20324A),
    onSecondaryContainer = Color(0xFFE6EAF0),

    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EAF0),

    surface = Color(0xFF121824),
    onSurface = Color(0xFFE6EAF0),

    surfaceVariant = Color(0xFF1B2332),
    onSurfaceVariant = Color(0xFFB7C2D0),

    outline = Color(0xFF3A465A),
    outlineVariant = Color(0xFF2A3446)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1C6DD0),
    onPrimary = Color.White,

    primaryContainer = Color(0xFFD6E7FF),
    onPrimaryContainer = Color(0xFF001B33),

    secondary = Color(0xFF2A5D9F),
    onSecondary = Color.White,

    secondaryContainer = Color(0xFFD8E2FF),
    onSecondaryContainer = Color(0xFF001B33),

    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF0B0F14),

    surface = Color.White,
    onSurface = Color(0xFF0B0F14),

    surfaceVariant = Color(0xFFE8EEF6),
    onSurfaceVariant = Color(0xFF3A465A),

    outline = Color(0xFF7C8AA0),
    outlineVariant = Color(0xFFCCD6E4)
)

@Composable
fun TVHStreamTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        shapes = Shapes(),
        content = content
    )
}
