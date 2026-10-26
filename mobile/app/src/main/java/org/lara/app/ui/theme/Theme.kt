package org.lara.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LaraScheme = lightColorScheme(
    primary = LaraColors.Primary,
    onPrimary = Color.White,
    primaryContainer = LaraColors.PrimaryLight,
    onPrimaryContainer = LaraColors.PrimaryDark,
    secondary = LaraColors.Ai,
    onSecondary = Color.White,
    secondaryContainer = LaraColors.AiLight,
    onSecondaryContainer = LaraColors.AiDark,
    background = LaraColors.Canvas,
    onBackground = LaraColors.TextPrimary,
    surface = LaraColors.Surface,
    onSurface = LaraColors.TextPrimary,
    surfaceVariant = LaraColors.SurfaceSubtle,
    onSurfaceVariant = LaraColors.TextSecondary,
    outline = LaraColors.Border,
    error = LaraColors.Danger,
    onError = Color.White,
    errorContainer = LaraColors.DangerLight,
    onErrorContainer = LaraColors.DangerDark,
)

// Light theme only, on purpose: bright classrooms and a high-contrast standard for young readers.
// Dynamic color (Material You) is not used so the brand colors never change per device.
@Composable
fun LaraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LaraScheme,
        typography = LaraTypography,
        shapes = LaraShapes,
        content = content,
    )
}
