package org.lara.app.ui.screens.teacher.classwork

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import java.net.URI
import org.lara.app.R
import org.lara.app.ui.theme.MinTouchTarget

@Composable
internal fun AssignmentLinkDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val address = input.trim()
    val uri = runCatching { URI(address) }.getOrNull()
    val valid = uri?.host != null && uri.scheme?.lowercase() in setOf("http", "https") && uri.userInfo == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assignment_add_link)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assignment_link_address)) },
                supportingText = { Text(stringResource(R.string.assignment_link_help)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = address.isNotEmpty() && !valid,
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(address) }, enabled = valid, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.assignment_add_link))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.enrollment_cancel_action))
            }
        },
    )
}
