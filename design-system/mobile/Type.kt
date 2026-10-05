package org.lara.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.lara.app.R

// Copy design-system/mobile/res/font/*.ttf to mobile/app/src/main/res/font/. Bundled, never a downloadable font:
// the app must work with no internet.
val Nunito = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_medium, FontWeight.Medium),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
    Font(R.font.nunito_black, FontWeight.Black),
)

// Scale from the design system (docs/design-system.md section 2). Use sp so system font scaling works.
private val LaraStyles = Typography(
    displayLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 48.sp),
    headlineLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
)

// Material 3 components read the styles above that we did not set (TopAppBar uses titleLarge, dialogs use
// headlineSmall, and so on). Without this they fall back to the system font and break "Nunito everywhere".
val LaraTypography: Typography = LaraStyles.let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontFamily = Nunito),
        displayMedium = t.displayMedium.copy(fontFamily = Nunito),
        headlineSmall = t.headlineSmall.copy(fontFamily = Nunito),
        titleLarge = t.titleLarge.copy(fontFamily = Nunito),
        titleSmall = t.titleSmall.copy(fontFamily = Nunito),
        bodyMedium = t.bodyMedium.copy(fontFamily = Nunito),
        labelMedium = t.labelMedium.copy(fontFamily = Nunito),
        labelSmall = t.labelSmall.copy(fontFamily = Nunito),
    )
}
