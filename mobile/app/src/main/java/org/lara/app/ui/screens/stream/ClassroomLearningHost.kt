package org.lara.app.ui.screens.stream

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import org.lara.app.ui.screens.classwork.preloadMaterial
import kotlinx.coroutines.launch
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.Cached
import org.lara.app.domain.model.*
import org.lara.app.ui.navigation.*
import org.lara.app.ui.screens.classwork.MaterialReader
import org.lara.app.ui.screens.classwork.VideoPlayerScreen
import org.lara.app.ui.theme.*
import org.lara.app.ui.screens.teacher.classes.teacherIds
import org.lara.app.ui.screens.teacher.PostAnnouncement
import java.text.DateFormat
import java.util.Date


@Composable
fun ClassroomLearningHost(container: AppContainer, userId: String, destination: ShellDestination,
    fixedClassId: String? = null, teacherClassId: String? = null, onChooseClass: ((String) -> Unit)? = null,
    teacherHubReachable: Boolean? = null, hubReachable: Boolean? = teacherHubReachable) {
    if (destination !in setOf(ShellDestination.STREAM, ShellDestination.CLASSWORK, ShellDestination.PEOPLE)) {
        ShellPlaceholder(destination); return
    }
    val scope = rememberCoroutineScope()
    val classFlow = remember(container) { container.classrooms.classrooms(scope) }
    val cached by classFlow.collectAsStateWithLifecycle()
    var choice by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    val classes = (cached as? Cached.Content)?.value.orEmpty()
    val selected = classes.find { it.id == (fixedClassId ?: teacherClassId ?: choice) } ?: if (fixedClassId == null) classes.firstOrNull() else null
    if (cached == Cached.Loading) { org.lara.app.ui.components.LoadingSpinner(); return }
    if (selected == null) { ReadingEmpty(R.string.reading_no_classes); return }
    val unreadByAssignment = if (teacherHubReachable != null && userId in selected.teacherIds()) {
        val unread by remember(container, selected.id) {
            container.teacherPrivateCommentRepository.unreadThreads(selected.id)
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        unread.groupingBy { it.assignmentId }.fold(0) { total, thread -> total + thread.count }
    } else emptyMap()
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
        key(selected.id) { ClassFeed(container, userId, selected, destination, teacherHubReachable, hubReachable, unreadByAssignment) }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ClassFeed(container: AppContainer, userId: String, classroom: Classroom, destination: ShellDestination,
    teacherHubReachable: Boolean?, hubReachable: Boolean?, unreadCommentsByAssignment: Map<String, Int>) {
    val scope = rememberCoroutineScope()
    val streamFlow = remember(classroom.id) { container.stream.stream(classroom.id, scope) }
    val workFlow = remember(classroom.id) { container.classwork.classwork(classroom.id, scope) }
    val posts by streamFlow.collectAsStateWithLifecycle()
    val work by workFlow.collectAsStateWithLifecycle()
    var topicFilter by rememberSaveable(classroom.id) { mutableStateOf<String?>(null) }
    val names by container.commentNames.collectAsStateWithLifecycle(emptyList())
    val reload: suspend () -> Unit = { container.sync.synchronize(userId) }
    if (destination == ShellDestination.PEOPLE) {
        org.lara.app.ui.components.PullRefresh(reload) { LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            item { Text(stringResource(R.string.classes_teachers), style = MaterialTheme.typography.headlineMedium)
                HorizontalDivider(color = LaraColors.Border) }
            items(classroom.teacherIds().distinct(), key = { it }) { id ->
                org.lara.app.ui.screens.teacher.classes.ClassPersonCard(
                    names.find { it.id == id && it.role == "TEACHER" }?.name ?: stringResource(R.string.reading_teacher),
                    stringResource(if (id == classroom.teacherId) R.string.classes_owner_role else R.string.classes_co_role))
            }
        } }
        return
    }
    var opened by rememberSaveable(classroom.id) { mutableStateOf<String?>(null) }
    var reviewAssignment by rememberSaveable(classroom.id) { mutableStateOf<String?>(null) }
    var openedAssignment by rememberSaveable(classroom.id) { mutableStateOf<String?>(null) }
    val assignments = (work as? Cached.Content)?.value?.assignments.orEmpty()
    val selectedAssignment = assignments.find { it.id == openedAssignment }
    val materials = (work as? Cached.Content)?.value?.materials.orEmpty()
    var preparing by remember { mutableStateOf(false) }
    val openMaterial = { id: String ->
        val material = materials.find { it.id == id }
        if (!preparing && material != null) {
            if (material.fileType == "VIDEO") opened = id
            else { preparing = true; scope.launch { try { preloadMaterial(container, material) } finally { preparing = false }; opened = id } }
        }
    }
    val fullScreen = org.lara.app.ui.components.LocalFullScreenOverlay.current
    val storeOwner = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current
    val savedOwner = androidx.compose.ui.platform.LocalSavedStateRegistryOwner.current
    if (selectedAssignment != null && fullScreen != null && storeOwner != null) {
        BackHandler { openedAssignment = null }
        // The overlay is drawn by the scaffold, so hand it this screen's owners or its ViewModel loses the saved-state registry.
        SideEffect { fullScreen.value = { CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides storeOwner, androidx.compose.ui.platform.LocalSavedStateRegistryOwner provides savedOwner) { key(selectedAssignment.id) { org.lara.app.ui.screens.homework.HomeworkScreen(container, userId, selectedAssignment, materials.filter { it.assignmentId == selectedAssignment.id }, { id -> openedAssignment = null; openMaterial(id) }) { openedAssignment = null } } } } }
        DisposableEffect(Unit) { onDispose { fullScreen.value = null } }
    }
    val review = assignments.find { it.id == reviewAssignment }
    if (review != null && teacherHubReachable != null) {
        val page: @Composable () -> Unit = { key(review.id) {
            org.lara.app.ui.screens.teacher.review.SubmissionReview(container, userId, review, teacherHubReachable,
                classroom.archivedAt != null, close = { reviewAssignment = null }, assignmentAttachments = {
                    materials.filter { it.assignmentId == review.id }.forEach { attachment ->
                        AnnouncementAttachmentPreview(container, userId, attachment) {
                            reviewAssignment = null
                            openMaterial(attachment.id)
                        }
                    }
                })
        } }
        if (fullScreen != null) {
            val storeOwner = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current
            val savedOwner = androidx.compose.ui.platform.LocalSavedStateRegistryOwner.current
            SideEffect { fullScreen.value = { CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides storeOwner!!, androidx.compose.ui.platform.LocalSavedStateRegistryOwner provides savedOwner) { page() } } }
            DisposableEffect(Unit) { onDispose { fullScreen.value = null } }
        } else page()
        return
    }
    var openedPost by rememberSaveable(classroom.id) { mutableStateOf<String?>(null) }
    val selectedPost = (posts as? Cached.Content)?.value.orEmpty().find { it.id == openedPost }
    if (selectedPost != null && fullScreen != null && storeOwner != null) {
        BackHandler { openedPost = null }
        SideEffect { fullScreen.value = { CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides storeOwner, androidx.compose.ui.platform.LocalSavedStateRegistryOwner provides savedOwner) {
            ClassDiscussion(container, userId, selectedPost, classroom.archivedAt != null, reload, { openedPost = null }) {
                AnnouncementActions(container, userId, classroom, selectedPost,
                    materials.filter { it.announcementId == selectedPost.id }, hubReachable == true, reload,
                    close = { openedPost = null }, openMaterial = { openMaterial(it) })
            } } } }
        DisposableEffect(Unit) { onDispose { fullScreen.value = null } }
    }
    val selected = materials.find { it.id == opened }
    if (selected != null && (selected.fileType != "VIDEO" || storeOwner != null)) {
        val page: @Composable () -> Unit = { key(selected.id) {
            if (selected.fileType == "VIDEO") VideoPlayerScreen(container, userId, selected.id) { opened = null }
            else MaterialReader(container, selected) { opened = null }
        } }
        if (fullScreen != null && storeOwner != null) {
            SideEffect { fullScreen.value = { CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides storeOwner, androidx.compose.ui.platform.LocalSavedStateRegistryOwner provides savedOwner) { page() } } }
            DisposableEffect(Unit) { onDispose { fullScreen.value = null } }
        } else page()
        return
    }
    if (posts == Cached.Loading || work == Cached.Loading) {
        if (teacherHubReachable != null && destination == ShellDestination.CLASSWORK) org.lara.app.ui.screens.teacher.classwork.ClassworkAuthoringSkeleton()
        else org.lara.app.ui.components.LoadingSpinner()
        return
    }
    val announcements = (posts as? Cached.Content)?.value.orEmpty()
    val topics = (work as? Cached.Content)?.value?.topics.orEmpty()
    if (destination == ShellDestination.CLASSWORK && teacherHubReachable != null && userId in classroom.teacherIds()) {
        org.lara.app.ui.components.PullRefresh(reload) {
            org.lara.app.ui.screens.teacher.classwork.TeacherClasswork(container.classworkAuthoring, userId, classroom,
                requireNotNull((work as? Cached.Content)?.value), announcements, teacherHubReachable, openMaterial,
                unreadCommentsByAssignment = unreadCommentsByAssignment) { reviewAssignment = it }
        }
        return
    }
    val canCompose = hubReachable != null && destination == ShellDestination.STREAM && classroom.archivedAt == null
    Box(Modifier.fillMaxSize()) {
    org.lara.app.ui.components.PullRefresh(reload) { LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = LaraSpacing.S4, top = LaraSpacing.S4, end = LaraSpacing.S4, bottom = LaraSpacing.S4 + if (canCompose) PrimaryTouchTarget + LaraSpacing.S4 else 0.dp),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        if (destination == ShellDestination.STREAM) {
            // Classwork also shows up here as posts, like Classroom. 
            val learner = userId !in classroom.teacherIds()
            val feed = announcements.map { post -> Triple(post.createdAt, post.id, @Composable {
                AnnouncementCard(container, userId, post, names.find { it.id == post.authorId }?.name, materials.filter { it.announcementId == post.id }, { openedPost = post.id }) { openMaterial(it.id) }
            }) } + materials.filter { it.announcementId == null && it.assignmentId == null }.map { m ->
                Triple(m.createdAt, m.id, @Composable {
                    WorkPostCard(if (m.fileType == "VIDEO") R.drawable.ic_play else R.drawable.ic_books_regular,
                        stringResource(R.string.stream_new_material, m.title), m.createdAt) { openMaterial(m.id) }
                })
            } + (if (learner) assignments else emptyList()).map { a ->
                Triple(a.createdAt, a.id, @Composable {
                    WorkPostCard(R.drawable.ic_exam_regular, stringResource(R.string.stream_new_assignment, a.title), a.createdAt) { openedAssignment = a.id }
                })
            }
            if (feed.isEmpty()) item { ReadingEmpty(R.string.reading_stream_empty) }
            items(feed.sortedByDescending { it.first }, key = { it.second }) { it.third() }
        } else {
            val learner = userId !in classroom.teacherIds()
            val handouts = materials.filter { it.assignmentId == null }
            val allAssignments = if (learner) assignments else emptyList()
            val topicIds = (handouts.mapNotNull { it.topicId } + allAssignments.mapNotNull { it.topicId }).toSet()
            val ungrouped = handouts.any { it.topicId == null } || allAssignments.any { it.topicId == null }
            val ordered = (topics.map { it.id }.filter { it in topicIds } + topicIds.filter { id -> topics.none { it.id == id } }).map { "t$it" } + if (ungrouped) listOf("none") else emptyList()
            val selectedTopic = topicFilter?.takeIf { id -> topics.any { it.id == id } }
            if (topics.isNotEmpty()) item {
                org.lara.app.ui.screens.teacher.classwork.TopicFilter(topics, selectedTopic) { topicFilter = it }
            }
            val shown = ordered.filter { selectedTopic == null || it == "t$selectedTopic" }
            if (shown.isEmpty()) item { ReadingEmpty(R.string.reading_work_empty) }
            shown.forEach { section ->
                val isTopic = section.startsWith("t")
                val key = section.drop(1)
                val rowAssignments = allAssignments.filter { if (isTopic) it.topicId == key else it.topicId == null }
                val rowMaterials = handouts.filter { if (isTopic) it.topicId == key else it.topicId == null }
                item(key = "section-$section") {
                    Column(Modifier.padding(top = LaraSpacing.S2), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                        Text(if (isTopic) topics.find { it.id == key }?.name ?: stringResource(R.string.reading_no_topic) else stringResource(R.string.reading_no_topic),
                            style = MaterialTheme.typography.titleMedium, color = LaraColors.PrimaryDark)
                        HorizontalDivider(color = LaraColors.Border)
                    }
                }
                items(rowAssignments, key = { "assignment-${it.id}" }) { assignment ->
                    ClassworkRow(R.drawable.ic_exam_regular, assignment.title,
                        stringResource(R.string.classwork_due, shortDate(assignment.dueDate)), false) { openedAssignment = assignment.id }
                }
                items(rowMaterials, key = { it.id }) { material -> MaterialRow(material) {
                    val thread = material.announcementId?.takeIf { id -> announcements.any { it.id == id } }
                    if (thread != null) openedPost = thread else openMaterial(material.id)
                } }
            }
        }
    } }
        if (canCompose) PostAnnouncement(container, userId, classroom, hubReachable == true,
            Modifier.align(Alignment.BottomEnd).padding(LaraSpacing.S4), canModerate = userId in classroom.teacherIds())
    }
}

