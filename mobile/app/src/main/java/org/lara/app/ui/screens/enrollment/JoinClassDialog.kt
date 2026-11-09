package org.lara.app.ui.screens.enrollment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import org.lara.app.R
import org.lara.app.domain.enrollment.ClassCode
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.components.ClassFormDialog
import org.lara.app.ui.components.classFormInputScroll
import org.lara.app.ui.theme.*

enum class JoinClassError { INVALID_CODE, HUB_UNREACHABLE, REQUEST_FAILED, REFRESH_FAILED, SESSION_EXPIRED }

/** Presentation only; the enrollment owner supplies reachability, request state, and persistence. */
@Composable
fun JoinClassDialog(
    isHubReachable: Boolean,
    busy: Boolean,
    error: JoinClassError?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onInputChanged: () -> Unit = {},
    learnerName: String? = null,
) {
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val invalidCode = invalid || error == JoinClassError.INVALID_CODE
    val label = stringResource(R.string.enrollment_code_label)
    ClassFormDialog(
        title = stringResource(R.string.enrollment_join_title),
        action = stringResource(R.string.enrollment_join_action), enabled = !busy && isHubReachable,
        onAction = {
            val code = ClassCode.canonicalOrNull(draft.text)
            if (code == null) invalid = true else onSubmit(code)
        }, onDismiss = onDismiss, canDismiss = !busy,
    ) {
        learnerName?.takeIf { it.isNotBlank() }?.let { name ->
            Text(stringResource(R.string.enrollment_signed_in_as), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = LaraColors.PrimaryLight, contentColor = LaraColors.PrimaryDark) {
                    Box(Modifier.size(LaraSpacing.S12), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text(name.trim().take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(stringResource(R.string.enrollment_code_help))
        Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.text.length <= 32) { draft = it; invalid = false; onInputChanged() } },
                visualTransformation = GroupedClassCode,
                enabled = !busy, singleLine = true, isError = invalidCode,
                shape = LaraShapes.medium,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).classFormInputScroll().semantics { contentDescription = label },
            )
            if (invalidCode) {
                Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    Icon(painterResource(R.drawable.ic_warning_circle), null, tint = LaraColors.DangerDark)
                    Text(stringResource(R.string.enrollment_code_invalid), color = LaraColors.DangerDark)
                }
            }
        }
        Text(stringResource(R.string.enrollment_approval_help), color = LaraColors.TextSecondary)
        when {
            !isHubReachable || error == JoinClassError.HUB_UNREACHABLE ->
                DiscoveryBanner(R.string.enrollment_join_offline, warning = true, success = false)
            error == JoinClassError.REQUEST_FAILED ->
                DiscoveryBanner(R.string.enrollment_join_failed, warning = true, success = false)
            error == JoinClassError.REFRESH_FAILED ->
                DiscoveryBanner(R.string.enrollment_refresh_failed, warning = true, success = false)
            error == JoinClassError.SESSION_EXPIRED ->
                DiscoveryBanner(R.string.enrollment_session_expired, warning = true, success = false)
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = LaraColors.PrimaryDark, trackColor = LaraColors.Border)
            Text(stringResource(R.string.enrollment_join_sending))
        }
    }
}
