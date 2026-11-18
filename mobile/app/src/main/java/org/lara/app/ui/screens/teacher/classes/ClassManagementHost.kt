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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
import org.lara.app.ui.components.ClassroomActionRow
import org.lara.app.ui.components.ClassFormDialog
import org.lara.app.ui.components.classFormInputScroll
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

@Composable
fun ClassManagementEntry(container: AppContainer, userId: String, reachable: StateFlow<Boolean>, expired: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
        Text(stringResource(R.string.classes_manage))
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ClassManagementPage(container, userId, reachable, expired, close = { open = false })
    }
}

@Composable
fun ClassManagementPage(container: AppContainer, userId: String, reachable: StateFlow<Boolean>, expired: () -> Unit,
    initialClassId: String? = null, embedded: Boolean = false, startCreating: Boolean = false,
    pendingContent: (@Composable () -> Unit)? = null, close: () -> Unit) {
    val latestExpired by rememberUpdatedState(expired)
    val model: ClassManagementViewModel = viewModel(key = "class-management-$userId", factory = viewModelFactory {
        initializer { ClassManagementViewModel(container, userId, reachable) { latestExpired() } }
    })
    LaunchedEffect(model, initialClassId) {
        if (initialClassId != null) {
            model.state.first { !it.busy }
            model.select(initialClassId)
        }
    }
    val online by reachable.collectAsStateWithLifecycle()
    ClassManagementScreen(model, userId, online, close, embedded, startCreating, pendingContent)
}

