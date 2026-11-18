package org.lara.app.ui.screens.teacher

import android.widget.Toast
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.model.Classroom
import org.lara.app.ui.components.ClassFormDialog
import org.lara.app.ui.components.classFormInputScroll
import org.lara.app.ui.theme.*

internal enum class AnnouncementStatus { Idle, Posting, Posted, Failed }

internal class PostAnnouncementViewModel(
    private val post: suspend (String, String, Boolean) -> Unit,
    private val refreshStream: suspend () -> Unit,
) : ViewModel() {
    private val mutableStatus = MutableStateFlow(AnnouncementStatus.Idle)
    val status = mutableStatus.asStateFlow()

    fun send(title: String, content: String, allowComments: Boolean, reachable: Boolean) {
        if (!reachable || title.isBlank() || content.isBlank() ||
            mutableStatus.value in setOf(AnnouncementStatus.Posting, AnnouncementStatus.Posted)) return
        mutableStatus.value = AnnouncementStatus.Posting
        viewModelScope.launch {
            try {
                post(title, content, allowComments)
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

    fun reset() { mutableStatus.value = AnnouncementStatus.Idle }
}

@Composable
internal fun PostAnnouncement(container: AppContainer, userId: String, classroom: Classroom,
    reachable: Boolean, modifier: Modifier = Modifier) {
    val model: PostAnnouncementViewModel = viewModel(
        key = "post-announcement-$userId-${classroom.id}",
        factory = viewModelFactory {
            initializer {
                PostAnnouncementViewModel(
                    post = { title, content, allowComments ->
                        container.announcements.post(userId, classroom.id, title, content, allowComments)
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
    val context = LocalContext.current
    LaunchedEffect(status) {
        if (status == AnnouncementStatus.Posted) {
            open = false
            model.reset()
            Toast.makeText(context, R.string.announcement_posted, Toast.LENGTH_SHORT).show()
        }
    }
    FloatingActionButton(
        onClick = { open = true },
        modifier = modifier.size(PrimaryTouchTarget),
        shape = LaraShapes.large,
        containerColor = LaraColors.PrimaryLight,
        contentColor = LaraColors.PrimaryDark,
    ) {
        Icon(painterResource(R.drawable.ic_pencil_simple), stringResource(R.string.announcement_post))
    }
    if (open) AnnouncementDialog(
        classroomName = classroom.name,
        reachable = reachable,
        status = status,
        onClose = { open = false; model.reset() },
        onPost = { title, content, comments -> model.send(title, content, comments, reachable) },
    )
}

@Composable
private fun AnnouncementDialog(classroomName: String, reachable: Boolean, status: AnnouncementStatus,
    onClose: () -> Unit, onPost: (String, String, Boolean) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var comments by rememberSaveable { mutableStateOf(true) }
    val posting = status == AnnouncementStatus.Posting
    val editable = !posting && status != AnnouncementStatus.Posted
    ClassFormDialog(
        title = stringResource(R.string.announcement_new),
        action = stringResource(if (posting) R.string.announcement_posting else R.string.announcement_send),
        enabled = reachable && editable && title.isNotBlank() && content.isNotBlank(),
        onAction = { onPost(title, content, comments) },
        onDismiss = onClose,
        canDismiss = editable,
    ) {
        Text(classroomName, style = MaterialTheme.typography.titleMedium)
        if (!reachable || status == AnnouncementStatus.Failed) {
            Text(stringResource(if (!reachable) R.string.announcement_needs_hub else R.string.announcement_failed),
                color = if (!reachable) LaraColors.TextSecondary else MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(value = title, onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).classFormInputScroll(),
            label = { Text(stringResource(R.string.announcement_title)) }, shape = LaraShapes.medium,
            singleLine = true, enabled = editable)
        OutlinedTextField(value = content, onValueChange = { content = it },
            modifier = Modifier.fillMaxWidth().classFormInputScroll(),
            label = { Text(stringResource(R.string.announcement_content)) },
            shape = LaraShapes.medium, minLines = 4, enabled = editable)
        Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
            .toggleable(comments, enabled = editable, role = Role.Checkbox, onValueChange = { comments = it }),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            Checkbox(checked = comments, onCheckedChange = null, enabled = editable)
            Text(stringResource(R.string.announcement_allow_comments))
        }
    }
}
