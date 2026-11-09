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

/** The shared sync session owns the socket; this model retains independent REST class/roster loading. */
@OptIn(ExperimentalCoroutinesApi::class)
class TeacherApprovalSession(val container: AppContainer, private val userId: String,
    private val connection: org.lara.app.domain.enrollment.EnrollmentSessionSource) : ViewModel() {
    private val online = MutableStateFlow(false)
    val reachable: StateFlow<Boolean> = online
    val classrooms = container.classrooms.classrooms(viewModelScope)
    val remote = container.classManagement(userId)
    val refresher = container.savedRosterRefresher(remote, userId)
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    val pendingClassCodes = pending.asStateFlow()
    private val classFailure = MutableStateFlow<ApprovalError?>(null)
    val error = classFailure.asStateFlow()
    private val failures = MutableSharedFlow<HubEvent>(extraBufferCapacity = 1)
    private var classJob: Job? = null
    private var refreshRequested = false
    private val classLoading = MutableStateFlow(false)
    val refreshingClasses = classLoading.asStateFlow()
    private fun refreshClasses() {
        refreshRequested = true
        if (classJob?.isActive == true || !online.value) return
        classJob = viewModelScope.launch {
            classLoading.value = true
            try {
            while (refreshRequested && online.value) {
                refreshRequested = false
                try { container.sync.refreshRoster(userId) { refresher.refreshClasses() }; classFailure.value = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    classFailure.value = if (e is HubFailure && e.status == 401) ApprovalError.SESSION_EXPIRED else ApprovalError.REQUEST_FAILED
                    if (classFailure.value == ApprovalError.SESSION_EXPIRED) failures.emit(HubEvent.SessionExpired)
                }
            }
            } finally { classLoading.value = false }
        }
    }
    fun consumeJoinRequest(code: String) { pending.value = pending.value - code }

    val events: SharedFlow<HubEvent> = merge(failures, connection.events.onEach { event ->
        if (event is HubEvent.JoinRequest) {
            org.lara.app.domain.enrollment.ClassCode.canonicalOrNull(event.classCode)?.let { pending.value = pending.value + it }
            refreshClasses()
        } else if (event is HubEvent.HelloAck) refreshClasses()
    }).shareIn(viewModelScope, SharingStarted.WhileSubscribed(0), replay = 0)
    fun setReachable(value: Boolean) {
        online.value = value
        if (value) refreshClasses() else { classJob?.cancel(); classJob = null }
    }
}
