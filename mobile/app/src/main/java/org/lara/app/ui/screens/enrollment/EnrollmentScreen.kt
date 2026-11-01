package org.lara.app.ui.screens.enrollment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

/** Transient request notices do not grant access. Only an ACTIVE Room enrollment opens a class. */
@Composable
fun EnrollmentScreen(
    state: EnrollmentScreenState,
    onJoin: () -> Unit,
    onDismissJoin: () -> Unit,
    onSubmit: (String) -> Unit,
    onOpenClass: (String) -> Unit,
    onCodeChanged: () -> Unit = {},
) {
    val classes = (state.classes as? Cached.Content)?.value.orEmpty()
    val cachedIds = classes.map { it.classroom.id }.toSet()
    val requests = state.requests.filterNot { it.classroomId in cachedIds }
    val loadingLabel = stringResource(R.string.enrollment_loading)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(LaraSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        item { Text(stringResource(R.string.enrollment_classes), style = MaterialTheme.typography.headlineMedium) }
        if (state.offline) item {
            DiscoveryBanner(R.string.enrollment_join_offline, warning = true, success = false)
        }
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
        item {
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
                Surface(color = LaraColors.Surface, shape = LaraShapes.large) {
                    Column(Modifier.fillMaxWidth().padding(LaraSpacing.S6), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                        Icon(painterResource(R.drawable.ic_books_regular), null, tint = LaraColors.PrimaryDark)
                        Text(stringResource(R.string.enrollment_empty), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.enrollment_empty_help), color = LaraColors.TextSecondary)
                    }
                }
            }
        }
        items(classes, key = { "saved:" + it.enrollment.id }) { item ->
            val notice = state.requests.firstOrNull { it.classroomId == item.classroom.id }
            val denied = notice != null && notice.status != EnrollmentStatus.ACTIVE
            EnrollmentCard(item.classroom.name, item.classroom.section,
                if (denied) requireNotNull(notice).status else EnrollmentStatus.valueOf(item.enrollment.status),
                state.openableClass(item.classroom.id) != null,
                onOpen = { onOpenClass(item.classroom.id) })
        }
        items(requests, key = { "request:" + it.classroomId }) { item ->
            EnrollmentCard(item.title ?: stringResource(R.string.enrollment_request_title), null,
                item.status, canOpen = false, onOpen = {})
        }
    }
    if (state.showJoinDialog) JoinClassDialog(!state.offline, state.busy, state.error, onDismissJoin, onSubmit, onCodeChanged)
}

@Composable
private fun EnrollmentCard(title: String, section: String?, status: EnrollmentStatus, canOpen: Boolean, onOpen: () -> Unit) {
    val active = status == EnrollmentStatus.ACTIVE
    val label = when (status) {
        EnrollmentStatus.PENDING -> R.string.enrollment_pending
        EnrollmentStatus.ACTIVE -> if(canOpen) R.string.enrollment_active else R.string.enrollment_approval_received
        EnrollmentStatus.REJECTED -> R.string.enrollment_rejected
        EnrollmentStatus.REMOVED -> R.string.enrollment_removed
    }
    Surface(color = LaraColors.Surface, shape = LaraShapes.large, border = BorderStroke(1.dp, LaraColors.Border),
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable(enabled = canOpen, onClick = onOpen)) {
        Column(Modifier.padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            section?.let { Text(it, color = LaraColors.TextSecondary) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                Icon(painterResource(if(active) R.drawable.ic_check_circle else R.drawable.ic_warning_circle), null,
                    tint = if(active) LaraColors.PrimaryDark else LaraColors.WarningDark)
                Text(stringResource(label), color = if(active) LaraColors.PrimaryDark else LaraColors.WarningBannerText)
            }
            if(canOpen) Text(stringResource(R.string.enrollment_open), color = LaraColors.PrimaryDark,
                style = MaterialTheme.typography.labelLarge)
        }
    }
}
