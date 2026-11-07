package org.lara.app.ui.screens.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.CommentAuthor
import org.lara.app.domain.Cached
import org.lara.app.domain.model.*
import org.lara.app.ui.navigation.*
import org.lara.app.ui.screens.classwork.MaterialReader
import org.lara.app.ui.theme.*
import org.lara.app.ui.screens.teacher.classes.teacherIds
import java.text.DateFormat
import java.util.Date

@Composable
fun ClassroomLearningHost(container: AppContainer, userId: String, destination: ShellDestination,
    fixedClassId: String? = null, teacherClassId: String? = null, onChooseClass: ((String) -> Unit)? = null) {
    if (destination !in setOf(ShellDestination.STREAM, ShellDestination.CLASSWORK)) {
        ShellPlaceholder(destination); return
    }
    val scope = rememberCoroutineScope()
    val classFlow = remember(container) { container.classrooms.classrooms(scope) }
    val cached by classFlow.collectAsStateWithLifecycle()
    var choice by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    val classes = (cached as? Cached.Content)?.value.orEmpty()
    val selected = classes.find { it.id == (fixedClassId ?: teacherClassId ?: choice) } ?: if (fixedClassId == null) classes.firstOrNull() else null
    if (cached == Cached.Loading) { ReadingSkeleton(); return }
    if (selected == null) { ReadingEmpty(R.string.reading_no_classes); return }
    Column(Modifier.fillMaxSize()) {
        if (fixedClassId == null) {
            var expanded by remember { mutableStateOf(false) }
            Box(Modifier.padding(horizontal = LaraSpacing.S4)) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = MinTouchTarget),
                    border = BorderStroke(1.dp, LaraColors.Border), shape = LaraShapes.medium) {
                    Icon(painterResource(R.drawable.ic_books_regular), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(LaraSpacing.S2))
                    Text(stringResource(R.string.reading_choose_class, selected.name))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = LaraColors.Surface) {
                    classes.forEach { classroom -> DropdownMenuItem(text = { Text(classroom.name) },
                        onClick = { choice = classroom.id; onChooseClass?.invoke(classroom.id); expanded = false },
                        modifier = Modifier.heightIn(min = MinTouchTarget)) }
                }
            }
        }
        key(selected.id) { ClassFeed(container, userId, selected, destination) }
    }
}

