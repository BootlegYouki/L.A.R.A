package org.lara.app.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.lara.app.R
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.theme.*

@Composable
fun AuthScreen(state: AuthState, onSubmit: (String, String, String?) -> Unit, onDiscovery: () -> Unit) {
    var registering by remember { mutableStateOf(false) }
    // Credentials deliberately do not use rememberSaveable or a SavedStateHandle.
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.retryAt) {
        now = android.os.SystemClock.elapsedRealtime()
        while (now < state.retryAt) {
            kotlinx.coroutines.delay(1000)
            now = android.os.SystemClock.elapsedRealtime()
        }
    }
    val retrying = now < state.retryAt
    val idLabel = stringResource(R.string.auth_id)
    val nameLabel = stringResource(R.string.auth_name)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = LaraColors.PrimaryDark)
        Text(stringResource(if(registering) R.string.auth_register else R.string.auth_login), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.auth_teacher_help), style = MaterialTheme.typography.bodyLarge)
        state.error?.let { DiscoveryBanner(it, warning = true, success = false) }
        Text(idLabel, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(value = id, onValueChange = { id = it.take(32) }, enabled = !state.busy,
            singleLine = true, shape = LaraShapes.medium,
            isError = state.error == R.string.auth_invalid || state.error == R.string.auth_wrong_pin,
            keyboardOptions = KeyboardOptions(keyboardType = if(registering) KeyboardType.Number else KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                .semantics { contentDescription = idLabel })
        if(registering) {
            Text(nameLabel, style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(value = name, onValueChange = { name = it.take(120) }, enabled = !state.busy,
                singleLine = true, shape = LaraShapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                    .semantics { contentDescription = nameLabel })
        }
        if (retrying) Text(stringResource(R.string.auth_retry_seconds, ((state.retryAt - now + 999) / 1000).toInt()))
        Text(stringResource(R.string.auth_pin), style = MaterialTheme.typography.labelLarge)
        // Four individually masked boxes match the PIN component without opening a second keypad.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(4) { index ->
                val description = stringResource(R.string.auth_pin_digit, index + 1)
                OutlinedTextField(value = if(index < pin.length) "*" else "", onValueChange = {}, readOnly = true,
                    visualTransformation = PasswordVisualTransformation(), shape = LaraShapes.medium,
                    isError = state.error == R.string.auth_invalid || state.error == R.string.auth_wrong_pin,
                    modifier = Modifier.width(52.dp).heightIn(min = 60.dp).semantics { contentDescription = description })
            }
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "delete")).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { value ->
                    if(value.isEmpty()) Spacer(Modifier.weight(1f)) else OutlinedButton(
                        enabled = !state.busy,
                        onClick = { if(value == "delete") pin = pin.dropLast(1) else if(pin.length < 4) pin += value },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(if(value == "delete") stringResource(R.string.auth_delete) else value)
                    }
                }
            }
        }
        Button(onClick = { onSubmit(id, pin, if(registering) name else null); pin = "" },
            enabled = !state.busy && !retrying && id.isNotBlank() && pin.length == 4 && (!registering || name.isNotBlank()),
            modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
            if(state.busy) CircularProgressIndicator(Modifier.size(24.dp), color = LaraColors.Surface)
            else Text(stringResource(if(registering) R.string.auth_register else R.string.auth_login))
        }
        if(state.busy) Text(stringResource(R.string.auth_checking))
        TextButton(onClick = { registering = !registering; pin = "" }, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
            Text(stringResource(if(registering) R.string.auth_login_instead else R.string.auth_register_instead))
        }
        OutlinedButton(onClick = onDiscovery, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.discovery_open)) }
    }
}
