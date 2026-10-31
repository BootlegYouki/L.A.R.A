package org.lara.app.ui.screens.enrollment

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Session owner constructs the ViewModel with the future sync refresh callback. */
@Composable
fun EnrollmentHost(model: EnrollmentViewModel, onOpenClass: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_START) model.start()
            if(event == Lifecycle.Event.ON_STOP) model.stop()
        }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.start()
        onDispose { lifecycle.removeObserver(observer); model.stop() }
    }
    EnrollmentScreen(state, model::showJoin, model::dismissJoin, model::submit, onOpenClass)
}
