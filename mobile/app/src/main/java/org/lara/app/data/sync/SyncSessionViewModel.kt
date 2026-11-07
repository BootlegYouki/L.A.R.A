package org.lara.app.data.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.lara.app.AppContainer
import org.lara.app.data.remote.*
import org.lara.app.domain.enrollment.HubEvent

/** One socket per signed-in foreground session, shared with teacher approval controls. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSessionViewModel(private val container: AppContainer, private val userId: String) : ViewModel(), org.lara.app.domain.enrollment.EnrollmentSessionSource {
    private val realtime = RealtimeClient()
    private val online = MutableStateFlow(false)
    private val networkGeneration = MutableStateFlow(0L)
    override val reachable: StateFlow<Boolean> = online
    override val events: SharedFlow<HubEvent> = combine(online, networkGeneration) { connected, generation -> connected to generation }
        .flatMapLatest { (connected, _) ->
        if (!connected) emptyFlow() else flow {
            emitAll(realtime.observe(container.enrollmentApi.connection(userId)))
        }.catch { e ->
            if (e is CancellationException) throw e
            if (e is HubFailure && e.status == 401) emit(HubEvent.SessionExpired)
        }
    }.transform { event ->
        emit(event)
        if (event is HubEvent.HelloAck || event is HubEvent.Announcement) {
            if (event is HubEvent.HelloAck) container.syncScheduler.enqueueReconnect()
            else container.syncScheduler.enqueueNow()
            try { container.sync.pull(userId) }
            catch (e: CancellationException) { throw e }
            catch (e: HubFailure) { if (e.status == 401) emit(HubEvent.SessionExpired) }
            catch (_: Exception) { /* WorkManager retains the retry; saved screens stay readable. */ }
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(0), replay = 0)
    fun setReachable(value: Boolean) { online.value = value }
    fun onNetworkChanged() { networkGeneration.value += 1 }
    override fun onCleared() { realtime.close() }
}
