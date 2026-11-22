package org.lara.app.ui.screens.teacher.classwork

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.lara.app.R
import org.lara.app.domain.*
import org.lara.app.domain.model.*
import org.lara.app.ui.components.ClassFormDialog
import org.lara.app.ui.components.classFormInputScroll
import org.lara.app.ui.theme.*
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
internal fun TeacherClasswork(repository: ClassworkAuthoringRepository, userId: String, classroom: Classroom, work: Classwork,
    announcements: List<Announcement>, reachable: Boolean, openMaterial: (String) -> Unit,
    unreadCommentsByAssignment: Map<String, Int> = emptyMap(), openReview: (String) -> Unit) {
    val model: ClassworkAuthoringViewModel = viewModel(key = "author-$userId-${classroom.id}", factory = viewModelFactory {
        initializer { ClassworkAuthoringViewModel(repository) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val uploads by remember(repository, classroom.id) { repository.uploads(classroom.id) }.collectAsStateWithLifecycle(emptyList())
    var form by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var topicFilter by rememberSaveable(classroom.id) { mutableStateOf<String?>(null) }
    var confirmRetry by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTopic = topicFilter?.takeIf { id -> work.topics.any { it.id == id } }
    val canWrite = reachable && classroom.archivedAt == null && !state.busy
    fun edit(kind: String, id: String? = null) { model.reset(); selectedId = id; form = kind }
    LaunchedEffect(state.completed) { if (state.completed > 0) { form = null; deleting = null } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(LaraSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        item {
            Box {
                Button(onClick = { menu = true }, enabled = classroom.archivedAt == null,
                    modifier = Modifier.heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.author_create)) }
                DropdownMenu(menu, { menu = false }, containerColor = LaraColors.Surface) {
                    listOf("assignments" to R.string.author_assignment, "materials" to R.string.author_material,
                        "video" to R.string.author_video, "topics" to R.string.author_topic).forEach { (kind, label) ->
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { menu = false; edit(kind) }, modifier = Modifier.heightIn(min = MinTouchTarget))
                    }
                }
            }
        }
        if (!reachable) item { Text(stringResource(R.string.material_staging_offline), color = LaraColors.TextSecondary) }
        state.error?.let { error -> item { Text(stringResource(error), color = MaterialTheme.colorScheme.error) } }
        items(uploads.filter { it.state != "SUCCEEDED" }, key = { "upload-${it.id}" }) { upload ->
            Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                Text(upload.title.ifBlank { stringResource(R.string.author_uploading) })
                Text(stringResource(when (upload.state) { "FAILED" -> R.string.author_upload_failed; "UNCERTAIN" -> R.string.parity_upload_uncertain; "RUNNING" -> R.string.author_upload_percent; else -> R.string.author_upload_waiting }, upload.percent))
                LinearProgressIndicator(progress = { upload.percent / 100f }, color = LaraColors.Primary, trackColor = LaraColors.PrimaryLight, modifier = Modifier.fillMaxWidth().height(12.dp))
                if (upload.state == "FAILED") TextButton(onClick = { model.run { repository.retry(upload.id) } }, enabled = canWrite,
                    modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.author_retry)) }
                if (upload.state == "UNCERTAIN") TextButton(onClick = { confirmRetry = upload.id }, enabled = canWrite,
                    modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.author_retry)) }
                TextButton(onClick = { model.run(close = false) { repository.cancel(upload.id) } },
                    enabled = !state.busy, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(if (upload.state == "UNCERTAIN") R.string.parity_upload_dismiss else R.string.parity_upload_cancel))
                }
            }
        }
        if (work.topics.isNotEmpty()) item {
            TopicFilter(work.topics, selectedTopic) { topicFilter = it }
        }
        val visible = work.materials.filter { it.assignmentId == null }
        if (visible.isEmpty() && work.assignments.isEmpty() && work.topics.isEmpty()) item {
            org.lara.app.ui.screens.stream.ReadingEmpty(R.string.reading_work_empty)
        }
        (work.topics.map { it.id } + listOf<String?>(null)).filter { selectedTopic == null || it == selectedTopic }.forEach { topicId ->
            val topic = work.topics.find { it.id == topicId }
            val assignments = work.assignments.filter { it.topicId == topicId || (topicId == null && work.topics.none { t -> t.id == it.topicId }) }
            val materials = visible.filter { it.topicId == topicId || (topicId == null && work.topics.none { t -> t.id == it.topicId }) }
            if (topic != null || assignments.isNotEmpty() || materials.isNotEmpty()) {
                item(key = "topic-$topicId") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(topic?.name ?: stringResource(R.string.reading_no_topic), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge, color = LaraColors.PrimaryDark)
                        if (topic != null) ItemMenu(canWrite, { edit("topics", topic.id) }, { selectedId = topic.id; deleting = "topics" })
                    }
                    HorizontalDivider(color = LaraColors.Primary)
                }
                items(assignments, key = { "assignment-${it.id}" }) { a ->
                    AuthorRow(a.title, stringResource(R.string.classwork_due, DateFormat.getDateTimeInstance().format(Date(a.dueDate))), R.drawable.ic_exam_regular,
                        { openReview(a.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S1)) {
                            unreadCommentsByAssignment[a.id]?.takeIf { it > 0 }?.let { count ->
                                Surface(color = LaraColors.InfoLight, shape = LaraShapes.small) {
                                    Text(stringResource(R.string.teacher_private_unread, count),
                                        Modifier.padding(horizontal = LaraSpacing.S2, vertical = LaraSpacing.S1),
                                        style = MaterialTheme.typography.labelMedium, color = LaraColors.InfoDark)
                                }
                            }
                            ItemMenu(canWrite, { edit("assignments", a.id) }, { selectedId = a.id; deleting = "assignments" })
                        }
                    }
                }
                items(materials, key = { "material-${it.id}" }) { m ->
                    AuthorRow(m.title, stringResource(R.string.classwork_posted, DateFormat.getDateInstance().format(Date(m.createdAt))),
                        if (m.fileType == "VIDEO") R.drawable.ic_play else R.drawable.ic_books_regular, { openMaterial(m.id) }) {
                        ItemMenu(canWrite, { edit("materials", m.id) }, { selectedId = m.id; deleting = "materials" })
                    }
                }
            }
        }
    }
    confirmRetry?.let { id ->
        AlertDialog(onDismissRequest = { confirmRetry = null },
            title = { Text(stringResource(R.string.author_retry)) },
            text = { Text(stringResource(R.string.parity_upload_duplicate)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRetry = null
                    model.run(close = false) { repository.retryUncertain(id) }
                }, enabled = canWrite, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.author_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRetry = null }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.enrollment_cancel_action))
                }
            })
    }
    form?.let { kind -> key(kind, selectedId) {
        AuthoringForm(kind, classroom, selectedId, work, announcements, reachable, state, model, onClose = { form = null; model.reset() })
    } }
    deleting?.let { kind ->
        AlertDialog(onDismissRequest = { if (!state.busy) deleting = null }, title = { Text(stringResource(R.string.author_delete_title)) },
            text = { Column {
                Text(stringResource(when (kind) { "assignments" -> R.string.author_delete_assignment; "topics" -> R.string.author_delete_topic; else -> R.string.author_delete_material }))
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = {
                TextButton(onClick = { selectedId?.let { id -> model.run { repository.delete(kind, id) } } }, enabled = canWrite,
                    modifier = Modifier.heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.author_delete), color = MaterialTheme.colorScheme.error) }
            }, dismissButton = { TextButton(onClick = { deleting = null }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.enrollment_cancel_action)) } })
    }
}

