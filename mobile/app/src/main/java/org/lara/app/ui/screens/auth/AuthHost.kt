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
import org.lara.app.ui.screens.settings.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

@Composable
fun AuthHost(container: AppContainer) {
    val model: AuthViewModel = viewModel(factory = viewModelFactory {
        initializer { AuthViewModel(container.auth, container.endpoints) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    var discovery by remember { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
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
                settings && state.previousUser != null -> {
                    val userId = requireNotNull(state.previousUser).id
                    val expectedSession = state.sessionVersion
                    val endpoint = remember(userId) { container.endpoints.load() }
                    val reachability = remember(endpoint) { container.auth.reachability(endpoint, container.syncScheduler.networkChanges) }
                    val reachable by reachability.collectAsStateWithLifecycle(initialValue = false)
                    SettingsHost(container.forUser(userId), userId, reachable,
                        onSignedOut = { settings = false; model.signedOut(expectedSession) },
                        onChangeHub = { settings = false; discovery = true; model.signedOut(expectedSession) },
                        onBack = { settings = false })
                }
                else -> AuthScreen(state, model::submit, onDiscovery = { discovery = true },
                    onSettings = if (state.previousUser != null) { { settings = true } } else null)
            }
        }
    }
    state.user?.let { user ->
        key(user.id, state.sessionVersion) {
            val active = remember { container.forUser(user.id) }
            val store = remember { ViewModelStore() }
            val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = store } }
            DisposableEffect(store) { onDispose { store.clear() } }
            val sessionVersion = state.sessionVersion
            val expired = { model.requireSignIn(sessionVersion) }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                val attempts = remember(user.id) { active.quizzes.hasInProgressAttempt(user.id) }
                val locked by attempts.collectAsStateWithLifecycle(initialValue = true)
                val endpoint = remember(user.id) { active.endpoints.load() }
                val reachability = remember(endpoint) { active.auth.reachability(endpoint, active.syncScheduler.networkChanges) }
                val reachable by reachability.collectAsStateWithLifecycle(initialValue = false)
                val syncSession = rememberSyncSession(active, user.id, reachable, expired)
                val shell = ShellState(
                    role = if(user.role == "TEACHER") ShellRole.TEACHER else ShellRole.STUDENT,
                    isHubReachable = reachable,
                    hasInProgressAttempt = user.role == "STUDENT" && locked,
                )
                if (discovery) {
                    val scan: DiscoveryViewModel = viewModel(key = "settings-discovery", factory = viewModelFactory {
                        initializer { DiscoveryViewModel(active.discovery) }
                    })
                    val result by scan.result.collectAsStateWithLifecycle()
                    LaunchedEffect(result) { if (result == ConnectionResult.RESPONDING) {
                        discovery = false; settings = false; model.signedOut(sessionVersion)
                    } }
                    DiscoveryScreen(scan, onBack = { discovery = false })
                } else if (settings) {
                    SettingsHost(active, user.id, reachable, onSignedOut = { settings = false; model.signedOut(sessionVersion) },
                        onChangeHub = { settings = false; discovery = true; model.signedOut(sessionVersion) }, onBack = { settings = false })
                } else CompositionLocalProvider(LocalOpenSettings provides { settings = true }) {
                    if (user.role == "STUDENT") org.lara.app.ui.screens.enrollment.LearnerEnrollmentNavigation(
                        active, user.id, shell, expired, sharedSession = syncSession)
                    else LaraNavigation(active.discovery, shell, sessionKey = user.id, teacherApprovalActions = {
                        org.lara.app.ui.screens.teacher.TeacherApprovalPanel(active, user.id, syncSession, expired)
                    })
                }
            }
        }
    }
}
