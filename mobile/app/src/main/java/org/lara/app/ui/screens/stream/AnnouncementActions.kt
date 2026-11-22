package org.lara.app.ui.screens.stream

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.model.Announcement
import org.lara.app.domain.model.Classroom
import org.lara.app.domain.model.Material
import org.lara.app.ui.components.AttachmentChip
import org.lara.app.ui.screens.teacher.PostAnnouncement
import org.lara.app.ui.screens.teacher.classes.teacherIds
import org.lara.app.ui.theme.LaraSpacing
import org.lara.app.ui.theme.MinTouchTarget
import org.lara.app.ui.theme.PrimaryTouchTarget

@Composable
internal fun AnnouncementActions(
    container: AppContainer, userId: String, classroom: Classroom, post: Announcement,
    attachments: List<Material>, reachable: Boolean, reload: suspend () -> Unit,
    close: () -> Unit, openMaterial: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val teacher = userId in classroom.teacherIds()
    val canManage = classroom.archivedAt == null && (teacher || post.authorId == userId)
    val canEdit = canManage && (post.authorId == userId ||
        teacher && (post.authorId == null || post.authorId in classroom.teacherIds()))
    var busy by remember(post.id) { mutableStateOf(false) }
    var failed by remember(post.id) { mutableStateOf(false) }
    var deleteId by rememberSaveable(post.id) { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable(post.id) { mutableStateOf<String?>(null) }
    var title by rememberSaveable(post.id) { mutableStateOf("") }
    val enabled = canManage && reachable && !busy
    val uploads by remember(container, classroom.id) { container.classworkAuthoring.uploads(classroom.id) }
        .collectAsStateWithLifecycle(emptyList())

    fun change(success: () -> Unit = {}, request: suspend () -> Unit) {
        busy = true
        failed = false
        scope.launch {
            try {
                request()
                container.syncScheduler.enqueueNow()
                reload()
                success()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }

    if (canManage) {
        if (canEdit) PostAnnouncement(container, userId, classroom, reachable && !busy,
            canModerate = teacher, existing = post)
        TextButton(onClick = { deleteId = post.id }, enabled = enabled,
            modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.announcement_delete))
        }
        if (!reachable) Text(stringResource(R.string.announcement_needs_hub))
    }
    if (failed) Text(stringResource(R.string.announcement_change_failed), color = MaterialTheme.colorScheme.error)
    uploads.filter { it.announcementId == post.id && it.state in setOf("RUNNING", "ENQUEUED", "FAILED") }.forEach { upload ->
        if (upload.title.isNotBlank()) AttachmentChip("DOCUMENT", upload.title)
        Text(stringResource(when (upload.state) {
            "FAILED" -> R.string.author_upload_failed
            "RUNNING" -> R.string.author_uploading
            else -> R.string.author_upload_waiting
        }))
        if (upload.state == "FAILED") TextButton(
            onClick = { change { container.classworkAuthoring.retry(upload.id) } }, enabled = enabled,
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) { Text(stringResource(R.string.review_retry)) }
    }
    attachments.forEach { attachment ->
        AnnouncementAttachmentPreview(container, userId, attachment, reachable) { openMaterial(attachment.id) }
        if (canManage) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            TextButton(onClick = { title = attachment.title; renameId = attachment.id }, enabled = enabled,
                modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.announcement_rename_attachment))
            }
            TextButton(onClick = { deleteId = attachment.id }, enabled = enabled,
                modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.author_delete))
            }
        }
    }
    deleteId?.let { id ->
        val deletingPost = id == post.id
        val action = if (deletingPost) R.string.announcement_delete else R.string.announcement_delete_attachment
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(action)) },
            text = { Text(stringResource(if (deletingPost) R.string.announcement_delete_confirm else R.string.announcement_delete_attachment_confirm)) },
            confirmButton = {
                TextButton(enabled = enabled, modifier = Modifier.heightIn(min = PrimaryTouchTarget), onClick = {
                    deleteId = null
                    change(success = { if (deletingPost) close() }) {
                        container.materialAuthoringApi.mutate(userId, if (deletingPost) "announcements" else "materials",
                            id, "DELETE", null)
                    }
                }) { Text(stringResource(R.string.author_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.enrollment_cancel_action))
                }
            },
        )
    }
    renameId?.let { id ->
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text(stringResource(R.string.announcement_rename_attachment)) },
            text = { OutlinedTextField(title, { title = it }, singleLine = true,
                label = { Text(stringResource(R.string.announcement_title)) }) },
            confirmButton = {
                TextButton(enabled = enabled && title.isNotBlank(), modifier = Modifier.heightIn(min = PrimaryTouchTarget), onClick = {
                    renameId = null
                    val name = title.trim()
                    change {
                        container.materialAuthoringApi.mutate(userId, "materials", id, "PATCH", buildJsonObject { put("title", name) })
                    }
                }) { Text(stringResource(R.string.author_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameId = null }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.enrollment_cancel_action))
                }
            },
        )
    }
}
