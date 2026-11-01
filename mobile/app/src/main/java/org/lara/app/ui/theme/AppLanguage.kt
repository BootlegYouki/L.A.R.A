package org.lara.app.ui.theme

import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.util.Locale

private data class LanguageEnvironment(val context: android.content.Context,
    val configuration: Configuration, val density: Density)
private val LocalLanguageEnvironment = staticCompositionLocalOf<LanguageEnvironment?> { null }

@Composable
fun AppLanguage(language: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val current = LocalConfiguration.current
    val configuration = remember(current, language) {
        Configuration(current).apply { setLocale(Locale.forLanguageTag(language)) }
    }
    val localized = remember(base, configuration) { base.createConfigurationContext(configuration) }
    val density = LocalDensity.current
    val environment = remember(localized, configuration, density) { LanguageEnvironment(localized, configuration, density) }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
        LocalLanguageEnvironment provides environment, content = content)
}

/** Dialogs/sheets create another Android owner, which otherwise reinstates the device locale/density. */
@Composable
fun PopupLanguage(content: @Composable () -> Unit) {
    val environment = LocalLanguageEnvironment.current
    if (environment == null) content() else CompositionLocalProvider(
        LocalContext provides environment.context, LocalConfiguration provides environment.configuration,
        LocalDensity provides environment.density, content = content)
}
