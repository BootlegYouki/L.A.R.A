package org.lara.app.ui.screens.teacher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.lara.app.R
import org.lara.app.domain.RosterMember
import org.lara.app.domain.Cached
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.theme.*

@Composable
fun PendingApprovalButton(state: ApprovalState, onClick: () -> Unit) {
    // Count is textual as well as a badge for accessibility.
    BadgedBox(badge = { if (state.pending.isNotEmpty()) Badge(
        containerColor = LaraColors.PrimaryLight, contentColor = LaraColors.PrimaryDark,
    ) { Text(state.pending.size.toString()) } }) {
        OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = MinTouchTarget), shape = LaraShapes.medium,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = LaraColors.PrimaryDark)) {
            Text(stringResource(R.string.approval_pending_count, state.pending.size))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(state: ApprovalState, onDismiss: () -> Unit, onDecision: (String, Boolean) -> Unit) {
    if (!state.sheetOpen) return
    ModalBottomSheet(onDismissRequest = onDismiss, shape = LaraShapes.extraLarge,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LaraColors.Surface, contentColor = LaraColors.TextPrimary,
        scrimColor = LaraColors.TextPrimary.copy(alpha = 0.5f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                item { Text(stringResource(R.string.approval_title), style = MaterialTheme.typography.headlineMedium) }
                item { Text(stringResource(R.string.approval_review_help), style = MaterialTheme.typography.bodyLarge, color = LaraColors.TextSecondary) }
                item {
                    if (!state.offline && state.error != null) DiscoveryBanner(when (state.error) {
                        ApprovalError.FORBIDDEN -> R.string.approval_forbidden
                        ApprovalError.SESSION_EXPIRED -> R.string.enrollment_session_expired
                        else -> R.string.approval_failed
                    }, warning = true, success = false)
                }
                if (state.roster == Cached.Loading) item { org.lara.app.ui.components.LoadingSpinner(inline = true) }
                else if (state.pending.isEmpty()) item { Text(stringResource(R.string.approval_empty)) }
                items(state.pending.take(1), key = { it.enrollment.id }) { member -> PendingMemberCard(member, state, onDecision) }
            }
            TextButton(onDismiss, Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
                colors = ButtonDefaults.textButtonColors(contentColor = LaraColors.PrimaryDark)) {
                Text(stringResource(R.string.enrollment_cancel_action))
            }
        }
     }
}


@Composable
internal fun PendingMemberCard(member: RosterMember, state: ApprovalState, onDecision: (String, Boolean) -> Unit) {
    val name = member.user?.fullName ?: stringResource(R.string.approval_learner)
    val id = member.enrollment.studentId
    val busy = id in state.busy
    val enabled = member.user != null
    var confirm by remember { mutableStateOf<Boolean?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val offlineHint = stringResource(R.string.approval_offline_toast)
    fun ask(accept: Boolean) { if (state.offline) android.widget.Toast.makeText(context, offlineHint, android.widget.Toast.LENGTH_SHORT).show() else confirm = accept }
    org.lara.app.ui.components.ClassroomSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = LaraColors.PrimaryLight) {
                Box(Modifier.size(40.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(name.trim().take(1).uppercase(), color = LaraColors.PrimaryDark, style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                member.user?.lrnOrId?.let { Text(stringResource(R.string.approval_lrn, it), style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextSecondary) }
            }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = LaraColors.PrimaryDark, strokeWidth = 2.dp)
            else {
                IconButton({ ask(false) }, Modifier.size(MinTouchTarget), enabled) {
                    Icon(painterResource(R.drawable.ic_x), stringResource(R.string.approval_decline), Modifier.size(24.dp))
                }
                IconButton({ ask(true) }, Modifier.size(MinTouchTarget), enabled,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = LaraColors.PrimaryDark, contentColor = LaraColors.Surface,
                        disabledContainerColor = LaraColors.Border, disabledContentColor = LaraColors.Surface)) {
                    Icon(painterResource(R.drawable.ic_check), stringResource(R.string.approval_accept), Modifier.size(24.dp))
                }
            }
        }
    }
    confirm?.let { accept ->
        org.lara.app.ui.components.NativeConfirmDialog(
            stringResource(if (accept) R.string.approval_confirm_accept else R.string.approval_confirm_decline, name),
            stringResource(if (accept) R.string.approval_confirm_accept_body else R.string.approval_confirm_decline_body),
            stringResource(if (accept) R.string.approval_accept else R.string.approval_decline), stringResource(R.string.enrollment_cancel_action),
            { confirm = null; onDecision(id, accept) }, { confirm = null })
    }
}
