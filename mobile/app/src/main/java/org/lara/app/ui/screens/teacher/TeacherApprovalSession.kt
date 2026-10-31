package org.lara.app.ui.screens.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.lara.app.AppContainer
import org.lara.app.data.remote.*
import org.lara.app.domain.enrollment.HubEvent
import org.lara.app.domain.model.Classroom

internal fun Classroom.managedBy(teacherId: String): Boolean = archivedAt == null &&
    (this.teacherId == teacherId || runCatching {
        Json.decodeFromString<List<String>>(coTeacherIdsJson ?: "[]").contains(teacherId)
    }.getOrDefault(false))

/** One foreground socket shared by all saved class approval controls in this signed-in session. */
@OptIn(ExperimentalCoroutinesApi::class)
class TeacherApprovalSession(val container: AppContainer, private val userId: String) : ViewModel() {
    private val realtime = RealtimeClient()
    private val online = MutableStateFlow(false)
    val reachable: StateFlow<Boolean> = online
    val classrooms = container.classrooms.classrooms(viewModelScope)
    val remote = container.classManagement(userId)
    val refresher = container.savedRosterRefresher(remote)
    val events: SharedFlow<HubEvent> = online.flatMapLatest { connected ->
        if (!connected) emptyFlow() else flow {
            val connection = container.enrollmentApi.connection(userId)
            emitAll(realtime.observe(connection))
        }.catch { e ->
            if (e is CancellationException) throw e
            if (e is HubFailure && e.status == 401) emit(HubEvent.SessionExpired)
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(0), replay = 0)
    fun setReachable(value: Boolean) { online.value = value }
    override fun onCleared() { realtime.close() }
}
