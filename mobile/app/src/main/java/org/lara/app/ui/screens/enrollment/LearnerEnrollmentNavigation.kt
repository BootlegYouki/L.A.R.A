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
import org.lara.app.ui.components.ClassHomeScaffold
import org.lara.app.ui.components.HomeClass

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
    EnrollmentSession(model)
    org.lara.app.ui.components.ClassNav { selectedId, open, home ->
    val selected = state.openableClass(selectedId)
    LaunchedEffect(selectedId, state.classes, state.requests) {
        if (selectedId != null && state.classes is Cached.Content && selected == null) home()
    }
    if (selected != null) org.lara.app.ui.components.ClassScreen {
        var info by rememberSaveable(selected.classroom.id) { mutableStateOf(false) }
        CompositionLocalProvider(org.lara.app.ui.components.LocalClassChrome provides
            org.lara.app.ui.components.ClassChrome(selected.classroom.name, selected.classroom.section, info = { info = true }, back = home)) {
            LaraNavigation(container.discovery, shell, sessionKey = "$userId:${selected.classroom.id}", content = { destination ->
                org.lara.app.ui.screens.stream.ClassroomLearningHost(container, userId, destination, selected.classroom.id)
            })
        }
        if (info) @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { info = false },
            containerColor = org.lara.app.ui.theme.LaraColors.Surface) {
            org.lara.app.ui.screens.teacher.classes.ClassInfoSheet(selected.classroom)
        }
    } else if (selectedId == null) ClassHomeScaffold(
        classes = (state.classes as? Cached.Content)?.value.orEmpty().filter { state.openableClass(it.classroom.id) != null }
            .map { HomeClass(it.classroom.id, it.classroom.name, it.classroom.section) },
        teacher = false, canAdd = !state.offline && !state.busy, onAdd = model::showJoin, offline = if (state.classes is Cached.Content) state.offline else null,
        onOpenClass = { id -> if (state.openableClass(id) != null) open(id) }, onHome = home,
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            org.lara.app.ui.components.PullRefresh({ model.reload() }) { EnrollmentScreen(state, model::showJoin, model::dismissJoin, model::submit,
                onOpenClass = { id -> if (state.openableClass(id) != null) open(id) }, onCodeChanged = model::onInputChanged,
                teacherNames = names.filter { it.role == "TEACHER" }.associate { it.id to it.name }, showJoinAction = false,
                learnerName = names.find { it.id == userId }?.name) }
        }
    }
    }
}