@Composable
private fun ClassFeed(container: AppContainer, userId: String, classroom: Classroom, destination: ShellDestination) {
    val scope = rememberCoroutineScope()
    val streamFlow = remember(classroom.id) { container.stream.stream(classroom.id, scope) }
    val workFlow = remember(classroom.id) { container.classwork.classwork(classroom.id, scope) }
    val posts by streamFlow.collectAsStateWithLifecycle()
    val work by workFlow.collectAsStateWithLifecycle()
    val names by container.commentNames.collectAsStateWithLifecycle(emptyList())
    val model: StreamViewModel = viewModel(key = "stream-$userId-${classroom.id}", factory = viewModelFactory {
        initializer { StreamViewModel(container.stream, userId) }
    })
    val action by model.action.collectAsStateWithLifecycle()
    var opened by rememberSaveable(classroom.id) { mutableStateOf<String?>(null) }
    var openedAssignment by rememberSaveable(classroom.id) { mutableStateOf<String?>(null) }
    val assignments = (work as? Cached.Content)?.value?.assignments.orEmpty()
    val selectedAssignment = assignments.find { it.id == openedAssignment }
    if (selectedAssignment != null) {
        key(selectedAssignment.id) {
            org.lara.app.ui.screens.homework.HomeworkScreen(container, userId, selectedAssignment) { openedAssignment = null }
        }
        return
    }
    val materials = (work as? Cached.Content)?.value?.materials.orEmpty().filter { it.fileType != "VIDEO" }
    val selected = materials.find { it.id == opened }
    if (selected != null) {
        key(selected.id, selected.updatedAt) { MaterialReader(container, selected) { opened = null } }
        return
    }
    if (posts == Cached.Loading || work == Cached.Loading) { ReadingSkeleton(); return }
    val announcements = (posts as? Cached.Content)?.value.orEmpty()
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(LaraSpacing.S3),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        item {
            Surface(modifier = Modifier.laraShadow(), color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface, shape = LaraShapes.large) {
                Column(Modifier.fillMaxWidth().padding(LaraSpacing.S6), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    Surface(color = LaraColors.Surface.copy(alpha = 0.18f), shape = CircleShape) {
                        Icon(painterResource(R.drawable.ic_books_regular), null,
                            Modifier.padding(LaraSpacing.S3).size(32.dp), tint = LaraColors.Surface)
                    }
                    Text(classroom.name, style = MaterialTheme.typography.headlineMedium)
                    Text(classroom.section, color = LaraColors.PrimaryLight, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (destination == ShellDestination.STREAM) R.string.nav_stream else R.string.nav_classwork))
                }
            }
        }
        if (destination == ShellDestination.STREAM) {
            if (announcements.isEmpty()) item { ReadingEmpty(R.string.reading_stream_empty) }
            items(announcements, key = { it.id }) { post ->
                AnnouncementCard(container, post, userId, names, action, model,
                    materials.filter { it.announcementId == post.id }) { opened = it.id }
            }
        } else {
            val learner = userId !in classroom.teacherIds()
            if (learner) items(assignments, key = { "assignment-${it.id}" }) { assignment ->
                org.lara.app.ui.components.ClassroomSurface {
                    Text(assignment.title, style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = { openedAssignment = assignment.id }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.homework_open))
                    }
                }
            }
            val handouts = materials.filter { it.assignmentId == null && it.announcementId == null }
            if (handouts.isEmpty()) item { ReadingEmpty(R.string.reading_handouts_empty) }
            val topics = (work as? Cached.Content)?.value?.topics.orEmpty()
            val groups = handouts.groupBy { it.topicId }
            val ordered = topics.map { it.id }.filter { it in groups } + groups.keys.filter { id -> topics.none { it.id == id } }
            ordered.forEach { topicId ->
                item(key = "topic-${topicId ?: "none"}") {
                    Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                        Text(topics.find { it.id == topicId }?.name ?: stringResource(R.string.reading_handouts),
                            style = MaterialTheme.typography.headlineMedium, color = LaraColors.PrimaryDark)
                        Text(stringResource(R.string.reading_handout_count, groups[topicId].orEmpty().size),
                            style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextSecondary)
                        HorizontalDivider(thickness = 2.dp, color = LaraColors.Primary)
                    }
                }
                items(groups[topicId].orEmpty(), key = { it.id }) { material -> MaterialRow(material) { opened = material.id } }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun AnnouncementCard(container: AppContainer, post: Announcement, userId: String,
    names: List<CommentAuthor>, action: CommentAction, model: StreamViewModel,
    attachments: List<Material>, onOpen: (Material) -> Unit) {
    val scope = rememberCoroutineScope()
    val flow = remember(post.id) { container.stream.comments(post.id, scope) }
    val cached by flow.collectAsStateWithLifecycle()
    val comments = (cached as? Cached.Content)?.value.orEmpty()
    var expanded by rememberSaveable(post.id) { mutableStateOf(false) }
    var draft by rememberSaveable(post.id, userId) { mutableStateOf("") }
    Surface(color = LaraColors.SurfaceSubtle, contentColor = LaraColors.TextPrimary, shape = LaraShapes.medium) {
        Column(Modifier.fillMaxWidth().padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                Surface(color = LaraColors.PrimaryLight, shape = CircleShape) {
                    Icon(painterResource(R.drawable.ic_info), null, Modifier.padding(LaraSpacing.S3), tint = LaraColors.PrimaryDark)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.reading_class_announcement), style = MaterialTheme.typography.titleMedium)
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(post.createdAt)),
                        style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextSecondary)
                    ReadingStatus("SYNCED")
                }
            }
            Text(post.title, style = MaterialTheme.typography.titleLarge)
            Text(post.content, style = MaterialTheme.typography.bodyLarge)
            attachments.forEach { material -> MaterialRow(material) { onOpen(material) } }
            HorizontalDivider(color = LaraColors.Border)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(if (expanded) R.string.reading_hide_comments else R.string.reading_comments, comments.size))
            }
            if (!post.allowComments) Text(stringResource(R.string.reading_comments_disabled), color = LaraColors.TextSecondary)
            if (expanded) {
                if (cached == Cached.Loading) ReadingSkeleton()
                else if (comments.isEmpty()) Text(stringResource(R.string.reading_comments_empty))
                comments.forEach { comment ->
                    val author = names.find { it.id == comment.authorId }
                    val name = when {
                        comment.authorId == userId -> stringResource(R.string.reading_you)
                        author == null -> stringResource(R.string.reading_class_member)
                        author.role == "STUDENT" -> author.name.trim().substringBefore(' ')
                        else -> author.name
                    }
                    Column(Modifier.fillMaxWidth().background(LaraColors.Surface, LaraShapes.medium).padding(LaraSpacing.S3),
                        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(comment.content)
                        ReadingStatus(comment.syncStatus)
                        if (comment.syncStatus == "REJECTED") Text(stringResource(R.string.reading_rejected),
                            style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextSecondary)
                    }
                }
                if (post.allowComments) {
                    val composer = remember { BringIntoViewRequester() }
                    var focused by remember { mutableStateOf(false) }
                    val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
                    LaunchedEffect(keyboardHeight, focused) { if (focused) composer.bringIntoView() }
                    Column(Modifier.fillMaxWidth().bringIntoViewRequester(composer),
                        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(stringResource(R.string.reading_add_comment)) },
                        modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                            focused = focus.isFocused
                        }, shape = LaraShapes.extraLarge,
                        enabled = post.id !in action.sending, minLines = 2)
                    if (post.id in action.failed) Text(stringResource(R.string.reading_comment_failed), color = LaraColors.DangerDark)
                    Button(onClick = { model.send(post.id, draft) { draft = "" } },
                        enabled = draft.isNotBlank() && post.id !in action.sending,
                        modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                        Text(stringResource(R.string.reading_send))
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialRow(material: Material, open: () -> Unit) {
    Surface(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).laraShadow(),
        color = LaraColors.Surface, contentColor = LaraColors.TextPrimary, shape = LaraShapes.large,
        border = BorderStroke(1.dp, LaraColors.Border)) {
        Row(Modifier.padding(LaraSpacing.S4), horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(color = LaraColors.InfoLight, shape = LaraShapes.medium) {
                Icon(painterResource(R.drawable.ic_books_regular), null, Modifier.padding(LaraSpacing.S3).size(24.dp),
                    tint = LaraColors.InfoDark)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                Text(material.title, style = MaterialTheme.typography.titleMedium)
                Surface(color = if (material.localFilePath == null) LaraColors.InfoLight else LaraColors.PrimaryLight,
                    shape = LaraShapes.small) {
                    Text(stringResource(if (material.localFilePath == null) R.string.reading_save_home_badge else R.string.reading_saved_device_badge),
                        Modifier.padding(horizontal = LaraSpacing.S2, vertical = LaraSpacing.S1),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (material.localFilePath == null) LaraColors.InfoDark else LaraColors.PrimaryDark)
                }
                Text(stringResource(R.string.reading_open), style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextSecondary)
            }
            if (material.localFilePath != null) Icon(painterResource(R.drawable.ic_check_circle), null,
                Modifier.size(24.dp), tint = LaraColors.PrimaryDark)
        }
    }
}

