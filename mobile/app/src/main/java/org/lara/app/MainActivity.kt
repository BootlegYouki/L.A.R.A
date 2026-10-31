package org.lara.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.lara.app.ui.screens.auth.AuthHost
import org.lara.app.ui.theme.LaraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LaraTheme { AuthHost((application as LaraApp).container) }
        }
    }
}
