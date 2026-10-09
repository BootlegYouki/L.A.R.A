package org.lara.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.lara.app.ui.LaraApp

/**
 * Single-Activity entry point. It hosts the Compose root [LaraApp], which selects the navigation
 * graph (Student vs Teacher) by the authenticated role. The scaffold renders a connection landing
 * plus a placeholder bottom-nav shell; real role selection arrives with #24 (TECH_SPEC §2).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LaraApp()
        }
    }
}
