package org.lara.app.ui.screens.classwork

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.data.remote.TutorEvent
import org.lara.app.domain.model.Material
import org.lara.app.ui.components.ClassroomBackBar
import org.lara.app.ui.theme.*

private data class TutorMessage(val fromTutor: Boolean, val text: String)

/** Full-screen Socratic tutor chat for one material; the Hub builds the prompt and streams the reply. */
@Composable
fun TutorChat(container: AppContainer, material: Material, close: () -> Unit) {
    BackHandler(onBack = close)
    val messages = remember(material.id) { mutableStateListOf<TutorMessage>() }
    var input by rememberSaveable(material.id) { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    LaunchedEffect(messages.lastOrNull()?.text, messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex) }
    val unreachable = stringResource(R.string.tutor_unreachable)
    val notAvailable = stringResource(R.string.tutor_not_available)
    val locked = stringResource(R.string.tutor_quiz_locked)
    val busyMsg = stringResource(R.string.tutor_busy)
    val failed = stringResource(R.string.tutor_failed)

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""; busy = true; status = null
        messages += TutorMessage(false, text)
        messages += TutorMessage(true, "")
        job = scope.launch {
            try {
                container.askTutor(material.classroomId, material.id, text).collect { e ->
                    when (e) {
                        is TutorEvent.Queue -> status = e.message
                        is TutorEvent.Token -> { status = null; messages[messages.lastIndex] = TutorMessage(true, messages.last().text + e.text) }
                        TutorEvent.Done -> Unit
                        is TutorEvent.Failed -> status = when (e.code) {
                            "AI_NOT_AVAILABLE" -> notAvailable
                            "QUIZ_IN_PROGRESS" -> locked
                            "AI_QUEUE_FULL" -> busyMsg
                            else -> failed
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = unreachable }
            finally {
                if (messages.lastOrNull()?.let { it.fromTutor && it.text.isEmpty() } == true) messages.removeAt(messages.lastIndex)
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(LaraColors.Surface)) {
        ClassroomBackBar(close, stringResource(R.string.tutor_title))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            if (messages.isEmpty()) item {
                // Purple is reserved for the tutor, so the sparkle sits on the tutor's own tint.
                Column(Modifier.fillParentMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(72.dp).background(LaraColors.AiLight, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_sparkle_regular), null, Modifier.size(36.dp), tint = LaraColors.AiDark)
                    }
                    Spacer(Modifier.height(LaraSpacing.S4))
                    Text(stringResource(R.string.tutor_intro, material.title), style = MaterialTheme.typography.bodyLarge,
                        color = LaraColors.TextSecondary, textAlign = TextAlign.Center)
                }
            }
            items(messages.size) { i ->
                val m = messages[i]
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromTutor) Alignment.CenterStart else Alignment.CenterEnd) {
                    Surface(color = if (m.fromTutor) LaraColors.SurfaceSubtle else LaraColors.PrimaryDark,
                        contentColor = if (m.fromTutor) LaraColors.TextPrimary else Color.White, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.widthIn(max = 300.dp)) {
                        if (m.text.isEmpty()) CircularProgressIndicator(Modifier.padding(LaraSpacing.S3).size(20.dp), strokeWidth = 2.dp)
                        else Text(m.text, Modifier.padding(horizontal = LaraSpacing.S3, vertical = LaraSpacing.S2), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        status?.let { Text(it, Modifier.padding(horizontal = LaraSpacing.S4, vertical = LaraSpacing.S1), style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextSecondary) }
        Row(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(LaraSpacing.S3), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            OutlinedTextField(input, { input = it.take(500) }, Modifier.weight(1f), enabled = !busy, singleLine = true, shape = RoundedCornerShape(28.dp),
                placeholder = { Text(stringResource(R.string.tutor_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            FilledIconButton(onClick = ::send, enabled = !busy && input.isNotBlank(), modifier = Modifier.size(MinTouchTarget),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = LaraColors.PrimaryDark)) {
                Icon(painterResource(R.drawable.ic_paper_plane), stringResource(R.string.tutor_send), Modifier.size(24.dp), tint = Color.White)
            }
        }
    }
}
