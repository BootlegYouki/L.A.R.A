package org.lara.app.ui.screens.stream

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.Cached
import org.lara.app.domain.model.Announcement
import org.lara.app.ui.theme.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ClassDiscussion(container: AppContainer, userId: String, post: Announcement,
    readOnly: Boolean, reload: suspend () -> Unit, back: () -> Unit,
    attachmentContent: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val flow = remember(container, post.id) { container.stream.comments(post.id, scope) }
    val cached by flow.collectAsStateWithLifecycle()
    val names by container.commentNames.collectAsStateWithLifecycle(emptyList())
    val comments = (cached as? Cached.Content)?.value.orEmpty()
    var draft by rememberSaveable(post.id) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(LaraColors.Surface).navigationBarsPadding().imePadding()) {
        Surface(color = LaraColors.Surface) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(AppBarHeight), verticalAlignment = Alignment.CenterVertically) {
                IconButton(back, Modifier.size(MinTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.settings_back))
                }
            }
        }
        org.lara.app.ui.components.PullRefresh(reload, Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = LaraSpacing.S4, vertical = LaraSpacing.S5),
                verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                        Text(post.title, style = MaterialTheme.typography.headlineMedium)
                        Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(post.createdAt)),
                            style = MaterialTheme.typography.bodySmall, color = LaraColors.TextSecondary)
                        if (post.content.isNotBlank()) Text(post.content, style = MaterialTheme.typography.bodyLarge)
                        attachmentContent()
                        HorizontalDivider(color = LaraColors.Border)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                            Icon(painterResource(R.drawable.ic_chats_regular), null, Modifier.size(24.dp))
                            Text(stringResource(R.string.class_comments_title), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (cached == Cached.Loading) item { org.lara.app.ui.components.LoadingSpinner() }
                else if (comments.isEmpty()) item {
                    Text(stringResource(R.string.class_comments_empty), color = LaraColors.TextSecondary)
                }
                items(comments, key = { it.id }) { comment ->
                    val author = names.find { it.id == comment.authorId }?.name ?: stringResource(R.string.class_comments_member)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = LaraSpacing.S2),
                        horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Surface(Modifier.size(40.dp), color = LaraColors.PrimaryLight, shape = CircleShape) {
                            Box(contentAlignment = Alignment.Center) { Text(author.take(1).uppercase(), color = LaraColors.PrimaryDark) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S1)) {
                            Text(author, style = MaterialTheme.typography.titleSmall)
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(comment.createdAt)),
                                style = MaterialTheme.typography.bodySmall, color = LaraColors.TextSecondary)
                            Text(comment.content, Modifier.padding(top = LaraSpacing.S1),
                                style = MaterialTheme.typography.bodyLarge)
                            if (comment.syncStatus != "SYNCED") Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S1)) {
                                Icon(painterResource(R.drawable.ic_chats_regular), null, Modifier.size(16.dp))
                                Text(stringResource(if (comment.syncStatus == "REJECTED") R.string.class_comments_rejected else R.string.reading_queued),
                                    style = MaterialTheme.typography.bodySmall, color = LaraColors.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = LaraColors.Border)
        if (readOnly || !post.allowComments) Text(stringResource(if (readOnly) R.string.class_comments_archived else R.string.class_comments_disabled),
            Modifier.padding(LaraSpacing.S4), color = LaraColors.TextSecondary)
        else Column(
            Modifier.padding(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2),
        ) {
            if (failed) Text(stringResource(R.string.class_comments_save_failed), color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f).heightIn(min = MinTouchTarget),
                    label = { Text(stringResource(R.string.class_comments_hint)) }, maxLines = 4,
                    enabled = !saving, shape = LaraShapes.large)
                IconButton(onClick = {
                    if (!saving && draft.isNotBlank()) {
                        saving = true; failed = false
                        val content = draft.trim()
                        scope.launch {
                            try {
                                container.stream.addComment(post.id, userId, content)
                                draft = ""
                                try { reload() }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) {
                                    // The durable outbox is already saved; WorkManager retries when the Hub returns.
                                }
                            }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { failed = true }
                            finally { saving = false }
                        }
                    }
                }, enabled = !saving && draft.isNotBlank(), modifier = Modifier.size(PrimaryTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_paper_plane), stringResource(R.string.class_comments_send))
                }
            }
        }
    }
}
