package org.lara.app.ui.screens.teacher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.model.Classroom
import org.lara.app.domain.model.Announcement
import org.lara.app.domain.MaterialDraft
import org.lara.app.ui.components.AttachmentChip
import org.lara.app.ui.components.ClassFormDialog
import org.lara.app.ui.components.classFormInputScroll
import org.lara.app.ui.theme.*

internal enum class AnnouncementStatus { Idle, Posting, Posted, Failed }

internal class PostAnnouncementViewModel(
    private val post: suspend (String, String, Boolean) -> String,
    private val refreshStream: suspend () -> Unit,
    private val queueAttachment: suspend (String, String, String) -> Unit = { _, _, _ -> },
    initialPostId: String? = null,
    initialQueued: Set<String> = emptySet(),
    private val saveProgress: (String?, Set<String>) -> Unit = { _, _ -> },
) : ViewModel() {
    private val mutableStatus = MutableStateFlow(AnnouncementStatus.Idle)
    val status = mutableStatus.asStateFlow()

    private var savedId = initialPostId
    private val queued = initialQueued.toMutableSet()
    val postSaved get() = savedId != null

    fun send(title: String, content: String, allowComments: Boolean, reachable: Boolean,
        attachments: Map<String, String> = emptyMap()) {
        if (!reachable || title.isBlank() || content.isBlank() ||
            mutableStatus.value in setOf(AnnouncementStatus.Posting, AnnouncementStatus.Posted)) return
        mutableStatus.value = AnnouncementStatus.Posting
        viewModelScope.launch {
            try {
                val id = savedId ?: post(title, content, allowComments).also {
                    savedId = it
                    saveProgress(savedId, queued)
                }
                attachments.forEach { (uri, name) ->
                    if (uri !in queued) {
                        queueAttachment(id, uri, name)
                        queued.add(uri)
                        saveProgress(savedId, queued)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableStatus.value = AnnouncementStatus.Failed
                return@launch
            }
            mutableStatus.value = AnnouncementStatus.Posted
            refreshStream()
        }
    }

    fun reset() {
        savedId = null
        queued.clear()
        saveProgress(null, emptySet())
        mutableStatus.value = AnnouncementStatus.Idle
    }
}

@Composable
internal fun PostAnnouncement(container: AppContainer, userId: String, classroom: Classroom,
    reachable: Boolean, modifier: Modifier = Modifier, canModerate: Boolean = true, existing: Announcement? = null) {
    val context = LocalContext.current
    val draftKey = "announcement-$userId-${classroom.id}-${existing?.id.orEmpty()}"
    val drafts = remember(context) { context.getSharedPreferences("announcement-drafts", Context.MODE_PRIVATE) }
    val model: PostAnnouncementViewModel = viewModel(
        key = "post-announcement-$userId-${classroom.id}-${existing?.id.orEmpty()}",
        factory = viewModelFactory {
            initializer {
                PostAnnouncementViewModel(
                    post = { title, content, allowComments ->
                        if (existing == null) {
                            container.announcements.post(userId, classroom.id, title, content,
                                allowComments.takeIf { canModerate })
                        } else {
                            container.materialAuthoringApi.mutate(userId, "announcements", existing.id, "PATCH", buildJsonObject {
                                put("title", title.trim())
                                put("content", content.trim())
                                if (canModerate) put("allow_comments", allowComments)
                            })
                            existing.id
                        }
                    },
                    queueAttachment = { id, uri, name ->
                        container.classworkAuthoring.upload(classroom.id, uri, MaterialDraft(name, "DOCUMENT", announcementId = id))
                    },
                    initialPostId = drafts.getString("$draftKey-post", null),
                    initialQueued = drafts.getStringSet("$draftKey-queued", emptySet()).orEmpty(),
                    saveProgress = { id, queued ->
                        drafts.edit().putString("$draftKey-post", id).putStringSet("$draftKey-queued", queued.toSet()).apply()
                    },
                    refreshStream = {
                        container.syncScheduler.enqueueNow()
                        try {
                            container.sync.pull(userId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // The post succeeded and WorkManager can retry the refresh.
                        }
                    },
                )
            }
        },
    )
    val status by model.status.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf(drafts.getString("$draftKey-title", existing?.title).orEmpty()) }
    var content by rememberSaveable { mutableStateOf(drafts.getString("$draftKey-content", existing?.content).orEmpty()) }
    var comments by rememberSaveable { mutableStateOf(drafts.getBoolean("$draftKey-comments", existing?.allowComments ?: true)) }
    var files by rememberSaveable { mutableStateOf(ArrayList(drafts.getStringSet("$draftKey-files", emptySet()).orEmpty())) }
    var pickerFailed by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickerFailed = false
        for (uri in uris) {
            val acquired = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (acquired && uri.toString() !in files) files = ArrayList(files + uri.toString())
            if (!acquired) pickerFailed = true
        }
    }
    val attachments by produceState<Map<String, String>>(emptyMap(), files) {
        value = withContext(Dispatchers.IO) {
            files.associateWith { raw ->
                runCatching {
                    context.contentResolver.query(Uri.parse(raw), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull() ?: context.getString(R.string.announcement_attachment)
            }
        }
    }
    LaunchedEffect(title, content, comments, files) {
        drafts.edit().putString("$draftKey-title", title).putString("$draftKey-content", content)
            .putBoolean("$draftKey-comments", comments).putStringSet("$draftKey-files", files.toSet()).apply()
    }
    LaunchedEffect(status) {
        if (status == AnnouncementStatus.Posted) {
            open = false
            title = existing?.title.orEmpty()
            content = existing?.content.orEmpty()
            comments = existing?.allowComments ?: true
            files = arrayListOf()
            pickerFailed = false
            model.reset()
            Toast.makeText(context, R.string.announcement_posted, Toast.LENGTH_SHORT).show()
        }
    }
    if (existing != null) TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
        Text(stringResource(R.string.announcement_edit))
    } else FloatingActionButton(
        onClick = { open = true },
        modifier = modifier.size(PrimaryTouchTarget),
        shape = LaraShapes.large,
        containerColor = LaraColors.PrimaryLight,
        contentColor = LaraColors.PrimaryDark,
    ) {
        Icon(painterResource(R.drawable.ic_pencil_simple), stringResource(R.string.announcement_post))
    }
    if (!open) return
    val posting = status == AnnouncementStatus.Posting
    val editable = !posting && status != AnnouncementStatus.Posted
    ClassFormDialog(
        title = stringResource(if (existing == null) R.string.announcement_new else R.string.announcement_edit),
        action = stringResource(when {
            posting -> R.string.announcement_posting
            model.postSaved -> R.string.review_retry
            existing != null -> R.string.author_save
            else -> R.string.announcement_send
        }),
        enabled = reachable && editable && title.isNotBlank() && content.isNotBlank() && attachments.keys == files.toSet(),
        onAction = { model.send(title, content, comments, reachable, attachments) },
        onDismiss = { open = false },
        canDismiss = editable,
    ) {
        Text(classroom.name, style = MaterialTheme.typography.titleMedium)
        if (posting) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!reachable || status == AnnouncementStatus.Failed) {
            Text(stringResource(when {
                !reachable -> R.string.announcement_needs_hub
                model.postSaved -> R.string.announcement_queue_failed
                else -> R.string.announcement_failed
            }),
                color = if (!reachable) LaraColors.TextSecondary else MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(value = title, onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).classFormInputScroll(),
            label = { Text(stringResource(R.string.announcement_title)) }, shape = LaraShapes.medium,
            singleLine = true, enabled = editable && !model.postSaved)
        OutlinedTextField(value = content, onValueChange = { content = it },
            modifier = Modifier.fillMaxWidth().classFormInputScroll(),
            label = { Text(stringResource(R.string.announcement_content)) },
            shape = LaraShapes.medium, minLines = 4, enabled = editable && !model.postSaved)
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = editable,
            modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.announcement_attach_files))
        }
        if (pickerFailed) Text(stringResource(R.string.announcement_attachment_failed), color = MaterialTheme.colorScheme.error)
        attachments.forEach { (uri, name) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AttachmentChip("DOCUMENT", name, Modifier.weight(1f))
                IconButton(onClick = { files = ArrayList(files - uri) }, enabled = editable,
                    modifier = Modifier.size(MinTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_trash), stringResource(R.string.announcement_remove_attachment, name))
                }
            }
        }
        if (canModerate) Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
            .toggleable(comments, enabled = editable && !model.postSaved, role = Role.Checkbox, onValueChange = { comments = it }),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            Checkbox(checked = comments, onCheckedChange = null, enabled = editable)
            Text(stringResource(R.string.announcement_allow_comments))
        }
    }
}
