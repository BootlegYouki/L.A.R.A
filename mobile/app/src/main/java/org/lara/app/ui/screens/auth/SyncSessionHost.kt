package org.lara.app.ui.screens.auth

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.lara.app.AppContainer
import org.lara.app.data.sync.SyncSessionViewModel
import org.lara.app.domain.enrollment.HubEvent

@Composable
fun rememberSyncSession(container: AppContainer, userId: String, reachable: Boolean, onSessionExpired: () -> Unit): SyncSessionViewModel {
    val model: SyncSessionViewModel = viewModel(key = "sync-session-$userId", factory = viewModelFactory {
        initializer { SyncSessionViewModel(container, userId) }
    })
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val expired by rememberUpdatedState(onSessionExpired)
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) foreground = true
            if (event == Lifecycle.Event.ON_STOP) foreground = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.setReachable(false) }
    }
    LaunchedEffect(model, reachable, foreground) { model.setReachable(reachable && foreground) }
    LaunchedEffect(model) { container.syncScheduler.enqueueNow() }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.events.collect { if (it == HubEvent.SessionExpired) expired() }
        }
    }
    return model
}
