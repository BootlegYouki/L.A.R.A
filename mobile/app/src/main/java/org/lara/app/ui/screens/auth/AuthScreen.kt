package org.lara.app.ui.screens.auth

import android.content.res.Configuration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
import org.lara.app.domain.model.AuthPin
import org.lara.app.ui.components.ClassroomSurface
import org.lara.app.ui.components.ClassroomTopBar
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.components.classFormInputScroll
import org.lara.app.ui.theme.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AuthScreen(state: AuthState, onSubmit: (String, String, String?) -> Unit, onDiscovery: () -> Unit,
    onSettings: (() -> Unit)? = null, hub: String? = null, hubReachable: Boolean? = null) {
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
    fun canSubmitNow() = !state.busy && !retrying && id.isNotBlank() && AuthPin.isValid(pin) && (!registering || name.isNotBlank())
    val canSubmit = canSubmitNow()
    fun submit() {
        if (canSubmitNow()) {
            dismissKeyboard()
            onSubmit(id, pin, if (registering) name else null)
            pin = ""
        }
    }
    Scaffold(Modifier.imePadding(), containerColor = LaraColors.Canvas) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).statusBarsPadding()) {
        Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4, Alignment.CenterVertically)) {
            // Placeholder mark until the real L.A.R.A. logo is chosen.
            Image(painterResource(R.drawable.ic_logo_placeholder), null, Modifier.align(Alignment.CenterHorizontally).size(72.dp))
            Text(stringResource(if (registering) R.string.auth_register else R.string.auth_login),
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
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
                    if (registering) Text(stringResource(R.string.auth_lrn_help),
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
                val pinLabel = stringResource(R.string.auth_pin)
                BasicTextField(value = pin, onValueChange = { value ->
                    pin = value.filter { it in '0'..'9' }.take(AuthPin.LENGTH)
                }, enabled = !state.busy, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { if (canSubmitNow()) submit() else dismissKeyboard() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(pinFocus).classFormInputScroll()
                        .semantics { contentDescription = pinLabel },
                    decorationBox = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2, Alignment.CenterHorizontally)) {
                            repeat(AuthPin.LENGTH) { i ->
                                val current = i == pin.length
                                Box(Modifier.weight(1f).height(PrimaryTouchTarget).background(LaraColors.Surface, LaraShapes.medium)
                                    .border(if (current || (invalid && i < pin.length)) 2.dp else 1.dp,
                                        if (invalid) LaraColors.Danger else if (current) LaraColors.Primary else LaraColors.Border,
                                        LaraShapes.medium), contentAlignment = Alignment.Center) {
                                    if (i < pin.length) Box(Modifier.size(14.dp).background(LaraColors.TextPrimary, CircleShape))
                                }
                            }
                        }
                    })

            }
            Button(onClick = ::submit, enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget), shape = LaraShapes.medium) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(LaraSpacing.S5), color = LaraColors.Surface)
                    Spacer(Modifier.width(LaraSpacing.S2))
                    Text(stringResource(R.string.auth_checking))
                } else Text(stringResource(if (registering) R.string.auth_register else R.string.auth_login))
            }
            TextButton(onClick = { dismissKeyboard(); registering = !registering; pin = "" }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                Text(stringResource(if (registering) R.string.auth_login_instead else R.string.auth_register_instead))
            }
            HubChip(hub, hubReachable, !state.busy) { dismissKeyboard(); onDiscovery() }
            onSettings?.let { open ->
                TextButton(onClick = open, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.settings_title))
                }
            }
        }
        }
    }
}

@Composable
private fun HubChip(hub: String?, reachable: Boolean?, enabled: Boolean, onChange: () -> Unit) {
    val warning = hub == null || reachable == false
    val (icon, text) = if (hub == null) R.drawable.ic_warning_circle to stringResource(R.string.auth_hub_none) else when (reachable) {
        true -> R.drawable.ic_check_circle to stringResource(R.string.auth_hub_connected, hub)
        false -> R.drawable.ic_warning_circle to stringResource(R.string.auth_hub_unreachable, hub)
        null -> R.drawable.ic_broadcast to stringResource(R.string.auth_hub_checking, hub)
    }
    val ink = if (warning) LaraColors.WarningBannerText else LaraColors.PrimaryDark
    Surface(color = if (warning) LaraColors.WarningLight else LaraColors.PrimaryLight, shape = LaraShapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (warning) LaraColors.WarningBorder else LaraColors.SuccessBorder),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = LaraSpacing.S3), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = ink)
            TextButton(onClick = onChange, enabled = enabled, modifier = Modifier.heightIn(min = MinTouchTarget),
                colors = ButtonDefaults.textButtonColors(contentColor = ink)) { Text(stringResource(if (hub == null) R.string.auth_hub_find else R.string.auth_hub_change)) }
        }
    }
}
