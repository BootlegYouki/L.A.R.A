package org.lara.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * L.A.R.A. canonical color tokens. Source: design-system/showcase.html (Colors) and
 * design-system/design-system.md section 1. Never write Color(0xFF...) inside a screen; add a token here
 * only through a Lead-reviewed design-system change.
 *
 * Zero gradients: every surface is a flat, solid fill.
 */
object LaraColors {
    // Brand
    val Primary = Color(0xFF2E9B4B)
    val PrimaryDark = Color(0xFF176B36)
    val PrimaryLight = Color(0xFFE4F6E8)

    // Socratic AI tutor. Strictly reserved for tutor surfaces (chat, AI tab, AI FAB, grounding tag).
    val Ai = Color(0xFF5145E5)
    val AiDark = Color(0xFF4035C9)
    val AiLight = Color(0xFFEEEAFE)
    val AiBorder = Color(0xFFD6CCFC)
    val AiBubbleText = Color(0xFF2D237A)
    val AiChatBackground = Color(0xFFFAF9FE)

    // Surfaces and text
    val Canvas = Color(0xFFF7FBFA)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceSubtle = Color(0xFFF2F6F5)
    val Border = Color(0xFFE4EAF0)
    val TextPrimary = Color(0xFF17213D)
    val TextSecondary = Color(0xFF667085)
    val TextMuted = Color(0xFF98A2B3)

    // Status. Always pair a status color with an icon and a text label.
    val Success = Color(0xFF22A447)
    val Warning = Color(0xFFF5B82E)
    val Danger = Color(0xFFEF5350)
    val Info = Color(0xFF3B82F6)

    // Status light fills, readable text on those fills, and borders
    val SuccessBorder = Color(0xFFC4ECCB)
    val WarningLight = Color(0xFFFEF0C7)
    val WarningDark = Color(0xFFB54708)
    val WarningBorder = Color(0xFFFEDF89)
    val DangerLight = Color(0xFFFEE4E2)
    val DangerDark = Color(0xFFB42318)
    val DangerBorder = Color(0xFFFECDCA)
    val InfoLight = Color(0xFFE0F2FE)
    val InfoDark = Color(0xFF0369A1)
    val InfoBorder = Color(0xFFBAE6FD)
}
