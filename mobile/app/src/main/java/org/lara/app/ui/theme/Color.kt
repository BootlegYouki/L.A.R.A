package org.lara.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Mobile palette requested for #266. Shared design-system reconciliation is recorded in the PR.
 * Keep color values here so screens use semantic tokens.
 *
 * Zero gradients: every surface is a flat, solid fill.
 */
object LaraColors {
    // Brand
    val Primary = Color(0xFF059669)
    val PrimaryDark = Color(0xFF047857)
    val PrimaryLight = Color(0xFFECFDF5)

    // Socratic AI tutor. Strictly reserved for tutor surfaces (chat, AI tab, AI FAB, grounding tag).
    val Ai = Color(0xFF5145E5)
    val AiDark = Color(0xFF4035C9)
    val AiLight = Color(0xFFEEEAFE)
    val AiBorder = Color(0xFFD6CCFC)
    val AiBubbleText = Color(0xFF2D237A)
    val AiChatBackground = Color(0xFFFAF9FE)

    // Surfaces and text
    val Canvas = Color(0xFFF8FAFC)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceSubtle = Canvas
    val Border = Color(0xFFE2E8F0)
    val TextPrimary = Color(0xFF0F172A)
    val TextSecondary = Color(0xFF64748B)
    val TextMuted = Color(0xFF98A2B3)

    // Status. Always pair a status color with an icon and a text label.
    val Success = Color(0xFF10B981)
    val Warning = Color(0xFFF59E0B)
    val WarningIcon = Color(0xFFD97706)
    val Danger = Color(0xFFEF5350)
    val Info = Color(0xFF2563EB)

    // Status light fills, readable text on those fills, and borders
    val SuccessBorder = Color(0xFFA7F3D0)
    val WarningLight = Color(0xFFFFFBEB)
    val WarningDark = Color(0xFFB45309)
    val WarningBannerText = Color(0xFF92400E)
    val WarningBorder = Color(0xFFFDE68A)
    val DangerLight = Color(0xFFFEE4E2)
    val DangerDark = Color(0xFFB42318)
    val DangerBorder = Color(0xFFFECDCA)
    val InfoLight = Color(0xFFEFF6FF)
    val InfoDark = Info
    val InfoBorder = Color(0xFFBFDBFE)
}
