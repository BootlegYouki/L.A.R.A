package org.lara.app.ui.screens.discovery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import org.lara.app.R
import org.lara.app.ui.theme.LaraSpacing
import org.lara.app.ui.theme.LaraTheme

/**
 * The elementary-friendly manual IP fallback (issue sub-task 5, `rules/networking-and-lan.md` §4.2).
 * When a sub-₱1,500 router has AP/client isolation on and both mDNS and the UDP beacon are blocked,
 * the pupil or teacher types the Hub address their teacher reads out. The input is validated with
 * the issue's regex in the ViewModel; here we only render the field, the inline error and the
 * actions. Short sentences, a concrete example and a numeric keyboard keep it approachable for
 * Grades 1 to 6.
 */
@Composable
fun ManualIpDialog(
    value: String,
    error: ManualInputError?,
    onValueChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorText = when (error) {
        ManualInputError.EMPTY -> stringResource(R.string.manual_dialog_error_empty)
        ManualInputError.INVALID_FORMAT -> stringResource(R.string.manual_dialog_error_format)
        null -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.manual_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.manual_dialog_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    isError = errorText != null,
                    label = { Text(stringResource(R.string.manual_dialog_label)) },
                    placeholder = { Text(stringResource(R.string.manual_dialog_hint)) },
                    keyboardOptions = KeyboardOptions(
                        // IP + optional ":port" are digits, dots and a colon; a number pad is friendliest.
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Go,
                    ),
                    supportingText = errorText?.let {
                        {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = LaraSpacing.S4),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConnect) {
                Text(stringResource(R.string.manual_dialog_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.manual_dialog_cancel))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun ManualIpDialogPreview() {
    LaraTheme {
        ManualIpDialog(
            value = "192.168.1.",
            error = ManualInputError.INVALID_FORMAT,
            onValueChange = {},
            onConnect = {},
            onDismiss = {},
        )
    }
}
