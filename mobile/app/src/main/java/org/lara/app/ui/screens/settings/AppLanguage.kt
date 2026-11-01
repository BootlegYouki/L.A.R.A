package org.lara.app.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
fun AppLanguage(language: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val current = LocalConfiguration.current
    val configuration = remember(current, language) {
        Configuration(current).apply { setLocale(Locale.forLanguageTag(language)) }
    }
    val localized = remember(base, configuration) { base.createConfigurationContext(configuration) }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, content = content)
}
