package org.lara.app.ui.screens.teacher.classes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.Cached
import org.lara.app.domain.enrollment.ClassCode
import org.lara.app.ui.theme.*
import org.lara.app.ui.components.DiscoveryBanner
import kotlinx.coroutines.flow.StateFlow

@Composable
fun ClassManagementEntry(container: AppContainer, userId: String, reachable: StateFlow<Boolean>, expired: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
        Text(stringResource(R.string.classes_manage))
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PopupLanguage {
            val latestExpired by rememberUpdatedState(expired)
            val model: ClassManagementViewModel = viewModel(key = "class-management-$userId", factory = viewModelFactory {
                initializer { ClassManagementViewModel(container, userId, reachable) { latestExpired() } }
            })
            val online by reachable.collectAsStateWithLifecycle()
            ClassManagementScreen(model, userId, online) { open = false }
        }
    }
}

@Composable
private fun ClassManagementScreen(model: ClassManagementViewModel, userId: String, online: Boolean, close: () -> Unit) {
    val cached by model.classrooms.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val classes = (cached as? Cached.Content)?.value.orEmpty().filter { userId in it.teacherIds() }
    val selected = classes.find { it.id == state.selected }
    var form by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var settingsTab by rememberSaveable(state.selected) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val enabled = online && !state.busy
    BackHandler { if (form != null) form = null else close() }
    Scaffold(containerColor = LaraColors.Canvas) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            item { Text(stringResource(R.string.classes_manage), style = MaterialTheme.typography.headlineMedium) }
            item { OutlinedButton(onClick = close, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.settings_back)) } }
            if (!online) item { DiscoveryBanner(R.string.classes_offline, warning = true, success = false) }
            else state.error?.let { message -> item { DiscoveryBanner(message, warning = true, success = false) } }
            item { Button(enabled = enabled, onClick = { name = ""; section = ""; form = "create" },
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.classes_create)) } }
            item { OutlinedButton(enabled = enabled, onClick = model::refresh, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.classes_refresh))
            } }
            if (state.busy || cached == Cached.Loading) item { org.lara.app.ui.screens.teacher.ApprovalLoadingSkeleton() }
            if (cached != Cached.Loading && classes.isEmpty()) item { Text(stringResource(R.string.classes_empty)) }
            items(classes, key = { it.id }) { row ->
                OutlinedButton(onClick = { model.select(row.id) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                    Column { Text(row.name); Text(row.section); if (row.archivedAt != null) Text(stringResource(R.string.classes_archived)) }
                }
            }
            selected?.let { row ->
                item {
                    Text(row.name, style = MaterialTheme.typography.headlineSmall)
                    val code = ClassCode.canonicalOrNull(row.classCode)?.let { it.take(3) + "-" + it.drop(3) }.orEmpty()
                    Text(code, style = MaterialTheme.typography.headlineLarge)
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.classes_copy))
                    }
                }
                item {
                    val owner = row.canManage(userId, true)
                    TabRow(selectedTabIndex = if (settingsTab && owner) 1 else 0) {
                        Tab(selected = !settingsTab || !owner, onClick = { settingsTab = false },
                            modifier = Modifier.heightIn(min = MinTouchTarget), text = { Text(stringResource(R.string.classes_people)) })
                        if (owner) Tab(selected = settingsTab, onClick = { settingsTab = true },
                            modifier = Modifier.heightIn(min = MinTouchTarget), text = { Text(stringResource(R.string.classes_rename)) })
                    }
                }
                if (settingsTab && row.canManage(userId, true)) item {
                    Button(enabled = enabled, onClick = { name = row.name; section = row.section; form = "rename" },
                        modifier = Modifier.heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.classes_rename)) }
                    OutlinedButton(enabled = enabled, onClick = { form = "archive" }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.classes_archive))
                    }
                    OutlinedButton(enabled = enabled, onClick = { name = ""; form = "add" }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.classes_add_teacher))
                    }
                }
                if (!settingsTab || !row.canManage(userId, true)) {
                item { Text(stringResource(R.string.classes_teachers), style = MaterialTheme.typography.titleLarge) }
                items(row.teacherIds().distinct(), key = { "teacher:$it" }) { id ->
                    Text(stringResource(if (id == row.teacherId) R.string.classes_owner else R.string.classes_teacher, id))
                    if (id != row.teacherId && row.canManage(userId, true)) OutlinedButton(enabled = enabled,
                        onClick = { target = id; form = "removeTeacher" }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.classes_remove_teacher))
                    }
                }
                item { Text(stringResource(R.string.classes_learners), style = MaterialTheme.typography.titleLarge) }
                val members = (state.roster as? Cached.Content)?.value.orEmpty()
                if (state.roster == Cached.Loading) item { org.lara.app.ui.screens.teacher.ApprovalLoadingSkeleton() }
                else if (members.isEmpty()) item { Text(stringResource(R.string.classes_no_learners)) }
                items(members, key = { "learner:${it.enrollment.id}" }) { member ->
                    Text(member.user?.fullName ?: stringResource(R.string.classes_unnamed))
                    Text(stringResource(when (member.enrollment.status) {
                        "ACTIVE" -> R.string.classes_active; "PENDING" -> R.string.classes_pending
                        "REMOVED" -> R.string.classes_removed; else -> R.string.classes_rejected
                    }))
                    if (member.enrollment.status == "ACTIVE" && row.canManage(userId, false)) OutlinedButton(enabled = enabled,
                        onClick = { target = member.enrollment.studentId; form = "removeLearner" }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.classes_remove_learner))
                    }
                }
                }
            }
        }
    }
    form?.let { action ->
        val editing = action in setOf("create", "rename", "add")
        AlertDialog(onDismissRequest = { form = null }, title = { PopupLanguage { Text(stringResource(when(action) {
            "create" -> R.string.classes_create; "rename" -> R.string.classes_rename; "add" -> R.string.classes_add_teacher
            "archive" -> R.string.classes_archive; "removeTeacher" -> R.string.classes_remove_teacher; else -> R.string.classes_remove_learner
        })) } }, text = { PopupLanguage { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            if (editing) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(if (action == "add") R.string.classes_teacher_id else R.string.classes_name)) })
                if (action != "add") OutlinedTextField(section, { section = it }, label = { Text(stringResource(R.string.classes_section)) })
            } else Text(stringResource(when (action) {
                "archive" -> R.string.classes_archive_warning; "removeLearner" -> R.string.classes_remove_warning; else -> R.string.classes_teacher_warning
            }))
        } } }, confirmButton = { PopupLanguage { TextButton(enabled = enabled && (!editing || name.isNotBlank() && (action == "add" || section.isNotBlank())),
            onClick = {
                when(action) {
                    "create" -> model.create(name, section); "rename" -> model.rename(name, section)
                    "add" -> model.addTeacher(name); "archive" -> model.archive()
                    "removeTeacher" -> model.removeTeacher(target); else -> model.removeLearner(target)
                }; form = null
            }, modifier = Modifier.heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.classes_confirm)) } } },
            dismissButton = { PopupLanguage { TextButton(onClick = { form = null }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.enrollment_cancel_action))
            } } })
    }
}
