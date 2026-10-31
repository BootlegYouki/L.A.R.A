package org.lara.app.ui.screens.teacher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.Cached
import org.lara.app.ui.theme.LaraSpacing

@Composable
fun TeacherApprovalPanel(container: AppContainer, userId: String, reachable: Boolean, onSessionExpired: () -> Unit) {
    val session: TeacherApprovalSession = viewModel(key = "teacher-approvals-$userId", factory = viewModelFactory {
        initializer { TeacherApprovalSession(container, userId) }
    })
    val latestExpired by rememberUpdatedState(onSessionExpired)
    LaunchedEffect(session, reachable) { session.setReachable(reachable) }
    DisposableEffect(session) { onDispose { session.setReachable(false) } }
    val cached by session.classrooms.collectAsStateWithLifecycle()
    val classes = (cached as? Cached.Content)?.value.orEmpty().filter { it.managedBy(userId) }
    Column(Modifier.fillMaxWidth().heightIn(max = LaraSpacing.S12 * 4).verticalScroll(rememberScrollState())
        .padding(horizontal = LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
        if (cached == Cached.Loading) Text(stringResource(R.string.approval_loading))
        else if (classes.isEmpty()) Text(stringResource(R.string.approval_no_saved_classes))
        classes.forEach { classroom ->
            key(classroom.id) {
                val model: RosterViewModel = viewModel(key = "approval-$userId-${classroom.id}", factory = viewModelFactory {
                    initializer { RosterViewModel(classroom.id, classroom.classCode, container.enrollments,
                        session.remote, session.reachable, session.events, session.refresher::refresh, { latestExpired() }) }
                })
                Text(classroom.name, style = MaterialTheme.typography.titleMedium)
                TeacherApprovalHost(model)
            }
        }
    }
}