/** Stream card for a handout or assignment; same look as an announcement, without a comment line. */
@Composable
private fun WorkPostCard(icon: Int, title: String, createdAt: Long, open: () -> Unit) {
    Surface(onClick = open, color = LaraColors.Surface, contentColor = LaraColors.TextPrimary, shape = LaraShapes.medium,
        border = BorderStroke(1.dp, LaraColors.Border)) {
        Row(Modifier.fillMaxWidth().padding(LaraSpacing.S3), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            Surface(Modifier.size(40.dp), color = LaraColors.InfoLight, shape = CircleShape) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(20.dp), tint = LaraColors.InfoDark) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S1)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(createdAt)),
                    style = MaterialTheme.typography.bodySmall, color = LaraColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun AnnouncementCard(container: AppContainer, userId: String, post: Announcement, author: String?, attachments: List<Material>, openPost: () -> Unit, onOpen: (Material) -> Unit) {
    Surface(onClick = openPost, color = LaraColors.Surface, contentColor = LaraColors.TextPrimary, shape = LaraShapes.medium,
        border = BorderStroke(1.dp, LaraColors.Border)) {
        Column(Modifier.fillMaxWidth().padding(LaraSpacing.S3), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                Surface(Modifier.size(40.dp), color = LaraColors.PrimaryLight, shape = CircleShape) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_info), null, Modifier.size(20.dp), tint = LaraColors.PrimaryDark)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S1)) {
                    Text(post.title, style = MaterialTheme.typography.bodyLarge)
                    if (post.authorId != null) Text(author ?: stringResource(R.string.class_comments_member),
                        style = MaterialTheme.typography.bodySmall, color = LaraColors.TextSecondary)
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(post.createdAt)),
                        style = MaterialTheme.typography.bodySmall, color = LaraColors.TextSecondary)
                }
            }
            if (post.content.isNotBlank()) Text(post.content, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            attachments.forEach { attachment ->
                AnnouncementAttachmentPreview(container, userId, attachment) { onOpen(attachment) }
            }
            HorizontalDivider(color = LaraColors.Border)
            Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable(onClick = openPost)
                .padding(horizontal = LaraSpacing.S1, vertical = LaraSpacing.S2),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                Icon(painterResource(R.drawable.ic_chats_regular), null, Modifier.size(24.dp))
                Text(stringResource(R.string.class_comments_hint), color = LaraColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun MaterialRow(material: Material, open: () -> Unit) {
    ClassworkRow(if (material.fileType == "VIDEO") R.drawable.ic_play else R.drawable.ic_books_regular, material.title,
        stringResource(R.string.classwork_posted, shortDate(material.createdAt)), material.localFilePath != null, open)
}

/** Flat Google Classroom list row: tinted circle icon, title, one grey line, check when kept on this device. */
@Composable
private fun ClassworkRow(icon: Int, title: String, subtitle: String, saved: Boolean, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget).clickable(onClick = open),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        Surface(Modifier.size(44.dp), color = LaraColors.InfoLight, shape = CircleShape) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(22.dp), tint = LaraColors.InfoDark) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LaraColors.TextSecondary)
        }
        if (saved) Icon(painterResource(R.drawable.ic_check_circle), stringResource(R.string.reading_saved_device_badge),
            Modifier.size(20.dp), tint = LaraColors.PrimaryDark)
    }
}

private fun shortDate(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

@Composable
internal fun ReadingEmpty(message: Int) {
    Column(Modifier.fillMaxWidth().padding(LaraSpacing.S6), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        Surface(Modifier.size(80.dp), shape = CircleShape, color = LaraColors.PrimaryLight) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(if (message == R.string.reading_handouts_empty || message == R.string.reading_work_empty) R.drawable.ic_books_regular else R.drawable.ic_chats_regular),
                    null, Modifier.size(40.dp), tint = LaraColors.PrimaryDark)
            }
        }
        Text(stringResource(if (message == R.string.reading_handouts_empty) R.string.reading_handouts_empty_title
            else if (message == R.string.reading_work_empty) R.string.reading_work_empty_title
            else if (message == R.string.reading_stream_empty) R.string.reading_stream_empty_title else R.string.reading_classes_empty_title),
            style = MaterialTheme.typography.titleMedium)
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge, color = LaraColors.TextSecondary)
    }
}