@Composable
private fun ClassManagementScreen(model: ClassManagementViewModel, userId: String, online: Boolean, close: () -> Unit,
    embedded: Boolean = false, startCreating: Boolean = false, pendingContent: (@Composable () -> Unit)? = null) {
    val cached by model.classrooms.collectAsStateWithLifecycle()
    val teacherNames by model.teacherNames.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val classes = (cached as? Cached.Content)?.value.orEmpty().filter { userId in it.teacherIds() }
    val selected = classes.find { it.id == state.selected }
    var form by rememberSaveable { mutableStateOf<String?>(if (startCreating) "create" else null) }
    var name by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var settingsTab by rememberSaveable(state.selected) { mutableStateOf(false) }
    var review by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(form) { review = false }
    val clipboard = LocalClipboardManager.current
    val enabled = online && !state.busy
    BackHandler { if (form != null) form = null else close() }
    Scaffold(containerColor = LaraColors.Canvas,
        contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets, topBar = {
        if (!embedded) org.lara.app.ui.components.ClassroomTopBar(stringResource(R.string.classes_manage), selected?.name, close)
    }) { padding ->
        org.lara.app.ui.components.PullRefresh({ model.reload() }, Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            if (online) state.error?.let { message -> item { DiscoveryBanner(message, warning = true, success = false) } }
            if (!embedded) item { Button(enabled = enabled, onClick = { name = ""; section = ""; form = "create" },
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.classes_create)) } }
            if (!embedded) item { OutlinedButton(enabled = enabled, onClick = model::refresh, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.classes_refresh))
            } }
            if (cached == Cached.Loading) item { org.lara.app.ui.components.LoadingSpinner(inline = true) }
            if (cached != Cached.Loading && classes.isEmpty()) item { Text(stringResource(R.string.classes_empty)) }
            if (!embedded) items(classes, key = { it.id }) { row ->
                ManagedClassCard(row, selected?.id == row.id, !state.busy) { model.select(row.id) }
            }
            selected?.let { row ->
                if (!embedded) item {
                    ClassInfoCard {
                    Text(stringResource(R.string.classes_details), style = MaterialTheme.typography.titleMedium, color = LaraColors.PrimaryDark)
                    Text(stringResource(R.string.classes_name), style = MaterialTheme.typography.labelLarge)
                    Text(row.name, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.classes_section), style = MaterialTheme.typography.labelLarge)
                    Text(row.section, style = MaterialTheme.typography.bodyLarge, color = LaraColors.TextSecondary)
                    val code = ClassCode.canonicalOrNull(row.classCode)?.let { it.take(3) + "-" + it.drop(3) }.orEmpty()
                    Surface(shape = LaraShapes.medium, color = LaraColors.PrimaryLight) {
                        Column(Modifier.fillMaxWidth().padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                            Text(stringResource(R.string.classes_code), style = MaterialTheme.typography.labelLarge, color = LaraColors.PrimaryDark)
                            Text(code, style = MaterialTheme.typography.headlineLarge, color = LaraColors.PrimaryDark)
                        }
                    }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.classes_copy))
                    }
                    Text(stringResource(R.string.classes_code_help), color = LaraColors.TextSecondary)
                    }
                }
                if (!embedded) item {
                    val owner = row.canManage(userId, true)
                    TabRow(selectedTabIndex = if (settingsTab && owner) 1 else 0) {
                        Tab(selected = !settingsTab || !owner, onClick = { settingsTab = false },
                            modifier = Modifier.heightIn(min = MinTouchTarget), text = { Text(stringResource(R.string.classes_people)) })
                        if (owner) Tab(selected = settingsTab, onClick = { settingsTab = true },
                            modifier = Modifier.heightIn(min = MinTouchTarget), text = { Text(stringResource(R.string.classes_rename)) })
                    }
                }
                if (settingsTab && row.canManage(userId, true)) item {
                    ClassInfoCard {
                    ClassroomActionRow(stringResource(R.string.classes_edit), icon = R.drawable.ic_pencil_simple,
                        enabled = enabled, onClick = { name = row.name; section = row.section; form = "rename" })
                    HorizontalDivider(color = LaraColors.Border)
                    ClassroomActionRow(stringResource(R.string.classes_add_teacher), icon = R.drawable.ic_users_regular,
                        enabled = enabled, onClick = { name = ""; form = "add" })
                    HorizontalDivider(color = LaraColors.Border)
                    ClassroomActionRow(stringResource(R.string.classes_archive), stringResource(R.string.classes_archive_help),
                        R.drawable.ic_archive, enabled = enabled, onClick = { form = "archive" })
                    }
                }
                if (!settingsTab || !row.canManage(userId, true)) {
                item { Text(stringResource(R.string.classes_teachers), style = MaterialTheme.typography.titleMedium) }
                items(row.teacherIds().distinct(), key = { "teacher:$it" }) { id ->
                    PersonRow(teacherNames.find { it.id == id }?.full_name ?: id,
                        stringResource(if (id == row.teacherId) R.string.classes_owner_role else R.string.classes_co_role)) {
                    if (id != row.teacherId && row.canManage(userId, true)) IconButton({ target = id; form = "removeTeacher" },
                        Modifier.size(MinTouchTarget), enabled) {
                        Icon(painterResource(R.drawable.ic_x), stringResource(R.string.classes_remove_teacher), Modifier.size(24.dp))
                    }
                    }
                }
                if (pendingContent != null) item { pendingContent() }
                item { Text(stringResource(R.string.classes_learners), style = MaterialTheme.typography.titleMedium) }
                val members = (state.roster as? Cached.Content)?.value.orEmpty()
                if (state.roster == Cached.Loading) item { org.lara.app.ui.components.LoadingSpinner(inline = true) }
                else if (members.none { it.enrollment.status != "PENDING" }) item { Text(stringResource(R.string.classes_no_learners)) }
                // Waiting learners are reviewed on the sidebar's Join requests page.
                items(members.filter { it.enrollment.status != "PENDING" }, key = { "learner:${it.enrollment.id}" }) { member ->
                    PersonRow(member.user?.fullName ?: stringResource(R.string.classes_unnamed), stringResource(when (member.enrollment.status) {
                        "ACTIVE" -> R.string.classes_active; "PENDING" -> R.string.classes_pending
                        "REMOVED" -> R.string.classes_removed; else -> R.string.classes_rejected
                    })) {
                    if (member.enrollment.status == "ACTIVE" && row.canManage(userId, false)) IconButton({ target = member.enrollment.studentId; form = "removeLearner" },
                        Modifier.size(MinTouchTarget), enabled) {
                        Icon(painterResource(R.drawable.ic_x), stringResource(R.string.classes_remove_learner), Modifier.size(24.dp))
                    }
                    }
                }
                }
            }
        }
        }
    }
    form?.let { action ->
        if (action == "create") {
            CreateClassDialog(name, section, review, online, state.busy,
                { name = it }, { section = it }, { review = true }, { review = false },
                { model.create(name, section); form = null }, { form = null })
        } else if (action == "rename" || action == "add") {
            ClassFormDialog(title = stringResource(if (action == "rename") R.string.classes_rename else R.string.classes_add_teacher),
                action = stringResource(if (action == "rename") R.string.classes_save else R.string.classes_add_action),
                enabled = enabled && name.isNotBlank() && (action == "add" || section.isNotBlank()),
                onAction = { if (action == "rename") model.rename(name, section) else model.addTeacher(name); form = null },
                onDismiss = { form = null }) {
                Text(stringResource(if (action == "rename") R.string.classes_details else R.string.classes_teachers),
                    style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    val label = stringResource(if (action == "add") R.string.classes_teacher_id else R.string.classes_name)
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                        .classFormInputScroll().semantics { contentDescription = label }, shape = LaraShapes.medium)
                }
                if (action == "rename") Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    val label = stringResource(R.string.classes_section)
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(section, { section = it }, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                        .classFormInputScroll().semantics { contentDescription = label }, shape = LaraShapes.medium)
                }
            }
        } else {
            org.lara.app.ui.components.NativeConfirmDialog(
                stringResource(when (action) { "archive" -> R.string.classes_archive; "removeTeacher" -> R.string.classes_remove_teacher; else -> R.string.classes_remove_learner }),
                stringResource(when (action) { "archive" -> R.string.classes_archive_warning; "removeLearner" -> R.string.classes_remove_warning; else -> R.string.classes_teacher_warning }) +
                    (selected?.takeIf { action == "archive" }?.let { "\n\n${it.name}\n${it.section}" } ?: ""),
                stringResource(if (action == "archive") R.string.classes_archive else R.string.classes_confirm), stringResource(R.string.enrollment_cancel_action),
                { if (enabled) { when (action) { "archive" -> model.archive(); "removeTeacher" -> model.removeTeacher(target); else -> model.removeLearner(target) } }; form = null },
                { form = null })
        }
    }
}
