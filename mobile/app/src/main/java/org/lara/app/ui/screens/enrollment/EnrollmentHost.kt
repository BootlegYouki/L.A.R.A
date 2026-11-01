package org.lara.app.ui.screens.enrollment

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun EnrollmentHost(model: EnrollmentViewModel, onOpenClass: (String) -> Unit) {
    EnrollmentSession(model)
    val state by model.state.collectAsStateWithLifecycle()
    EnrollmentScreen(state, model::showJoin, model::dismissJoin, model::submit, onOpenClass, model::onInputChanged)
}

/** Keep approval/revocation handling alive while the learner is inside an opened class as well. */
@Composable
fun EnrollmentSession(model: EnrollmentViewModel) {
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
}
