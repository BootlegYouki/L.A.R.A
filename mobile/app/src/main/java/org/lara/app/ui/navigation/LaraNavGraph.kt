package org.lara.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
 * Top-level navigation shell for the scaffold, split into two levels so the bottom bar is hoisted:
 *
 *  - Outer [NavHost]: [LaraRoutes.CONNECT] (the landing screen) and [LaraRoutes.HOME] (the shell).
 *  - [HomeShell]: ONE [Scaffold] with ONE [NavigationBar] and an inner [NavHost] that swaps the
 *    four tab screens. The bar is composed once and survives tab switches (so it does not rebuild
 *    per destination, and inner back-stack state is kept).
 *
 * Role-aware graphs (StudentNavGraph / TeacherNavGraph) replace [HomeShell]'s inner host in #24;
 * the outer host and the Connect -> Home transition stay as the stable entry contract.
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
                    navController.navigate(LaraRoutes.HOME) {
                        // Drop Connect so Back from the shell exits the app rather than returning here.
                        popUpTo(LaraRoutes.CONNECT) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(LaraRoutes.HOME) {
            HomeShell()
        }
    }
}

/**
 * The bottom-nav shell: a single Scaffold whose bar drives an inner NavHost over the four tabs.
 * The inner NavController is remembered here, so switching tabs recomposes only the content, not
 * the bar.
 */
@Composable
private fun HomeShell(
    tabNavController: NavHostController = rememberNavController(),
) {
    Scaffold(
        bottomBar = { LaraBottomBar(tabNavController) },
    ) { innerPadding ->
        NavHost(
            navController = tabNavController,
            startDestination = LaraTab.STREAM.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(LaraTab.STREAM.route) { StreamScreen() }
            composable(LaraTab.CLASSWORK.route) { ClassworkScreen() }
            composable(LaraTab.QUIZZES.route) { QuizzesScreen() }
            composable(LaraTab.TUTOR.route) { TutorScreen() }
        }
    }
}

@Composable
private fun LaraBottomBar(tabNavController: NavHostController) {
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        LaraTab.entries.forEach { tab ->
            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        tabNavController.navigate(tab.route) {
                            // Keep a single back-stack entry per tab and preserve each tab's state.
                            popUpTo(tabNavController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                // Icon slot is a deliberate empty placeholder in the scaffold; #24 drops the
                // Phosphor icon here so the item is icon + text (design system §4/§5.13).
                icon = { Box(Modifier) },
                // Text goes in the label slot (the correct Material slot) and always shows.
                label = { Text(stringResource(tab.labelRes)) },
                alwaysShowLabel = true,
            )
        }
    }
}
