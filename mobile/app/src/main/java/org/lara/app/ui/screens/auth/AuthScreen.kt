package org.lara.app.ui.screens.auth

import android.content.res.Configuration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import org.lara.app.R
import org.lara.app.ui.components.ClassroomActionRow
import org.lara.app.ui.components.ClassroomSurface
import org.lara.app.ui.components.ClassroomTopBar
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.components.classFormInputScroll
import org.lara.app.ui.theme.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AuthScreen(state: AuthState, onSubmit: (String, String, String?) -> Unit, onDiscovery: () -> Unit,
    onSettings: (() -> Unit)? = null) {
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
    val idLabel = stringResource(if (registering) R.string.auth_lrn else R.string.auth_id)
    val nameLabel = stringResource(R.string.auth_name)
    val nameFocus = remember { FocusRequester() }
    val pinFocus = remember { FocusRequester() }
    val errorLocation = remember { BringIntoViewRequester() }
    LaunchedEffect(state.error) {
        if (state.error != null) {
            kotlinx.coroutines.delay(350)
            errorLocation.bringIntoView()
        }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() { focus.clearFocus(); keyboard?.hide() }
    val invalid = state.error == R.string.auth_invalid || state.error == R.string.auth_wrong_pin
    val canSubmit = !state.busy && !retrying && id.isNotBlank() && pin.length == 4 && (!registering || name.isNotBlank())
    fun submit() {
        if (canSubmit) {
            dismissKeyboard()
            onSubmit(id, pin, if (registering) name else null)
            pin = ""
        }
    }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // At large font sizes, a landscape IME can leave less than one text line below the app bar.
    val compactKeyboard = keyboardOpen && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Scaffold(Modifier.imePadding(), containerColor = LaraColors.Canvas, topBar = {
        if (!compactKeyboard) ClassroomTopBar(stringResource(R.string.app_name))
    }, bottomBar = {
        if (!keyboardOpen) Surface(color = LaraColors.Surface) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(LaraSpacing.S4)) {
                Button(onClick = ::submit,
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget), shape = LaraShapes.medium) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(LaraSpacing.S5), color = LaraColors.Surface)
                        Spacer(Modifier.width(LaraSpacing.S2))
                        Text(stringResource(R.string.auth_checking))
                    } else Text(stringResource(if (registering) R.string.auth_register else R.string.auth_login))
                }
            }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            Text(stringResource(if (registering) R.string.auth_register else R.string.auth_login),
                style = MaterialTheme.typography.headlineMedium)
            ClassroomActionRow(stringResource(R.string.discovery_open), stringResource(R.string.auth_hub_step_help),
                R.drawable.ic_broadcast, enabled = !state.busy, onClick = { dismissKeyboard(); onDiscovery() })
            ClassroomSurface {
                state.error?.let {
                    Box(Modifier.bringIntoViewRequester(errorLocation)) {
                        DiscoveryBanner(it, warning = it != R.string.auth_hub_required, success = false)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    Text(idLabel, style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(value = id, onValueChange = { id = it.take(32) }, enabled = !state.busy,
                        singleLine = true, shape = LaraShapes.medium, isError = invalid,
                        keyboardOptions = KeyboardOptions(keyboardType = if (registering) KeyboardType.Number else KeyboardType.Ascii,
                            imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onNext = { if (registering) nameFocus.requestFocus() else pinFocus.requestFocus() }),
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).classFormInputScroll()
                            .semantics { contentDescription = idLabel })
                    Text(stringResource(if (registering) R.string.auth_lrn_help else R.string.auth_id_help),
                        style = MaterialTheme.typography.bodyLarge, color = LaraColors.TextSecondary)
                }
                if (registering) Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    Text(nameLabel, style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, enabled = !state.busy,
                        singleLine = true, shape = LaraShapes.medium,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { pinFocus.requestFocus() }),
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).focusRequester(nameFocus)
                            .classFormInputScroll().semantics { contentDescription = nameLabel })
                }
                if (retrying) Text(stringResource(R.string.auth_retry_seconds, ((state.retryAt - now + 999) / 1000).toInt()),
                    style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.auth_pin), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.auth_pin_help), style = MaterialTheme.typography.bodyLarge, color = LaraColors.TextSecondary)
                val pinLabel = stringResource(R.string.auth_pin)
                OutlinedTextField(value = pin, onValueChange = { value ->
                    pin = value.filter { it in '0'..'9' }.take(4)
                }, enabled = !state.busy, singleLine = true, shape = LaraShapes.medium,
                    isError = invalid, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() else dismissKeyboard() }),
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                        .focusRequester(pinFocus).classFormInputScroll()
                        .semantics { contentDescription = pinLabel })

            }
            TextButton(onClick = { dismissKeyboard(); registering = !registering; pin = "" }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                Text(stringResource(if (registering) R.string.auth_login_instead else R.string.auth_register_instead))
            }
            Text(stringResource(R.string.auth_teacher_help), style = MaterialTheme.typography.bodyLarge, color = LaraColors.TextSecondary)
            onSettings?.let { open ->
                TextButton(onClick = open, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.settings_title))
                }
            }
        }
    }
}
