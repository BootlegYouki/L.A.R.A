package org.lara.app.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.ui.theme.*

val LocalOpenSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun SettingsAction() {
    val open = LocalOpenSettings.current ?: return
    TextButton(onClick = open, modifier = Modifier.heightIn(min = MinTouchTarget),
        colors = ButtonDefaults.textButtonColors(contentColor = LaraColors.Surface)) {
        Text(stringResource(R.string.settings_title))
    }
}

@Composable
fun SettingsHost(container: AppContainer, userId: String, reachable: Boolean,
    onSignedOut: () -> Unit, onChangeHub: () -> Unit, onBack: () -> Unit) {
    val signedOut by rememberUpdatedState(onSignedOut)
    val changeHub by rememberUpdatedState(onChangeHub)
    val model: SettingsViewModel = viewModel(key = "settings-$userId", factory = viewModelFactory {
        initializer { SettingsViewModel(userId, container.settings, container.endpoints,
            onChangeHub = { changeHub() }) { signedOut() } }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    LaunchedEffect(model) { model.refresh() }
    BackHandler { if (!state.busy) onBack() }
    SettingsScreen(state, reachable, version, { model.signOut() },
        { model.signOut(true) }, model::cancelSignOut, { model.signOut(changeHub = true) }, onBack)
}
