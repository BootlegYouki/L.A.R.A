package org.lara.app.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.discovery.ConnectionResult
import org.lara.app.ui.navigation.*
import org.lara.app.ui.screens.discovery.*
import org.lara.app.ui.theme.*

@Composable
fun AuthHost(container: AppContainer) {
    val model: AuthViewModel = viewModel(factory = viewModelFactory {
        initializer { AuthViewModel(container.auth, container.endpoints) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    var discovery by remember { mutableStateOf(false) }
    BackHandler(enabled = discovery) { discovery = false }
    Scaffold(containerColor = LaraColors.Canvas) { insets ->
        if(state.user == null) Box(Modifier.fillMaxSize().padding(insets)) {
            when {
                state.restoring -> Column(
                    Modifier.padding(LaraSpacing.S4),
                    verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4),
                ) {
                    Text(stringResource(R.string.auth_restoring))
                    repeat(3) {
                        Spacer(Modifier.fillMaxWidth().height(56.dp)
                            .background(LaraColors.SurfaceSubtle, LaraShapes.medium))
                    }
                }
                discovery -> {
                    val scan: DiscoveryViewModel = viewModel(factory = viewModelFactory {
                        initializer { DiscoveryViewModel(container.discovery) }
                    })
                    val result by scan.result.collectAsStateWithLifecycle()
                    LaunchedEffect(result) { if(result == ConnectionResult.RESPONDING) discovery = false }
                    DiscoveryScreen(scan, onBack = { discovery = false })
                }
                else -> AuthScreen(state, model::submit, onDiscovery = { discovery = true })
            }
        }
    }
    state.user?.let { user ->
        val attempts = remember(user.id) { container.quizzes.hasInProgressAttempt(user.id) }
        val locked by attempts.collectAsStateWithLifecycle(initialValue = true)
        val endpoint = remember(user.id) { container.endpoints.load() }
        val reachability = remember(endpoint) { container.auth.reachability(endpoint) }
        val reachable by reachability.collectAsStateWithLifecycle(initialValue = false)
        val shell = ShellState(
            role = if(user.role == "TEACHER") ShellRole.TEACHER else ShellRole.STUDENT,
            isHubReachable = reachable,
            hasInProgressAttempt = user.role == "STUDENT" && locked,
        )
        if (user.role == "STUDENT") org.lara.app.ui.screens.enrollment.LearnerEnrollmentNavigation(
            container, user.id, shell, model::requireSignIn)
        else LaraNavigation(container.discovery, shell, sessionKey = user.id)
    }
}
