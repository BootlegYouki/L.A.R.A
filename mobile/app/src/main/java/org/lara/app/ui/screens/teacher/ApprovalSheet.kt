package org.lara.app.ui.screens.teacher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.lara.app.R
import org.lara.app.domain.Cached
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.theme.*

@Composable
fun PendingApprovalButton(state: ApprovalState, onClick: () -> Unit) {
    // Count is textual as well as a badge for accessibility.
    BadgedBox(badge = { if (state.pending.isNotEmpty()) Badge { Text(state.pending.size.toString()) } }) {
        TextButton(onClick, Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.approval_pending_count, state.pending.size))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(state: ApprovalState, onDismiss: () -> Unit, onDecision: (String, Boolean) -> Unit) {
    if (!state.sheetOpen) return
    ModalBottomSheet(onDismissRequest = onDismiss, shape = LaraShapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(horizontal = LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            Text(stringResource(R.string.approval_title), style = MaterialTheme.typography.headlineSmall)
            if (state.offline) DiscoveryBanner(R.string.approval_offline, warning = true, success = false)
            else if (state.error != null) DiscoveryBanner(when (state.error) {
                ApprovalError.FORBIDDEN -> R.string.approval_forbidden
                ApprovalError.SESSION_EXPIRED -> R.string.enrollment_session_expired
                else -> R.string.approval_failed
            }, warning = true, success = false)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                if (state.roster == Cached.Loading) item { Text(stringResource(R.string.approval_loading)) }
                else if (state.pending.isEmpty()) item { Text(stringResource(R.string.approval_empty)) }
                items(state.pending.take(1), key = { it.enrollment.id }) { member ->
                    Column(Modifier.fillMaxWidth().padding(vertical = LaraSpacing.S2),
                        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                        Text(member.user?.fullName ?: stringResource(R.string.approval_learner),
                            style = MaterialTheme.typography.titleMedium)
                        member.user?.lrnOrId?.let { Text(stringResource(R.string.approval_lrn, it)) }
                        val enabled = !state.offline && member.enrollment.studentId !in state.busy && member.user != null
                        // Vertical actions keep both languages usable with large font sizes.
                        Button(onClick = { onDecision(member.enrollment.studentId, true) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                            Text(stringResource(R.string.approval_accept))
                        }
                        OutlinedButton(onClick = { onDecision(member.enrollment.studentId, false) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                            Text(stringResource(R.string.approval_decline))
                        }
                    }
                }
            }
            TextButton(onDismiss, Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.enrollment_cancel_action))
            }
        }
    }
}

