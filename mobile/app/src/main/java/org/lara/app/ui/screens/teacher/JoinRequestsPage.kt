package org.lara.app.ui.screens.teacher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
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
import org.lara.app.data.sync.SyncSessionViewModel
import org.lara.app.domain.Cached
import org.lara.app.ui.components.ClassroomBackBar
import org.lara.app.ui.components.EmptyState
import org.lara.app.ui.screens.teacher.classes.teacherIds
import org.lara.app.ui.components.NativeConfirmDialog
import org.lara.app.ui.theme.*

/** Every learner waiting to join one of this teacher's classes, grouped by class. Shares the panel's session and roster models. */
@Composable
fun JoinRequestsPage(container: AppContainer, userId: String, syncSession: SyncSessionViewModel, onExpired: () -> Unit) {
    val session: TeacherApprovalSession = viewModel(key = "teacher-approvals-$userId", factory = viewModelFactory {
        initializer { TeacherApprovalSession(container, userId, syncSession) }
    })
    val reachable by syncSession.reachable.collectAsStateWithLifecycle()
    val cached by session.classrooms.collectAsStateWithLifecycle()
    val classes = (cached as? Cached.Content)?.value.orEmpty().filter { userId in it.teacherIds() && it.managedBy(userId) }
    val latestExpired by rememberUpdatedState(onExpired)
    val context = androidx.compose.ui.platform.LocalContext.current
    val offlineHint = stringResource(R.string.approval_offline_toast)
    // The shared session only knows it is online while some screen reports it; the home panel clears it when it leaves.
    LaunchedEffect(session, reachable) { session.setReachable(reachable) }
    Column(Modifier.fillMaxSize().background(LaraColors.Surface)) {
        val models = classes.map { it to rosterModel(container, userId, session, it, latestExpired) }
        val states = models.map { (_, m) -> m.state.collectAsStateWithLifecycle().value }
        val empty = cached != Cached.Loading && states.all { it.pending.isEmpty() }
        org.lara.app.ui.components.PullRefresh({ session.refresher.refreshClasses(); classes.forEach { c -> session.refresher.refresh(c.id) } }) {
        // Scrollable even when empty, so the pull still works on the empty state.
        if (empty) BoxWithConstraints(Modifier.fillMaxSize()) {
            // Scroll for the pull gesture, but keep the full height so the empty state stays centred.
            val height = maxHeight
            Box(Modifier.verticalScroll(rememberScrollState())) {
                EmptyState(R.drawable.ic_users_regular, stringResource(R.string.approval_empty), Modifier.height(height), hint = stringResource(R.string.approval_empty_hint))
            }
        }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            if (cached == Cached.Loading) item { org.lara.app.ui.components.LoadingSpinner(inline = true) }
            models.forEachIndexed { i, (classroom, model) ->
                val state = states[i]
                if (state.pending.isEmpty()) return@forEachIndexed
                item(key = "h-${classroom.id}") {
                    var confirmAll by remember { mutableStateOf(false) }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(classroom.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton({ if (reachable) confirmAll = true else android.widget.Toast.makeText(context, offlineHint, android.widget.Toast.LENGTH_SHORT).show() }, colors = ButtonDefaults.textButtonColors(contentColor = LaraColors.PrimaryDark)) {
                            Text(stringResource(R.string.approval_accept_all, state.pending.size))
                        }
                    }
                    if (confirmAll) NativeConfirmDialog(stringResource(R.string.approval_accept_all, state.pending.size), stringResource(R.string.approval_accept_all_body, classroom.name),
                        stringResource(R.string.approval_accept), stringResource(R.string.enrollment_cancel_action),
                        { confirmAll = false; state.pending.forEach { model.decide(it.enrollment.studentId, true) } }, { confirmAll = false })
                }
                items(state.pending, key = { "${classroom.id}-${it.enrollment.id}" }) { PendingMemberCard(it, state, model::decide) }
            }
        }
        }
    }
    LaunchedEffect(reachable) { if (reachable) classes.forEach { c -> session.refresher.refresh(c.id) } }
}

/** Pending learners of one class, for the People tab. Draws nothing when nobody is waiting. */
@Composable
fun ClassPendingRequests(container: AppContainer, userId: String, syncSession: SyncSessionViewModel, classroom: org.lara.app.domain.model.Classroom, onExpired: () -> Unit) {
    val session: TeacherApprovalSession = viewModel(key = "teacher-approvals-$userId", factory = viewModelFactory {
        initializer { TeacherApprovalSession(container, userId, syncSession) }
    })
    val reachable by syncSession.reachable.collectAsStateWithLifecycle()
    LaunchedEffect(session, reachable) { session.setReachable(reachable) }
    val model = rosterModel(container, userId, session, classroom, onExpired)
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(reachable) { if (reachable) session.refresher.refresh(classroom.id) }
    if (state.pending.isEmpty() || !classroom.managedBy(userId)) return
    Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
        Text(stringResource(R.string.approval_title), style = MaterialTheme.typography.titleMedium)
        state.pending.forEach { key(it.enrollment.id) { PendingMemberCard(it, state, model::decide) } }
    }
}
