package org.lara.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.lara.app.ui.screens.auth.AuthHost
import org.lara.app.ui.theme.LaraTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.lara.app.ui.screens.settings.AppLanguage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = (application as LaraApp).container
            val language by container.language.language.collectAsStateWithLifecycle()
            AppLanguage(language) { LaraTheme { AuthHost(container) } }
        }
    }
}
