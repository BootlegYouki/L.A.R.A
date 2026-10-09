package org.lara.app.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.lara.app.ui.screens.ClassworkScreen
import org.lara.app.ui.screens.ConnectScreen
import org.lara.app.ui.screens.QuizzesScreen
import org.lara.app.ui.screens.StreamScreen
import org.lara.app.ui.screens.TutorScreen

/**
 * Top-level navigation shell for the scaffold. Two levels:
 *  - [LaraRoutes.CONNECT]: the landing/connection screen the app opens to.
 *  - a "home" host that owns the four placeholder bottom-nav destinations.
 *
 * Role-aware graphs (StudentNavGraph / TeacherNavGraph) replace the single home host in #24; this
 * shell gives those issues a stable NavController contract to build on.
 */
@Composable
fun LaraNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = LaraRoutes.CONNECT,
    ) {
        composable(LaraRoutes.CONNECT) {
            ConnectScreen(
                onFindHub = {
                    navController.navigate(LaraRoutes.STREAM) {
                        // Leave the connect screen off the back stack once we enter the shell.
                        popUpTo(LaraRoutes.CONNECT) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        // Each tab is a top-level route; the shared bottom bar is rendered per destination so the
        // scaffold stays flat and easy to split into role graphs later.
        LaraTab.entries.forEach { tab ->
            composable(tab.route) {
                HomeShell(navController = navController, current = tab)
            }
        }
    }
}

/** The bottom-nav Scaffold wrapping whichever tab is active. */
@Composable
private fun HomeShell(
    navController: NavHostController,
    current: LaraTab,
) {
    Scaffold(
        bottomBar = { LaraBottomBar(navController = navController) },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (current) {
            LaraTab.STREAM -> StreamScreen(contentModifier)
            LaraTab.CLASSWORK -> ClassworkScreen(contentModifier)
            LaraTab.QUIZZES -> QuizzesScreen(contentModifier)
            LaraTab.TUTOR -> TutorScreen(contentModifier)
        }
    }
}

@Composable
private fun LaraBottomBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Material's NavigationBar is 80dp tall, comfortably above the 52dp pupil touch-target floor.
    NavigationBar {
        LaraTab.entries.forEach { tab ->
            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
            val label = stringResource(tab.labelRes)
            val cd = stringResource(tab.contentDescriptionRes)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                // Text-labelled nav (icons arrive with the Phosphor set in #24); the item exposes an
                // explicit accessible name and each item keeps a >=52dp hit height.
                icon = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .semantics { contentDescription = cd },
                    )
                },
                colors = NavigationBarItemDefaults.colors(),
            )
        }
    }
}
