package org.lara.app.ui.screens.teacher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.data.sync.SyncSessionViewModel
import org.lara.app.domain.Cached
import org.lara.app.ui.components.*
import org.lara.app.ui.navigation.*
import org.lara.app.ui.screens.stream.ClassroomLearningHost
import org.lara.app.ui.theme.LaraColors
import org.lara.app.ui.screens.teacher.classes.teacherIds

@Composable
fun TeacherClassroomNavigation(container: AppContainer, userId: String, shell: ShellState,
    session: SyncSessionViewModel, expired: () -> Unit) {
    var management by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var requests by rememberSaveable(userId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val flow = remember(container) { container.classrooms.classrooms(scope) }
    val cached by flow.collectAsStateWithLifecycle()
    val managed = (cached as? Cached.Content)?.value.orEmpty().filter { userId in it.teacherIds() && it.managedBy(userId) }.map { it.id }
    val joinRequestCount by remember(managed) { container.pendingJoinCount(managed) }.collectAsStateWithLifecycle(0)
    org.lara.app.ui.components.ClassNav { selectedId, open, home ->
    val selected = (cached as? Cached.Content)?.value?.find { it.id == selectedId && userId in it.teacherIds() }
    LaunchedEffect(cached, selectedId) {
        if (selectedId != null && cached is Cached.Content && selected == null) home()
    }
    if (selected != null) org.lara.app.ui.components.ClassScreen {
        CompositionLocalProvider(LocalClassChrome provides ClassChrome(selected.name, selected.section, edit = { management = "edit" }, info = { management = "info" }, back = home)) {
            LaraNavigation(container.discovery, shell, sessionKey = "$userId:${selected.id}", content = { destination -> ClassroomLearningHost(container, userId, destination, fixedClassId = selected.id) },
                teacherPeopleContent = { close ->
                    org.lara.app.ui.screens.teacher.classes.ClassManagementPage(container, userId, session.reachable, expired,
                        initialClassId = selected.id, embedded = true,
                        pendingContent = { ClassPendingRequests(container, userId, session, selected, expired) }, close = close)
                })
        }
    } else if (selectedId == null) ClassHomeScaffold(
        classes = (cached as? Cached.Content)?.value.orEmpty().filter { userId in it.teacherIds() }
            .map { HomeClass(it.id, it.name, it.section) },
        offline = if (cached is Cached.Content) !shell.isHubReachable else null, joinRequestCount = joinRequestCount,
        teacher = true, canAdd = shell.isHubReachable, onAdd = { management = "create" },
        onOpenClass = { open(it); requests = false }, onJoinRequests = { requests = true },
        onHome = { home(); requests = false },
        pageTitle = if (requests) stringResource(R.string.approval_title) else null,
    ) { insets ->
        if (requests) {
            BackHandler { requests = false }
            Box(Modifier.fillMaxSize().padding(insets)) { JoinRequestsPage(container, userId, session, expired) }
        } else Box(Modifier.fillMaxSize().padding(insets)) {
            TeacherApprovalPanel(container, userId, session, expired, onOpenClass = open, showManagementEntry = false)
        }
    }
    management?.takeIf { (it == "create") == (selected == null) }?.let { action ->
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { management = null },
            containerColor = org.lara.app.ui.theme.LaraColors.Surface) {
            if (action == "info" && selected != null) org.lara.app.ui.screens.teacher.classes.ClassInfoSheet(selected)
            else org.lara.app.ui.screens.teacher.classes.EditClassPage(container, userId, session.reachable, expired, if (action == "edit") selected?.id else null) { management = null }
        }
    }
    }
}
