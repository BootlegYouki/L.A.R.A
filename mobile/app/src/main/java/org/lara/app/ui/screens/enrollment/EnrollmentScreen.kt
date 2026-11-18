package org.lara.app.ui.screens.enrollment

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.lara.app.R
import org.lara.app.domain.Cached
import org.lara.app.domain.enrollment.*
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.theme.*

data class EnrollmentScreenState(
    val classes: Cached<List<LearnerClass>> = Cached.Loading,
    val requests: List<EnrollmentNotice> = emptyList(),
    val noticesLoaded: Boolean = false,
    val offline: Boolean = true,
    val busy: Boolean = false,
    val showJoinDialog: Boolean = false,
    val error: JoinClassError? = null,
) {
    fun openableClass(classroomId: String?): LearnerClass? =
        (classes as? Cached.Content)?.value?.firstOrNull { row ->
            noticesLoaded &&
            row.classroom.id == classroomId && row.canOpen &&
                requests.none { it.classroomId == classroomId && it.status != EnrollmentStatus.ACTIVE }
        }
}

/** Request notices alone do not grant access; verified membership is read from Room. */
@Composable
fun EnrollmentScreen(
    state: EnrollmentScreenState,
    onJoin: () -> Unit,
    onDismissJoin: () -> Unit,
    onSubmit: (String) -> Unit,
    onOpenClass: (String) -> Unit,
    onCodeChanged: () -> Unit = {},
    teacherNames: Map<String, String> = emptyMap(),
    showJoinAction: Boolean = true,
    learnerName: String? = null,
) {
    val classes = (state.classes as? Cached.Content)?.value.orEmpty()
    val cachedIds = classes.map { it.classroom.id }.toSet()
    val requests = state.requests.filterNot { it.classroomId in cachedIds }
    val loadingLabel = stringResource(R.string.enrollment_loading)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = LaraSpacing.S4, end = LaraSpacing.S4,
        top = LaraSpacing.S4, bottom = PrimaryTouchTarget + LaraSpacing.S8),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        if (!state.offline && !state.showJoinDialog && state.error != null) item {
            val message = when(state.error) {
                JoinClassError.SESSION_EXPIRED -> R.string.enrollment_session_expired
                JoinClassError.REFRESH_FAILED -> R.string.enrollment_refresh_failed
                JoinClassError.INVALID_CODE -> R.string.enrollment_code_invalid
                JoinClassError.HUB_UNREACHABLE -> R.string.enrollment_join_offline
                else -> R.string.enrollment_join_failed
            }
            DiscoveryBanner(message, warning = true, success = false)
        }
        val empty = state.classes != Cached.Loading && classes.isEmpty() && requests.isEmpty()
        if (showJoinAction && !empty) item {
            Button(onClick = onJoin, enabled = !state.offline && !state.busy,
                colors = ButtonDefaults.buttonColors(containerColor = LaraColors.PrimaryDark, contentColor = LaraColors.Surface),
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                Text(stringResource(R.string.enrollment_join_title))
            }
        }
        if (state.classes == Cached.Loading) {
            items(3) { Spacer(Modifier.fillMaxWidth().height(96.dp)
                .background(LaraColors.SurfaceSubtle, LaraShapes.large)
                .semantics { contentDescription = loadingLabel }) }
        } else if (classes.isEmpty() && requests.isEmpty()) {
            item {
                Column(Modifier.fillParentMaxHeight(0.9f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3, Alignment.CenterVertically)) {
                    Box(Modifier.size(96.dp).background(LaraColors.PrimaryLight, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_books_regular), null, tint = LaraColors.PrimaryDark, modifier = Modifier.size(48.dp))
                    }
                    Text(stringResource(R.string.enrollment_empty), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.enrollment_empty_help), color = LaraColors.TextSecondary, textAlign = TextAlign.Center)
                    TextButton(onClick = onJoin, enabled = !state.offline && !state.busy,
                        modifier = Modifier.heightIn(min = PrimaryTouchTarget)) {
                        Text(stringResource(R.string.enrollment_join_title))
                    }
                }
            }
        }
        items(classes, key = { "saved:" + it.classroom.id }) { item ->
            val notice = state.requests.firstOrNull { it.classroomId == item.classroom.id }
            val denied = notice != null && notice.status != EnrollmentStatus.ACTIVE
            EnrollmentCard(item.classroom.name, item.classroom.section,
                if (denied) requireNotNull(notice).status else item.status,
                state.openableClass(item.classroom.id) != null,
                onOpen = { onOpenClass(item.classroom.id) }, teacher = teacherNames[item.classroom.teacherId])
        }
        items(requests, key = { "request:" + it.classroomId }) { item ->
            EnrollmentCard(item.title ?: stringResource(R.string.enrollment_request_title), null,
                item.status, canOpen = false, onOpen = {})
        }
    }
    if (state.showJoinDialog) JoinClassDialog(!state.offline, state.busy, state.error, onDismissJoin, onSubmit, onCodeChanged, learnerName)
}

@Composable
private fun EnrollmentCard(title: String, section: String?, status: EnrollmentStatus, canOpen: Boolean, onOpen: () -> Unit, teacher: String? = null) {
    val label = when (status) {
        EnrollmentStatus.PENDING -> R.string.enrollment_pending
        EnrollmentStatus.ACTIVE -> if(canOpen) R.string.enrollment_active else R.string.enrollment_approval_received
        EnrollmentStatus.REJECTED -> R.string.enrollment_rejected
        EnrollmentStatus.REMOVED -> R.string.enrollment_removed
    }
    val context = LocalContext.current
    val message = stringResource(label)
    org.lara.app.ui.components.ClassroomCard(title, section, teacher, message, canOpen,
        if (canOpen) onOpen else { { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() } })
}
