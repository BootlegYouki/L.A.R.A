package org.lara.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.lara.app.R
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.components.ClassroomActionRow
import org.lara.app.ui.theme.*

@Composable
fun SettingsScreen(state: SettingsState, reachable: Boolean, version: String,
    onSignOut: () -> Unit, onConfirm: () -> Unit,
    onCancel: () -> Unit, onChangeHub: () -> Unit, onBack: () -> Unit) {
    Scaffold(containerColor = LaraColors.Canvas, topBar = {
        org.lara.app.ui.components.ClassroomTopBar(stringResource(R.string.settings_title), back = { if (!state.busy) onBack() })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            .padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S6)) {
            if (state.error) DiscoveryBanner(R.string.settings_failed, warning = true, success = false)
            else if (!reachable) DiscoveryBanner(R.string.settings_offline, warning = true, success = false)
            SettingsSection(R.string.settings_hub) {
                if (state.loading) repeat(2) {
                    Spacer(Modifier.fillMaxWidth().height(LaraSpacing.S6).background(LaraColors.SurfaceSubtle, LaraShapes.small))
                } else {
                    Text(state.hubName ?: stringResource(R.string.settings_hub_name), style = MaterialTheme.typography.titleMedium)
                    Text(state.address ?: stringResource(R.string.settings_no_hub), color = LaraColors.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
                        Icon(androidx.compose.ui.res.painterResource(if (reachable) R.drawable.ic_check_circle else R.drawable.ic_info),
                            null, Modifier.size(LaraSpacing.S5), tint = LaraColors.PrimaryDark)
                        Text(stringResource(if (reachable) R.string.settings_connected else R.string.settings_disconnected),
                            style = MaterialTheme.typography.bodyLarge, color = LaraColors.TextSecondary)
                    }
                }
                ClassroomActionRow(stringResource(R.string.settings_change_hub),
                    stringResource(R.string.settings_change_hub_help), R.drawable.ic_gear,
                    enabled = !state.busy, onClick = onChangeHub)
            }
            SettingsSection(R.string.settings_about) {
                Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_about_text), color = LaraColors.TextSecondary)
            }
            HorizontalDivider(color = LaraColors.Border)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = LaraColors.PrimaryDark,
                trackColor = LaraColors.Border)
            ClassroomActionRow(stringResource(R.string.settings_sign_out), icon = R.drawable.ic_sign_out,
                enabled = !state.busy && !state.loading, destructive = true, onClick = onSignOut)
        }
    }
    state.queued?.let { count ->
        AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.settings_sign_out)) },
            text = { Text(stringResource(R.string.settings_queued_warning, count), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = onConfirm, enabled = !state.busy,
                modifier = Modifier.heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.settings_sign_out)) } },
            dismissButton = { TextButton(onClick = onCancel, enabled = !state.busy,
                modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.settings_cancel)) } },
            shape = LaraShapes.extraLarge)
    }
}

@Composable
private fun SettingsSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = LaraColors.PrimaryDark)
        content()
    }
}
