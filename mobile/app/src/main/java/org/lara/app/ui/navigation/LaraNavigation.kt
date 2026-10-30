package org.lara.app.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.lara.app.domain.discovery.HubDiscovery
import org.lara.app.ui.screens.discovery.DiscoveryScreen
import org.lara.app.ui.screens.discovery.DiscoveryViewModel
import kotlinx.serialization.Serializable
import org.lara.app.R
import org.lara.app.ui.theme.LaraColors
import org.lara.app.ui.theme.LaraShapes
import org.lara.app.ui.theme.LaraSpacing
import org.lara.app.ui.theme.PrimaryTouchTarget

@Serializable
private data object Home

@Serializable
private data object About

@Serializable
private data object Discovery

@Composable
fun LaraNavigation(
    discovery: HubDiscovery,
    state: ShellState? = null,
    sessionKey: String = "",
    content: @Composable (ShellDestination) -> Unit = { ShellPlaceholder(it) },
) {
    if (state != null) {
        key(sessionKey, state.role) {
            when (state.role) {
                ShellRole.STUDENT -> StudentNavGraph(state, content = content)
                ShellRole.TEACHER -> TeacherNavGraph(state, content = content)
            }
        }
        return
    }
    val controller = rememberNavController()
    Scaffold(containerColor = LaraColors.Canvas) { insets ->
        NavHost(
            navController = controller,
            startDestination = Discovery,
            modifier = Modifier.fillMaxSize().padding(insets),
        ) {
            composable<Home> {
                PlaceholderPage(
                    about = false,
                    onAction = { controller.navigate(About) { launchSingleTop = true } },
                    onDiscovery = { controller.navigate(Discovery) { launchSingleTop = true } },
                )
            }
            composable<About> {
                PlaceholderPage(about = true, onAction = { controller.popBackStack() })
            }
            composable<Discovery> {
                val model: DiscoveryViewModel = viewModel(factory = viewModelFactory {
                    initializer { DiscoveryViewModel(discovery) }
                })
                DiscoveryScreen(model, onBack = {
                    controller.navigate(Home) { popUpTo<Discovery> { inclusive = true }; launchSingleTop = true }
                })
            }
        }
    }
}

@Composable
private fun PlaceholderPage(about: Boolean, onAction: () -> Unit, onDiscovery: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(LaraSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S6),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = LaraColors.PrimaryDark,
        )
        Text(
            text = stringResource(if (about) R.string.about_title else R.string.shell_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Surface(color = LaraColors.Surface, shape = LaraShapes.large) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(LaraSpacing.S6),
                verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    tint = LaraColors.TextSecondary,
                )
                Text(
                    text = stringResource(R.string.offline_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.offline_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = LaraColors.TextSecondary,
                )
            }
        }
        if (about) {
            Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.empty_body),
                style = MaterialTheme.typography.bodyLarge,
                color = LaraColors.TextSecondary,
            )
        }
        if (onDiscovery != null) {
            androidx.compose.material3.OutlinedButton(
                onClick = onDiscovery,
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget),
            ) { Text(stringResource(R.string.discovery_open)) }
        }
        Button(
            onClick = onAction,
            modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget),
            colors = ButtonDefaults.buttonColors(
                containerColor = LaraColors.PrimaryDark,
                contentColor = LaraColors.Surface,
            ),
        ) {
            Text(stringResource(if (about) R.string.back_action else R.string.about_action))
        }
    }
}
