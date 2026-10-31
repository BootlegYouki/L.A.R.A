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
class SyncSessionViewModel(private val container: AppContainer, private val userId: String) : ViewModel() {
    private val realtime = RealtimeClient()
    private val online = MutableStateFlow(false)
    val reachable: StateFlow<Boolean> = online
    val events: SharedFlow<HubEvent> = online.flatMapLatest { connected ->
        if (!connected) emptyFlow() else flow {
            emitAll(realtime.observe(container.enrollmentApi.connection(userId)))
        }.catch { e ->
            if (e is CancellationException) throw e
            if (e is HubFailure && e.status == 401) emit(HubEvent.SessionExpired)
        }
    }.transform { event ->
        emit(event)
        if (event is HubEvent.HelloAck || event is HubEvent.Announcement) {
            container.syncScheduler.enqueueNow()
            try { container.sync.pull(userId) }
            catch (e: CancellationException) { throw e }
            catch (e: HubFailure) { if (e.status == 401) emit(HubEvent.SessionExpired) }
            catch (_: Exception) { /* WorkManager retains the retry; saved screens stay readable. */ }
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(0), replay = 0)
    fun setReachable(value: Boolean) { online.value = value }
    override fun onCleared() { realtime.close() }
}
