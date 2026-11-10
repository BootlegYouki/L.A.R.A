package org.lara.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    @Test fun semanticTextAndIconsKeepReadableContrast() {
        for ((ink, fill) in listOf(
            LaraColors.TextPrimary to LaraColors.Surface,
            LaraColors.TextSecondary to LaraColors.Canvas,
            LaraColors.PrimaryDark to LaraColors.PrimaryLight,
            LaraColors.InfoDark to LaraColors.InfoLight,
            LaraColors.WarningDark to LaraColors.WarningLight,
            LaraColors.WarningBannerText to LaraColors.WarningLight,
            LaraColors.DangerDark to LaraColors.DangerLight,
        )) {
            assertTrue("Essential text contrast", contrast(ink, fill) >= 4.5f)
        }
        assertTrue("Warning icon contrast", contrast(LaraColors.WarningIcon, LaraColors.WarningLight) >= 3f)
        assertTrue("Large bold primary action contrast", contrast(Color.White, LaraColors.Primary) >= 3f)
    }

    private fun contrast(a: Color, b: Color): Float =
        (max(a.luminance(), b.luminance()) + 0.05f) / (min(a.luminance(), b.luminance()) + 0.05f)
}
