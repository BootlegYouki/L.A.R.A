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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.filterNot
import org.lara.app.domain.enrollment.ClassCode
import org.lara.app.domain.enrollment.HubEvent
import org.lara.app.ui.components.DiscoveryBanner

@Composable
fun TeacherApprovalPanel(container: AppContainer, userId: String, reachable: Boolean, onSessionExpired: () -> Unit) {
    val session: TeacherApprovalSession = viewModel(key = "teacher-approvals-$userId", factory = viewModelFactory {
        initializer { TeacherApprovalSession(container, userId) }
    })
    val latestExpired by rememberUpdatedState(onSessionExpired)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(session, reachable, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            session.setReachable(reachable)
            try { session.events.collect { if (it == HubEvent.SessionExpired) latestExpired() } }
            finally { session.setReachable(false) }
        }
    }
    DisposableEffect(session) { onDispose { session.setReachable(false) } }
    val cached by session.classrooms.collectAsStateWithLifecycle()
    val pendingCodes by session.pendingClassCodes.collectAsStateWithLifecycle()
    val error by session.error.collectAsStateWithLifecycle()
    val refreshing by session.refreshingClasses.collectAsStateWithLifecycle()
    val classes = (cached as? Cached.Content)?.value.orEmpty().filter { it.managedBy(userId) }
    Column(Modifier.fillMaxWidth().heightIn(max = LaraSpacing.S12 * 4).verticalScroll(rememberScrollState())
        .padding(horizontal = LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
        if (error != null && reachable) DiscoveryBanner(R.string.approval_failed, warning = true, success = false)
        if (cached == Cached.Loading || classes.isEmpty() && refreshing) ApprovalLoadingSkeleton()
        else if (classes.isEmpty()) Text(stringResource(if (reachable) R.string.approval_no_classes else R.string.approval_no_saved_classes))
        classes.forEach { classroom ->
            key(classroom.id) {
                val model: RosterViewModel = viewModel(key = "approval-$userId-${classroom.id}", factory = viewModelFactory {
                    initializer { RosterViewModel(classroom.id, classroom.classCode, container.enrollments,
                        session.remote, session.reachable, session.events.filterNot { it is HubEvent.JoinRequest }, session.refresher::refresh, { latestExpired() }) }
                })
                val code = ClassCode.canonicalOrNull(classroom.classCode)
                LaunchedEffect(model, code, pendingCodes) {
                    if (code != null && code in pendingCodes) {
                        model.onJoinRequested()
                        session.consumeJoinRequest(code)
                    }
                }
                Text(classroom.name, style = MaterialTheme.typography.titleMedium)
                TeacherApprovalHost(model)
            }
        }
    }
}
