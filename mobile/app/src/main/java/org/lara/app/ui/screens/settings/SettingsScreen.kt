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
import androidx.compose.ui.unit.dp
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
            .padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            if (state.error) DiscoveryBanner(R.string.settings_failed, warning = true, success = false)
            org.lara.app.ui.components.ClassroomSurface {
                if (state.loading) repeat(2) {
                    Spacer(Modifier.fillMaxWidth().height(LaraSpacing.S6).background(LaraColors.SurfaceSubtle, LaraShapes.small))
                } else Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = LaraColors.PrimaryLight) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(androidx.compose.ui.res.painterResource(if (reachable) R.drawable.ic_wifi_high else R.drawable.ic_wifi_slash),
                                null, Modifier.size(24.dp), tint = LaraColors.PrimaryDark)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(state.hubName ?: stringResource(R.string.settings_hub_name), style = MaterialTheme.typography.titleMedium)
                        Text(state.address ?: stringResource(R.string.settings_no_hub), color = LaraColors.TextSecondary)
                    }
                    if (reachable) Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_check_circle),
                        stringResource(R.string.settings_connected), Modifier.size(24.dp), tint = LaraColors.PrimaryDark)
                }
            }
            ClassroomActionRow(stringResource(R.string.settings_change_hub), icon = R.drawable.ic_gear,
                enabled = !state.busy, onClick = onChangeHub)
            ClassroomActionRow(stringResource(R.string.settings_version, version), icon = R.drawable.ic_info, enabled = false, onClick = {})
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = LaraColors.PrimaryDark,
                trackColor = LaraColors.Border)
            ClassroomActionRow(stringResource(R.string.settings_sign_out), icon = R.drawable.ic_sign_out,
                enabled = !state.busy && !state.loading, destructive = true, onClick = onSignOut)
        }
    }
    state.queued?.let { count ->
        org.lara.app.ui.components.NativeConfirmDialog(stringResource(R.string.settings_sign_out), stringResource(R.string.settings_queued_warning, count),
            stringResource(R.string.settings_sign_out), stringResource(R.string.settings_cancel),
            { if (!state.busy) onConfirm() }, { if (!state.busy) onCancel() })
    }
}
