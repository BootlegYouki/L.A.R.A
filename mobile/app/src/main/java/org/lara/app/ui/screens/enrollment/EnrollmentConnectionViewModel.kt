package org.lara.app.ui.screens.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.lara.app.data.remote.*
import org.lara.app.domain.enrollment.*

/** MOBILE 2.5 may supply its shared session instead; cached-only builds still receive live status. */
@OptIn(ExperimentalCoroutinesApi::class)
class EnrollmentConnectionViewModel(private val api: EnrollmentApi, private val userId: String) : ViewModel(), EnrollmentSessionSource {
    private val realtime = RealtimeClient()
    private val online = MutableStateFlow(false)
    override val reachable: StateFlow<Boolean> = online
    override val events = online.flatMapLatest { connected ->
        if (!connected) emptyFlow() else flow {
            emitAll(realtime.observe(api.connection(userId)))
        }.catch { e ->
            if (e is CancellationException) throw e
            if (e is HubFailure && e.status == 401) emit(HubEvent.SessionExpired)
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(0), replay = 0)
    fun setReachable(value: Boolean) { online.value = value }
    override fun onCleared() { realtime.close() }
}
