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
            session.events, session.reachable, container.enrollmentNotices, { container.enrollments.refresh(userId); Unit }, { expired() },
            loadClassrooms = { container.sync.refreshClassrooms(userId) {
                container.enrollmentApi.classroomSnapshot(userId)
            } }) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val names by container.commentNames.collectAsStateWithLifecycle(emptyList())
    var selectedId by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    val selected = state.openableClass(selectedId)
    EnrollmentSession(model)
    LaunchedEffect(selectedId, state.classes, state.requests) {
        if (selectedId != null && state.classes is Cached.Content && selected == null) selectedId = null
    }
    BackHandler(enabled = selected != null) { selectedId = null }
    if (selected == null) {
        Scaffold(containerColor = LaraColors.Canvas, topBar = {
            org.lara.app.ui.components.ClassroomTopBar(stringResource(R.string.app_name))
        }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                EnrollmentScreen(state, model::showJoin, model::dismissJoin, model::submit,
                    onOpenClass = { id -> if (state.openableClass(id) != null) selectedId = id }, onCodeChanged = model::onInputChanged,
                    teacherNames = names.filter { it.role == "TEACHER" }.associate { it.id to it.name })
            }
        }
    } else {
        CompositionLocalProvider(org.lara.app.ui.components.LocalClassChrome provides
            org.lara.app.ui.components.ClassChrome(selected.classroom.name, selected.classroom.section) { selectedId = null }) {
        LaraNavigation(container.discovery, shell, sessionKey = "$userId:${selected.classroom.id}", content = { destination ->
            org.lara.app.ui.screens.stream.ClassroomLearningHost(container, userId, destination, selected.classroom.id)
        })
        }
    }
}
