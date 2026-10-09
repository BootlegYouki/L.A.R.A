package org.lara.app.ui.screens.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lara.app.R
import org.lara.app.data.remote.discovery.DiscoverySource
import org.lara.app.data.remote.discovery.DiscoveredHub
import org.lara.app.ui.components.LaraButton
import org.lara.app.ui.components.LaraPrimaryButton
import org.lara.app.ui.theme.LaraSpacing
import org.lara.app.ui.theme.LaraTheme

/**
 * The connection/discovery screen (issue MOBILE 1.2). It auto-discovers the classroom Hub over mDNS
 * and the UDP beacon the moment it is shown, lists each found Hub as a tappable card, and always
 * offers the manual IP fallback for routers with AP isolation. It never blocks: with no Hub yet it
 * shows a friendly searching/empty state, and off Wi-Fi it explains what to do instead of erroring
 * (offline-first, `rules/database-and-sync.md` §6).
 *
 * This is the stateful entry point: it owns the [DiscoveryViewModel] and forwards a chosen (or
 * manually entered) [DiscoveredHub] to [onHubSelected], which navigates on to the app shell.
 */
@Composable
fun DiscoveryScreen(
    onHubSelected: (DiscoveredHub) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoveryViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DiscoveryContent(
        state = state,
        onConnect = onHubSelected,
        onRescan = viewModel::startDiscovery,
        onOpenManual = viewModel::openManualDialog,
        modifier = modifier,
    )

    if (state.showManualDialog) {
        ManualIpDialog(
            value = state.manualInput,
            error = state.manualError,
            onValueChange = viewModel::onManualInputChange,
            onConnect = { viewModel.submitManualEntry(onHubSelected) },
            onDismiss = viewModel::dismissManualDialog,
        )
    }
}

/**
 * Stateless rendering of [DiscoveryUiState]. Split out so it previews and tests without Android
 * system services. Every status is conveyed by text (not color alone) per the accessibility rule.
 */
@Composable
private fun DiscoveryContent(
    state: DiscoveryUiState,
    onConnect: (DiscoveredHub) -> Unit,
    onRescan: () -> Unit,
    onOpenManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(LaraSpacing.S6),
    ) {
        Text(
            text = stringResource(R.string.discovery_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        when {
            state.hasHubs -> HubList(
                hubs = state.hubs,
                onConnect = onConnect,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = LaraSpacing.S5),
            )

            !state.wifiAvailable -> StatusBlock(
                title = stringResource(R.string.discovery_offline_title),
                body = stringResource(R.string.discovery_offline_body),
                showSpinner = false,
                modifier = Modifier.weight(1f),
            )

            state.isScanning -> StatusBlock(
                title = stringResource(R.string.discovery_searching),
                body = stringResource(R.string.discovery_empty_body),
                showSpinner = true,
                modifier = Modifier.weight(1f),
            )

            else -> StatusBlock(
                title = stringResource(R.string.discovery_empty_title),
                body = stringResource(R.string.discovery_empty_body),
                showSpinner = false,
                modifier = Modifier.weight(1f),
            )
        }

        // The manual IP fallback is ALWAYS present (rules/networking-and-lan.md §4.2), so a pupil
        // behind router AP isolation is never stuck even when discovery finds nothing.
        LaraPrimaryButton(
            text = stringResource(R.string.discovery_manual_action),
            onClick = onOpenManual,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = LaraSpacing.S4),
        )
        LaraButton(
            text = stringResource(R.string.discovery_rescan),
            onClick = onRescan,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = LaraSpacing.S3),
        )
    }
}

@Composable
private fun HubList(
    hubs: List<DiscoveredHub>,
    onConnect: (DiscoveredHub) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.discovery_found_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = LaraSpacing.S3),
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(hubs, key = { it.key }) { hub ->
                HubCard(hub = hub, onConnect = { onConnect(hub) })
            }
        }
    }
}

@Composable
private fun HubCard(
    hub: DiscoveredHub,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceLabel = when (hub.source) {
        DiscoverySource.MDNS -> stringResource(R.string.discovery_source_mdns)
        DiscoverySource.UDP_BEACON -> stringResource(R.string.discovery_source_udp)
        DiscoverySource.MANUAL -> stringResource(R.string.discovery_source_manual)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(LaraSpacing.S4)) {
            Text(text = hub.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.discovery_hub_address, hub.key),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = LaraSpacing.S1),
            )
            Text(
                text = sourceLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LaraPrimaryButton(
                text = stringResource(R.string.discovery_connect),
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = LaraSpacing.S3),
            )
        }
    }
}

@Composable
private fun StatusBlock(
    title: String,
    body: String,
    showSpinner: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = if (showSpinner) LaraSpacing.S5 else LaraSpacing.S1),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = LaraSpacing.S2),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DiscoverySearchingPreview() {
    LaraTheme {
        DiscoveryContent(
            state = DiscoveryUiState(isScanning = true),
            onConnect = {},
            onRescan = {},
            onOpenManual = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DiscoveryFoundPreview() {
    LaraTheme {
        DiscoveryContent(
            state = DiscoveryUiState(
                hubs = listOf(
                    DiscoveredHub(
                        name = "Grade 4 - Science",
                        ip = "192.168.1.50",
                        httpPort = 8080,
                        wsPort = 8081,
                        source = DiscoverySource.MDNS,
                    ),
                ),
                isScanning = true,
            ),
            onConnect = {},
            onRescan = {},
            onOpenManual = {},
        )
    }
}
