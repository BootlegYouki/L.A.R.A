package org.lara.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.lara.app.R
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.theme.*

@Composable
fun SettingsScreen(state: SettingsState, language: String, reachable: Boolean, version: String,
    onLanguage: (String) -> Unit, onSignOut: () -> Unit, onConfirm: () -> Unit,
    onCancel: () -> Unit, onChangeHub: () -> Unit, onBack: () -> Unit) {
    Scaffold(containerColor = LaraColors.Canvas, topBar = {
        Surface(color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = LaraSpacing.S4),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.heightIn(min = MinTouchTarget),
                    colors = ButtonDefaults.textButtonColors(contentColor = LaraColors.Surface)) {
                    Text(stringResource(R.string.settings_back))
                }
            }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            .padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            if (state.error) DiscoveryBanner(R.string.settings_failed, warning = true, success = false)
            else if (!reachable) DiscoveryBanner(R.string.settings_offline, warning = true, success = false)
            SettingsCard(R.string.settings_language) {
                Column(Modifier.selectableGroup()) {
                    listOf("en" to R.string.settings_english, "tl" to R.string.settings_filipino).forEach { (code, label) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                            .selectable(language == code, enabled = !state.busy, role = Role.RadioButton, onClick = { onLanguage(code) })
                            .padding(horizontal = LaraSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = language == code, onClick = null)
                            Text(stringResource(label), Modifier.padding(start = LaraSpacing.S3))
                        }
                    }
                }
            }
            SettingsCard(R.string.settings_hub) {
                if (state.loading) repeat(2) {
                    Spacer(Modifier.fillMaxWidth().height(LaraSpacing.S6).background(LaraColors.SurfaceSubtle, LaraShapes.small))
                } else {
                    Text(state.hubName ?: stringResource(R.string.settings_hub_name), style = MaterialTheme.typography.titleMedium)
                    Text(state.address ?: stringResource(R.string.settings_no_hub), color = LaraColors.TextSecondary)
                    Text(stringResource(if (reachable) R.string.settings_connected else R.string.settings_disconnected),
                        color = LaraColors.TextSecondary)
                }
                OutlinedButton(onClick = onChangeHub, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget), shape = LaraShapes.medium) {
                    Text(stringResource(R.string.settings_change_hub))
                }
                Text(stringResource(R.string.settings_change_hub_help), style = MaterialTheme.typography.bodyMedium,
                    color = LaraColors.TextSecondary)
            }
            SettingsCard(R.string.settings_about) {
                Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_about_text), color = LaraColors.TextSecondary)
            }
            Button(onClick = onSignOut, enabled = !state.busy && !state.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget), shape = LaraShapes.medium) {
                if (state.busy) CircularProgressIndicator(Modifier.size(LaraSpacing.S6), color = LaraColors.Surface)
                else Text(stringResource(R.string.settings_sign_out))
            }
        }
    }
    state.queued?.let { count ->
        AlertDialog(onDismissRequest = onCancel, title = { PopupLanguage { Text(stringResource(R.string.settings_sign_out)) } },
            text = { PopupLanguage { Text(stringResource(R.string.settings_queued_warning, count), Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { PopupLanguage { TextButton(onClick = onConfirm, enabled = !state.busy,
                modifier = Modifier.heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.settings_sign_out)) } } },
            dismissButton = { PopupLanguage { TextButton(onClick = onCancel, enabled = !state.busy,
                modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.settings_cancel)) } } },
            shape = LaraShapes.extraLarge)
    }
}

@Composable
private fun SettingsCard(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().laraShadow(), color = LaraColors.Surface, shape = LaraShapes.large,
        border = BorderStroke(1.dp, LaraColors.Border)) {
        Column(Modifier.padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = LaraColors.PrimaryDark)
            content()
        }
    }
}
