package org.lara.app.ui.screens.enrollment

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.enrollment.EnrollmentSessionSource
import org.lara.app.domain.Cached
import org.lara.app.ui.navigation.*
import org.lara.app.ui.theme.*

@Composable
fun LearnerEnrollmentNavigation(container: AppContainer, userId: String, shell: ShellState,
    onSessionExpired: () -> Unit, sharedSession: EnrollmentSessionSource? = null) {
    val session = sharedSession ?: run {
        val connection: EnrollmentConnectionViewModel = viewModel(key = "enrollment-connection-$userId", factory = viewModelFactory {
            initializer { EnrollmentConnectionViewModel(container.enrollmentApi, userId) }
        })
        LaunchedEffect(connection, shell.isHubReachable) { connection.setReachable(shell.isHubReachable) }
        DisposableEffect(connection) { onDispose { connection.setReachable(false) } }
        connection
    }
    val expired by rememberUpdatedState(onSessionExpired)
    val model: EnrollmentViewModel = viewModel(key = "enrollment-$userId", factory = viewModelFactory {
        initializer { EnrollmentViewModel(userId, container.enrollments, container.enrollmentApi,
            session.events, session.reachable, container.enrollmentNotices, { container.enrollments.refresh(userId); Unit }, { expired() }) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    val selected = state.openableClass(selectedId)
    EnrollmentSession(model)
    LaunchedEffect(selectedId, state.classes, state.requests) {
        if (selectedId != null && state.classes is Cached.Content && selected == null) selectedId = null
    }
    BackHandler(enabled = selected != null) { selectedId = null }
    if (selected == null) {
        Scaffold(containerColor = LaraColors.Canvas, topBar = {
            Surface(color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface) {
                Text(stringResource(R.string.learner_classroom), Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(LaraSpacing.S4), style = MaterialTheme.typography.titleLarge)
            }
        }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                EnrollmentScreen(state, model::showJoin, model::dismissJoin, model::submit,
                    onOpenClass = { id -> if (state.openableClass(id) != null) selectedId = id }, onCodeChanged = model::onInputChanged)
            }
        }
    } else {
        LaraNavigation(container.discovery, shell, sessionKey = "$userId:${selected.classroom.id}", learnerClassActions = {
            Column(Modifier.fillMaxWidth().padding(horizontal = LaraSpacing.S4)) {
                TextButton(onClick = { selectedId = null }, modifier = Modifier.heightIn(min = MinTouchTarget),
                    colors = ButtonDefaults.textButtonColors(contentColor = LaraColors.PrimaryDark)) {
                    Text(stringResource(R.string.enrollment_all_classes))
                }
                Text(selected.classroom.name, style = MaterialTheme.typography.titleMedium)
            }
        })
    }
}