@Composable
private fun ItemMenu(enabled: Boolean, edit: () -> Unit, delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(MinTouchTarget)) {
            Icon(painterResource(R.drawable.ic_dots_three_vertical), stringResource(R.string.author_item_menu))
        }
        DropdownMenu(menu, { menu = false }, containerColor = LaraColors.Surface) {
            DropdownMenuItem(text = { Text(stringResource(R.string.author_edit)) }, onClick = { menu = false; edit() },
                enabled = enabled, modifier = Modifier.heightIn(min = MinTouchTarget))
            DropdownMenuItem(text = { Text(stringResource(R.string.author_delete)) }, onClick = { menu = false; delete() },
                enabled = enabled, modifier = Modifier.heightIn(min = MinTouchTarget))
        }
    }
}

@Composable
private fun AuthorRow(title: String, meta: String, icon: Int, open: () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        Surface(Modifier.size(44.dp), color = LaraColors.InfoLight, shape = LaraShapes.medium) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(24.dp), tint = LaraColors.InfoDark) }
        }
        Column(Modifier.weight(1f).heightIn(min = MinTouchTarget).clickable(onClick = open), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = LaraColors.TextSecondary)
        }
        trailing()
    }
}

@Composable
private fun AuthoringForm(kind: String, classroom: Classroom, id: String?, work: Classwork, posts: List<Announcement>,
    reachable: Boolean, state: AuthoringState, model: ClassworkAuthoringViewModel, onClose: () -> Unit) {
    val assignment = work.assignments.find { it.id == id }
    val material = work.materials.find { it.id == id }
    val topic = work.topics.find { it.id == id }
    var order by rememberSaveable { mutableStateOf(topic?.orderIndex?.toString() ?: "0") }
    var worksheet by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf(assignment?.title ?: material?.title ?: topic?.name.orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(assignment?.instructions.orEmpty()) }
    var addingLink by rememberSaveable { mutableStateOf(false) }
    var points by rememberSaveable { mutableStateOf(assignment?.maxPoints?.toString() ?: "100") }
    var due by rememberSaveable { mutableLongStateOf(assignment?.dueDate ?: Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }.timeInMillis) }
    var late by rememberSaveable { mutableStateOf(assignment?.allowLate ?: false) }
    var topicId by rememberSaveable { mutableStateOf(assignment?.topicId ?: material?.topicId) }
    var files by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var fileNames by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val pickerContext = LocalContext.current
    val pickerScope = rememberCoroutineScope()
    var attach by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var removeAttachment by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickerScope.launch {
            val names = withContext(Dispatchers.IO) { uris.map { uri ->
                runCatching { pickerContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } }.getOrNull()
            } }
            uris.forEachIndexed { index, uri -> if (uri.toString() !in files) {
                files = ArrayList(files + uri.toString())
                fileNames = ArrayList(fileNames + names[index].orEmpty())
            } }
        }
    }
    val isAssignment = kind == "assignments"
    val isTopic = kind == "topics"
    val newMaterial = !isTopic && !isAssignment && id == null
    val canSave = classroom.archivedAt == null && (reachable || newMaterial) && !state.busy && title.isNotBlank() &&
        (!isTopic || id == null || order.toLongOrNull()?.let { it >= 0 } == true) &&
        (!isAssignment || points.toLongOrNull()?.let { it in 1..Int.MAX_VALUE } == true) &&
        (isAssignment || isTopic || id != null || files.size == 1)
    ClassFormDialog(title = stringResource(when { isAssignment -> R.string.author_assignment; isTopic -> R.string.author_topic; kind == "video" -> R.string.author_video; else -> R.string.author_material }),
        action = stringResource(when {
            state.busy -> R.string.author_saving
            newMaterial && !reachable -> R.string.material_queue_upload
            else -> R.string.author_save
        }), enabled = canSave,
        canDismiss = !state.busy, onDismiss = { discard = true }, onAction = {
            if (isAssignment) model.assignment(classroom.id, id, AssignmentDraft(title, instructions, requireNotNull(points.toLongOrNull()), due, late, topicId), files)
            else model.run {
                when {
                    isTopic && id != null && order.toLongOrNull() != topic?.orderIndex ->
                        model.repository.reorderTopic(classroom.id, id, title, requireNotNull(order.toLongOrNull()))
                    isTopic -> model.repository.saveTopic(classroom.id, id, title)
                    id != null -> model.repository.saveMaterial(id, title, topicId)
                    else -> model.repository.upload(classroom.id, files.single(), MaterialDraft(title, when { kind == "video" -> "VIDEO"; worksheet -> "WORKSHEET"; else -> "DOCUMENT" }, topicId,
                        assignmentId = attach?.takeIf { it.startsWith("a:") }?.drop(2), announcementId = attach?.takeIf { it.startsWith("p:") }?.drop(2)))
                }
            }
        }) {
        Text(classroom.name, style = MaterialTheme.typography.titleMedium)
        if (!reachable) Text(stringResource(if (newMaterial) R.string.material_staging_offline else R.string.author_offline), color = LaraColors.TextSecondary)
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).classFormInputScroll(),
            label = { Text(stringResource(R.string.author_title)) }, enabled = !state.busy, shape = LaraShapes.medium)
        if (isAssignment) OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth().classFormInputScroll(),
            label = { Text(stringResource(R.string.author_instructions)) }, minLines = 3, enabled = !state.busy, shape = LaraShapes.medium)
        if (isAssignment) {
            TextButton(onClick = { addingLink = true }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.assignment_add_link)) }
        }
        if (!isTopic) {
            work.materials.filter { it.assignmentId == id && id != null }.forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.title, Modifier.weight(1f))
                    IconButton(onClick = { removeAttachment = m.id }, enabled = !state.busy && reachable, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(painterResource(R.drawable.ic_x), stringResource(R.string.author_remove_attachment))
                    }
                }
            }
            files.forEach { uri ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_books_regular), null)
                    Text(fileNames.getOrNull(files.indexOf(uri))?.takeIf { it.isNotBlank() } ?: stringResource(R.string.author_selected_file, files.indexOf(uri) + 1), Modifier.weight(1f))
                    IconButton(onClick = { val index = files.indexOf(uri); fileNames = ArrayList(fileNames.filterIndexed { i, _ -> i != index }); files = ArrayList(files - uri) }, enabled = !state.busy, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(painterResource(R.drawable.ic_x), stringResource(R.string.author_remove_attachment))
                    }
                }
            }
            if (isAssignment || id == null) TextButton(onClick = { picker.launch(if (kind == "video") arrayOf("video/mp4", "video/webm") else arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/*")) },
                enabled = !state.busy, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.author_add_attachment)) }
            if (!isAssignment && id == null) Text(stringResource(R.string.author_one_file), color = LaraColors.TextSecondary)
        }
        if (isAssignment) {
            OutlinedTextField(points, { points = it }, Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).classFormInputScroll(),
                label = { Text(stringResource(R.string.author_points)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = points.toLongOrNull()?.let { it in 1..Int.MAX_VALUE } != true,
                supportingText = { Text(stringResource(R.string.author_points_help)) }, enabled = !state.busy, shape = LaraShapes.medium)
            val context = LocalContext.current
            OutlinedButton(onClick = {
                val date = Calendar.getInstance().apply { timeInMillis = due }
                DatePickerDialog(context, { _, y, m, d ->
                    date.set(y, m, d)
                    TimePickerDialog(context, { _, h, min -> date.set(Calendar.HOUR_OF_DAY, h); date.set(Calendar.MINUTE, min); date.set(Calendar.SECOND, 0); date.set(Calendar.MILLISECOND, 0); due = date.timeInMillis }, date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE), false).show()
                }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show()
            }, enabled = !state.busy, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.classwork_due, DateFormat.getDateTimeInstance().format(Date(due))))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).toggleable(late, enabled = !state.busy, role = Role.Checkbox, onValueChange = { late = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(late, null, enabled = !state.busy); Text(stringResource(R.string.author_allow_late))
            }
        }
        if (isTopic && id != null) OutlinedTextField(order, { order = it },
            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            label = { Text(stringResource(R.string.parity_topic_order)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = order.toLongOrNull()?.let { it >= 0 } != true, enabled = !state.busy)
        if (newMaterial && kind != "video") Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                .toggleable(worksheet, enabled = !state.busy, role = Role.Checkbox, onValueChange = { worksheet = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(worksheet, null, enabled = !state.busy)
            Text(stringResource(R.string.parity_worksheet))
        }
        if (!isTopic) ChoiceField(stringResource(R.string.author_topic), topicId,
            listOf(null to stringResource(R.string.reading_no_topic)) + work.topics.map { it.id to it.name }, !state.busy) { topicId = it }
        if (!isTopic && !isAssignment && id == null) ChoiceField(stringResource(R.string.author_attach_to), attach,
            listOf(null to stringResource(R.string.author_standalone)) + work.assignments.map { "a:${it.id}" to it.title } + posts.map { "p:${it.id}" to it.title }, !state.busy) { attach = it }
    }
    if (addingLink) AssignmentLinkDialog(
        onDismiss = { addingLink = false },
        onAdd = { address ->
            instructions = listOf(instructions.trimEnd(), address).filter { it.isNotEmpty() }.joinToString("\n")
            addingLink = false
        },
    )
    removeAttachment?.let { attachmentId ->
        AlertDialog(onDismissRequest = { removeAttachment = null }, title = { Text(stringResource(R.string.author_delete_title)) },
            text = { Text(stringResource(R.string.author_delete_material)) }, confirmButton = {
                TextButton(onClick = { model.run(close = false) { model.repository.delete("materials", attachmentId) }; removeAttachment = null },
                    enabled = reachable && !state.busy, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.author_delete)) }
            }, dismissButton = { TextButton(onClick = { removeAttachment = null }, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.enrollment_cancel_action)) } })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.author_discard_title)) },
        text = { Text(stringResource(R.string.author_discard_body)) }, confirmButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.author_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.author_keep_editing)) } })
}

@Composable
internal fun TopicFilter(topics: List<org.lara.app.domain.model.Topic>, selected: String?, onSelect: (String?) -> Unit) {
    ChoiceField(stringResource(R.string.author_topic), selected,
        listOf(null to stringResource(R.string.parity_all_topics)) + topics.map { it.id to it.name }, true, onSelect)
}

@Composable
private fun ChoiceField(label: String, value: String?, choices: List<Pair<String?, String>>, enabled: Boolean, select: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                Text(choices.find { it.first == value }?.second ?: choices.first().second, Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_caret_down), null)
            }
            DropdownMenu(expanded, { expanded = false }, containerColor = LaraColors.Surface) {
                choices.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { select(id); expanded = false }, modifier = Modifier.heightIn(min = MinTouchTarget)) }
            }
        }
    }
}

@Composable
internal fun ClassworkAuthoringSkeleton() {
    Column(Modifier.fillMaxSize().padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        Text(stringResource(R.string.author_loading), color = LaraColors.TextSecondary)
        repeat(4) { Surface(Modifier.fillMaxWidth().height(72.dp), color = LaraColors.Border, shape = LaraShapes.medium) {} }
    }
}
