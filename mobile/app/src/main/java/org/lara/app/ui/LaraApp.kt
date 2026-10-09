package org.lara.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.lara.app.ui.navigation.LaraNavGraph
import org.lara.app.ui.theme.LaraTheme

/**
 * Compose root hosted by [org.lara.app.MainActivity]. Applies [LaraTheme] (the design-system tokens)
 * once at the top and draws the navigation shell. Role selection between the Student and Teacher
 * graphs is layered in at #24; the scaffold shows the connection landing then a placeholder shell.
 */
@Composable
fun LaraApp() {
    LaraTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LaraNavGraph()
        }
    }
}