@Composable
private fun ReadingStatus(status: String) {
    val queued = status == "QUEUED_FOR_SYNC"
    val rejected = status == "REJECTED"
    val tone = if (rejected) LaraColors.DangerDark else if (queued) LaraColors.WarningDark else LaraColors.PrimaryDark
    val fill = if (rejected) LaraColors.DangerLight else if (queued) LaraColors.WarningLight else LaraColors.PrimaryLight
    Surface(color = fill, contentColor = tone, shape = CircleShape) {
        Row(Modifier.padding(horizontal = LaraSpacing.S3, vertical = LaraSpacing.S1),
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(if (queued || rejected) R.drawable.ic_warning_circle else R.drawable.ic_check_circle),
                null, Modifier.size(20.dp))
            Text(stringResource(if (queued) R.string.reading_queued else if (rejected) R.string.reading_not_sent else R.string.reading_saved),
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun ReadingEmpty(message: Int) {
    Column(Modifier.fillMaxWidth().padding(LaraSpacing.S6), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        Surface(Modifier.size(80.dp), shape = CircleShape, color = LaraColors.PrimaryLight) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(if (message == R.string.reading_handouts_empty) R.drawable.ic_books_regular else R.drawable.ic_chats_regular),
                    null, Modifier.size(40.dp), tint = LaraColors.PrimaryDark)
            }
        }
        Text(stringResource(if (message == R.string.reading_handouts_empty) R.string.reading_handouts_empty_title
            else if (message == R.string.reading_stream_empty) R.string.reading_stream_empty_title else R.string.reading_classes_empty_title),
            style = MaterialTheme.typography.titleMedium)
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge, color = LaraColors.TextSecondary)
    }
}

@Composable
internal fun ReadingSkeleton() {
    Column(Modifier.fillMaxWidth().padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        Text(stringResource(R.string.reading_loading))
        repeat(3) { Spacer(Modifier.fillMaxWidth().height(LaraSpacing.S12).background(LaraColors.SurfaceSubtle, LaraShapes.medium)) }
    }
}
