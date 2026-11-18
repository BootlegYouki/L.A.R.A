package org.lara.app.ui.screens.discovery

import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.components.ClassroomTopBar
import org.lara.app.ui.components.ClassroomSurface
import org.lara.app.ui.components.classFormInputScroll
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.imePadding

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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.lara.app.ui.theme.laraShadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.statusBarsPadding
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(model: DiscoveryViewModel, onDismiss: () -> Unit) {
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
    var manual by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val message = when {
        connecting -> R.string.discovery_checking
        result == ConnectionResult.UNREACHABLE -> R.string.discovery_unreachable
        result == ConnectionResult.RESPONDING -> R.string.discovery_responding
        state.session > 0 && !state.wifiAvailable -> R.string.discovery_offline
        state.warning -> R.string.discovery_warning
        state.scanning -> R.string.discovery_scanning
        hubs.isEmpty() -> R.string.discovery_empty
        else -> R.string.discovery_choose
    }
    val warning = !connecting && result != ConnectionResult.RESPONDING &&
        (result == ConnectionResult.UNREACHABLE || (state.session > 0 && !state.wifiAvailable) || state.warning)
    val success = !connecting && result == ConnectionResult.RESPONDING
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LaraColors.Canvas,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = LaraSpacing.S4).padding(bottom = LaraSpacing.S6),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HubHeader(state.scanning && !connecting, hubBeacon(state, result, connecting), noHubs = hubs.isEmpty() && !state.scanning)
            if (warning || success || connecting || hubs.isNotEmpty()) DiscoveryBanner(message, warning, success)
            if (hubs.isNotEmpty()) Text(stringResource(R.string.discovery_results), style = MaterialTheme.typography.titleMedium)
            hubs.forEach { hub ->
                Surface(
                    onClick = { model.connect(hub) }, enabled = !connecting,
                    shape = LaraShapes.large, color = LaraColors.Surface,
                    border = BorderStroke(1.dp, LaraColors.Border),
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget).laraShadow(),
                ) {
                    Row(Modifier.padding(LaraSpacing.S4), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                        Icon(painterResource(R.drawable.ic_broadcast), contentDescription = null,
                            tint = LaraColors.PrimaryDark, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(hub.name, style = MaterialTheme.typography.titleMedium, color = LaraColors.TextPrimary)
                            Text(stringResource(R.string.discovery_hub_address, hub.ip, hub.httpPort),
                                style = MaterialTheme.typography.bodyLarge, color = LaraColors.TextSecondary)
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = model::retry,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LaraColors.PrimaryDark),
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            ) { Text(stringResource(R.string.discovery_retry)) }
            if (!manual) TextButton(onClick = { manual = true }, modifier = Modifier.heightIn(min = MinTouchTarget),
                colors = ButtonDefaults.textButtonColors(contentColor = LaraColors.PrimaryDark)) {
                Text(stringResource(R.string.discovery_manual_link))
            } else {
                ManualHubInput(input, { input = it.take(32); model.clearResult() },
                    result == ConnectionResult.INVALID_ADDRESS, connecting) { model.connect(input) }
                Button(
                    onClick = { keyboard?.hide(); model.connect(input) }, enabled = !connecting && input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget),
                    colors = ButtonDefaults.buttonColors(containerColor = LaraColors.PrimaryDark, contentColor = LaraColors.Surface),
                ) { Text(stringResource(R.string.discovery_connect)) }
            }
        }
    }
}

@Composable
private fun HubBeaconPill(beacon: HubBeacon, noHubs: Boolean) {
    val (label, dot) = when (beacon) {
        HubBeacon.SEARCHING -> R.string.discovery_beacon_searching to LaraColors.Warning
        HubBeacon.CONNECTED -> R.string.discovery_beacon_connected to LaraColors.Success
        HubBeacon.NOT_CONNECTED -> (if (noHubs) R.string.discovery_beacon_none else R.string.discovery_beacon_disconnected) to LaraColors.TextMuted
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
                .classFormInputScroll().border(2.dp, when {
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
    }
}

@Composable
private fun HubHeader(scanning: Boolean, beacon: HubBeacon, noHubs: Boolean) {
    val context = LocalContext.current
    val still = remember { android.provider.Settings.Global.getFloat(context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val pulse by rememberInfiniteTransition(label = "scan").animateFloat(1f, 1.18f,
        infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse")
    val scale = if (scanning && !still) pulse else 1f
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        Box(Modifier.padding(LaraSpacing.S2).size(72.dp).scale(scale)
            .background(LaraColors.PrimaryLight, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_broadcast), contentDescription = null,
                tint = LaraColors.PrimaryDark, modifier = Modifier.size(36.dp))
        }
        Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center)
        HubBeaconPill(beacon, noHubs)
    }
}
