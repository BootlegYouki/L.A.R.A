package org.lara.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LaraLightColorScheme = lightColorScheme(
    primary = LaraPrimaryGreen,
    onPrimary = LaraSurfaceWhite,
    primaryContainer = LaraLightGreen,
    onPrimaryContainer = LaraDarkGreen,
    secondary = LaraPrimaryPurple,
    onSecondary = LaraSurfaceWhite,
    secondaryContainer = LaraLightPurple,
    onSecondaryContainer = LaraDarkPurple,
    background = LaraCanvasBg,
    onBackground = LaraTextPrimary,
    surface = LaraSurfaceWhite,
    onSurface = LaraTextPrimary,
    surfaceVariant = LaraSurfaceSubtle,
    onSurfaceVariant = LaraTextSecondary,
    outline = LaraBorder,
    error = LaraStatusDanger,
    onError = LaraSurfaceWhite
)

@Composable
fun LaraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // In primary school classrooms, high-contrast light theme is the invariant
    MaterialTheme(
        colorScheme = LaraLightColorScheme,
        typography = LaraTypography,
        content = content
    )
}
