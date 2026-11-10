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
    var selectedId by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var management by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val flow = remember(container) { container.classrooms.classrooms(scope) }
    val cached by flow.collectAsStateWithLifecycle()
    val selected = (cached as? Cached.Content)?.value?.find { it.id == selectedId && userId in it.teacherIds() }
    LaunchedEffect(cached, selectedId) {
        if (cached is Cached.Content && selected == null) selectedId = null
    }
    BackHandler(enabled = selected != null) { selectedId = null }
    if (selected == null) ClassHomeScaffold(
        classes = (cached as? Cached.Content)?.value.orEmpty().filter { userId in it.teacherIds() }
            .map { HomeClass(it.id, it.name, it.section) },
        teacher = true, canAdd = shell.isHubReachable, onAdd = { management = "create" },
        onOpenClass = { selectedId = it }, onManageClasses = { management = "manage" },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            TeacherApprovalPanel(container, userId, session, expired, onOpenClass = { selectedId = it }, showManagementEntry = false)
        }
    } else CompositionLocalProvider(LocalClassChrome provides ClassChrome(selected.name, selected.section) { selectedId = null }) {
        LaraNavigation(container.discovery, shell, sessionKey = "$userId:${selected.id}", teacherApprovalActions = {
            TeacherApprovalPanel(container, userId, session, expired, selectedClassId = selected.id, showManagementEntry = false)
        }, content = { destination -> ClassroomLearningHost(container, userId, destination, fixedClassId = selected.id) },
            teacherPeopleContent = { close ->
                org.lara.app.ui.screens.teacher.classes.ClassManagementPage(container, userId, session.reachable, expired,
                    initialClassId = selected.id, embedded = true, close = close)
            })
    }
    management?.let { action ->
        Dialog(onDismissRequest = { management = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            org.lara.app.ui.screens.teacher.classes.ClassManagementPage(container, userId, session.reachable, expired,
                startCreating = action == "create", close = { management = null })
        }
    }
}
