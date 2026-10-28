package org.lara.app.ui.screens.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.lara.app.ui.theme.laraShadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.lara.app.R
import org.lara.app.domain.discovery.ConnectionResult
import org.lara.app.ui.theme.LaraColors
import org.lara.app.ui.theme.LaraShapes
import org.lara.app.ui.theme.LaraSpacing
import org.lara.app.ui.theme.MinTouchTarget
import org.lara.app.ui.theme.PrimaryTouchTarget

@Composable
fun DiscoveryScreen(model: DiscoveryViewModel, onBack: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.start()
            if (event == Lifecycle.Event.ON_STOP) model.stop()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.start()
        onDispose { lifecycle.removeObserver(observer); model.stop() }
    }
    val state by model.state.collectAsStateWithLifecycle()
    val hubs by model.hubs.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    val connecting by model.connecting.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val message = when {
        connecting -> R.string.discovery_checking
        result == ConnectionResult.UNREACHABLE -> R.string.discovery_unreachable
        result == ConnectionResult.RESPONDING -> R.string.discovery_responding
        !state.wifiAvailable -> R.string.discovery_offline
        state.warning -> R.string.discovery_warning
        state.scanning -> R.string.discovery_scanning
        hubs.isEmpty() -> R.string.discovery_empty
        else -> R.string.discovery_choose
    }
    val warning = !connecting && result != ConnectionResult.RESPONDING &&
        (result == ConnectionResult.UNREACHABLE || !state.wifiAvailable || state.warning)
    val success = !connecting && result == ConnectionResult.RESPONDING
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(LaraSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4),
    ) {
        item { Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.headlineMedium) }
        item { HubBeaconPill(hubBeacon(state, result, connecting)) }
        if (warning || success || connecting || state.scanning || hubs.isNotEmpty()) {
            item { DiscoveryBanner(message, warning, success) }
        }
        item {
            Text(stringResource(R.string.discovery_results), style = MaterialTheme.typography.labelLarge)
        }
        if (hubs.isEmpty()) {
            item { Text(stringResource(R.string.discovery_empty), style = MaterialTheme.typography.bodyLarge) }
            if (state.scanning) {
                items(2) {
                    Surface(
                        color = LaraColors.Border, shape = LaraShapes.large,
                        modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget),
                    ) {
                        Text(stringResource(R.string.discovery_scanning), modifier = Modifier.padding(LaraSpacing.S4))
                    }
                }
            }
        }
        items(hubs, key = { it.key }) { hub ->
            OutlinedButton(
                onClick = { model.connect(hub) }, enabled = !connecting,
                shape = LaraShapes.large,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LaraColors.PrimaryDark),
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget),
            ) {
                Column(Modifier.fillMaxWidth().padding(LaraSpacing.S3)) {
                    Text(hub.name, style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.discovery_hub_address, hub.ip, hub.httpPort))
                }
            }
        }
        item {
            OutlinedButton(
                onClick = model::retry,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LaraColors.PrimaryDark),
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            ) { Text(stringResource(R.string.discovery_retry)) }
        }
        item {
            Column(
                Modifier.padding(top = LaraSpacing.S2),
                verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4),
            ) {
                Text(stringResource(R.string.discovery_manual), style = MaterialTheme.typography.labelLarge)
                ManualHubInput(input, { input = it.take(32); model.clearResult() },
                    result == ConnectionResult.INVALID_ADDRESS, connecting) { model.connect(input) }
                Button(
                    onClick = { model.connect(input) }, enabled = !connecting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget),
                    colors = ButtonDefaults.buttonColors(containerColor = LaraColors.PrimaryDark, contentColor = LaraColors.Surface),
                ) { Text(stringResource(R.string.discovery_connect)) }
            }
        }
        item {
            OutlinedButton(
                onClick = onBack,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LaraColors.PrimaryDark),
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            ) { Text(stringResource(R.string.back_action)) }
        }
    }
}

@Composable
private fun HubBeaconPill(beacon: HubBeacon) {
    val (label, dot) = when (beacon) {
        HubBeacon.SEARCHING -> R.string.discovery_beacon_searching to LaraColors.Warning
        HubBeacon.CONNECTED -> R.string.discovery_beacon_connected to LaraColors.Success
        HubBeacon.NOT_CONNECTED -> R.string.discovery_beacon_disconnected to LaraColors.TextMuted
    }
    Surface(
        color = LaraColors.Surface, shape = CircleShape, border = BorderStroke(1.dp, LaraColors.Border),
        modifier = Modifier.laraShadow(shape = CircleShape).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.padding(horizontal = LaraSpacing.S3, vertical = LaraSpacing.S1),
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = LaraColors.TextPrimary)
        }
    }
}

@Composable
private fun DiscoveryBanner(message: Int, warning: Boolean, success: Boolean) {
    val fill = when { warning -> LaraColors.WarningLight; success -> LaraColors.PrimaryLight; else -> LaraColors.InfoLight }
    val ink = when { warning -> LaraColors.WarningBannerText; success -> LaraColors.PrimaryDark; else -> LaraColors.InfoDark }
    val border = when { warning -> LaraColors.WarningBorder; success -> LaraColors.SuccessBorder; else -> LaraColors.InfoBorder }
    val icon = when { warning -> R.drawable.ic_warning_circle; success -> R.drawable.ic_check_circle; else -> R.drawable.ic_info }
    val text = stringResource(message)
    Surface(
        color = fill, shape = LaraShapes.medium, border = BorderStroke(1.dp, border),
        modifier = Modifier.laraShadow(shape = LaraShapes.medium),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(LaraSpacing.S4).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp),
                tint = if (warning) LaraColors.WarningDark else ink)
            Text(buildAnnotatedString {
                val colon = text.indexOf(':')
                if (colon >= 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(0, colon + 1)) }
                    append(text.substring(colon + 1))
                } else append(text)
            }, color = ink, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ManualHubInput(
    value: String,
    onValueChange: (String) -> Unit,
    invalid: Boolean,
    checking: Boolean,
    onCheck: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val label = stringResource(R.string.discovery_address)
    Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            placeholder = { Text(stringResource(R.string.discovery_example)) },
            isError = invalid, enabled = !checking, singleLine = true, shape = LaraShapes.medium,
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
                autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); if (!checking) onCheck() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                errorBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent,
                focusedContainerColor = LaraColors.Surface, unfocusedContainerColor = LaraColors.Surface,
                disabledContainerColor = LaraColors.SurfaceSubtle,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                .border(2.dp, when {
                    invalid -> LaraColors.Danger
                    focused -> LaraColors.Primary
                    else -> LaraColors.Border
                }, LaraShapes.medium).semantics { contentDescription = label },
        )
        if (invalid) {
            Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Icon(painterResource(R.drawable.ic_warning_circle), contentDescription = null,
                    tint = LaraColors.Danger, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.discovery_invalid), color = LaraColors.DangerDark,
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(stringResource(R.string.discovery_address_help), color = LaraColors.TextSecondary,
            style = MaterialTheme.typography.bodyLarge)
    }
}
