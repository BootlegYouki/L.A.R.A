package org.lara.app.ui.screens.teacher

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Mount in the teacher action slot; the session owner supplies a sync-backed RosterViewModel. */
@Composable
fun TeacherApprovalHost(model: RosterViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.start()
                Lifecycle.Event.ON_STOP -> model.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.start()
        onDispose { lifecycle.removeObserver(observer); model.stop() }
    }
    PendingApprovalButton(state, model::show)
    ApprovalSheet(state, model::dismiss, model::decide)
}
