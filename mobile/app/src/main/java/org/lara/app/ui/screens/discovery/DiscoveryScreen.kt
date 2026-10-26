package org.lara.app.ui.screens.discovery

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
        result == ConnectionResult.INVALID_ADDRESS -> R.string.discovery_invalid
        result == ConnectionResult.UNREACHABLE -> R.string.discovery_unreachable
        result == ConnectionResult.RESPONDING -> R.string.discovery_responding
        !state.wifiAvailable -> R.string.discovery_offline
        state.warning -> R.string.discovery_warning
        state.scanning -> R.string.discovery_scanning
        hubs.isEmpty() -> R.string.discovery_empty
        else -> R.string.discovery_choose
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(LaraSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4),
    ) {
        item { Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.headlineMedium) }
        item {
            Surface(
                color = if (result == ConnectionResult.RESPONDING) LaraColors.PrimaryLight else LaraColors.SurfaceSubtle,
                shape = LaraShapes.large,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(LaraSpacing.S4).semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3),
                ) {
                    Icon(painterResource(R.drawable.ic_info), contentDescription = null,
                        tint = if (result == ConnectionResult.RESPONDING) LaraColors.PrimaryDark else LaraColors.TextSecondary)
                    Text(stringResource(message))
                }
            }
        }
        item {
            Text(stringResource(R.string.discovery_manual), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(32); model.clearResult() },
                label = { Text(stringResource(R.string.discovery_address)) },
                placeholder = { Text(stringResource(R.string.discovery_example)) },
                supportingText = { Text(stringResource(R.string.discovery_address_help)) },
                isError = result == ConnectionResult.INVALID_ADDRESS,
                enabled = !connecting,
                singleLine = true,
                shape = LaraShapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            )
            Button(
                onClick = { model.connect(input) }, enabled = !connecting,
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget),
                colors = ButtonDefaults.buttonColors(containerColor = LaraColors.PrimaryDark, contentColor = LaraColors.Surface),
            ) { Text(stringResource(R.string.discovery_connect)) }
        }
        item {
            OutlinedButton(onClick = model::retry,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LaraColors.PrimaryDark),
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.discovery_retry))
            }
            Text(stringResource(R.string.discovery_results), style = MaterialTheme.typography.titleMedium)
        }
        if (state.scanning && hubs.isEmpty()) {
            items(2) {
                Surface(color = LaraColors.Border, shape = LaraShapes.large, modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                    Text(stringResource(R.string.discovery_scanning), modifier = Modifier.padding(LaraSpacing.S4))
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
                    Text(hub.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.discovery_hub_address, hub.ip, hub.httpPort))
                }
            }
        }
        item {
            Text(stringResource(R.string.discovery_scope), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onBack,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LaraColors.PrimaryDark),
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.back_action))
            }
        }
    }
}
