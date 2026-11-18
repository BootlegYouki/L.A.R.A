package org.lara.app.ui.screens.enrollment

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.lara.app.R
import org.lara.app.domain.enrollment.ClassCode
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.components.classFormInputScroll
import org.lara.app.ui.theme.*

enum class JoinClassError { INVALID_CODE, HUB_UNREACHABLE, REQUEST_FAILED, REFRESH_FAILED, SESSION_EXPIRED }

@OptIn(ExperimentalMaterial3Api::class)
/** Presentation only; the enrollment owner supplies reachability, request state, and persistence. */
@Composable
fun JoinClassDialog(
    isHubReachable: Boolean,
    busy: Boolean,
    error: JoinClassError?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onInputChanged: () -> Unit = {},
    learnerName: String? = null, // kept for callers; the sheet no longer shows it
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val invalidCode = invalid || error == JoinClassError.INVALID_CODE
    val label = stringResource(R.string.enrollment_code_label)
    val submit = {
        val code = ClassCode.canonicalOrNull(draft)
        if (code == null) invalid = true else onSubmit(code)
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = LaraColors.Surface) {
      Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = LaraSpacing.S4).padding(bottom = LaraSpacing.S6),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        Text(stringResource(R.string.enrollment_join_title), style = MaterialTheme.typography.titleLarge)
        Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            BasicTextField(
                value = draft,
                onValueChange = { v ->
                    draft = v.filter { it.isLetterOrDigit() }.uppercase().take(6)
                    invalid = false; onInputChanged()
                },
                enabled = !busy, singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().classFormInputScroll().semantics { contentDescription = label },
                decorationBox = {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        repeat(6) { i ->
                            if (i == 3) Spacer(Modifier.width(LaraSpacing.S3))
                            else if (i > 0) Spacer(Modifier.width(LaraSpacing.S2))
                            val current = i == draft.length
                            Box(Modifier.weight(1f).height(PrimaryTouchTarget).background(LaraColors.Surface, LaraShapes.medium)
                                .border(if (current || invalidCode) 2.dp else 1.dp,
                                    if (invalidCode) LaraColors.Danger else if (current) LaraColors.Primary else LaraColors.Border,
                                    LaraShapes.medium), contentAlignment = Alignment.Center) {
                                if (i < draft.length) Text(draft[i].toString(), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                },
            )
            if (invalidCode) {
                Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    Icon(painterResource(R.drawable.ic_warning_circle), null, tint = LaraColors.DangerDark)
                    Text(stringResource(R.string.enrollment_code_invalid), color = LaraColors.DangerDark)
                }
            }
        }
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
        Button(onClick = submit, enabled = !busy && isHubReachable,
            colors = ButtonDefaults.buttonColors(containerColor = LaraColors.PrimaryDark, contentColor = LaraColors.Surface),
            modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
            Text(stringResource(R.string.enrollment_join_action))
        }
      }
    }
}
