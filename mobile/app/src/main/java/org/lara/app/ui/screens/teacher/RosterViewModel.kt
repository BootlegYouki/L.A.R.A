package org.lara.app.ui.screens.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.lara.app.data.remote.*
import org.lara.app.domain.*
import org.lara.app.domain.enrollment.*

enum class ApprovalError { REQUEST_FAILED, SESSION_EXPIRED, FORBIDDEN }
data class ApprovalState(
    val roster: Cached<List<RosterMember>> = Cached.Loading,
    val offline: Boolean = true,
    val sheetOpen: Boolean = false,
    val busy: Set<String> = emptySet(),
    val optimistic: Map<String, String> = emptyMap(),
    val error: ApprovalError? = null,
) {
    val pending: List<RosterMember> get() = (roster as? Cached.Content)?.value.orEmpty()
        .filter { (optimistic[it.enrollment.studentId] ?: it.enrollment.status) == "PENDING" }
}

/** Existing Room rows remain authoritative; the refresh owner must preserve server enrollment identities. */
class RosterViewModel(
    private val classroomId: String,
    private val classCode: String,
    cached: EnrollmentRepository,
    private val remote: ClassManagementRepository,
    reachable: StateFlow<Boolean>,
    events: Flow<HubEvent>,
    private val refreshSavedRoster: suspend (String) -> Unit,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {
    private val mutable = MutableStateFlow(ApprovalState())
    val state = mutable.asStateFlow()
    private var foreground = false
    private var eventJob: Job? = null
    private var refreshJob: Job? = null
    private var openAfterRefresh = false
    private var refreshRequested = false
    private val canonicalClassCode = requireNotNull(ClassCode.canonicalOrNull(classCode))
    private val eventFlow = events

    init {
        viewModelScope.launch {
            cached.roster(classroomId, viewModelScope).collect { rows ->
                mutable.update { old ->
                    val values = (rows as? Cached.Content)?.value.orEmpty()
                    val remaining = old.optimistic.filter { (id, status) ->
                        values.any { it.enrollment.studentId == id && it.enrollment.status != status }
                    }
                    old.copy(roster = rows, optimistic = remaining)
                }
                if (openAfterRefresh && mutable.value.pending.isNotEmpty()) {
                    mutable.update { it.copy(sheetOpen = true) }; openAfterRefresh = false
                }
            }
        }
        viewModelScope.launch {
            reachable.collect { online ->
                mutable.update { it.copy(offline = !online) }
                if (online && foreground) refresh()
            }
        }
    }
    fun start() {
        if (foreground) return
        foreground = true
        eventJob = viewModelScope.launch {
            eventFlow.collect { event ->
                when (event) {
                    is HubEvent.JoinRequest -> if (ClassCode.canonicalOrNull(event.classCode) == canonicalClassCode) {
                        openAfterRefresh = true
                        refresh()
                    }
                    is HubEvent.HelloAck -> refresh()
                    HubEvent.SessionExpired -> failure(HubFailure(401, "UNAUTHORIZED"))
                    else -> Unit
                }
            }
        }
        refresh()
    }
    fun stop() { foreground = false; eventJob?.cancel(); eventJob = null; refreshJob?.cancel(); refreshJob = null }
    fun show() { mutable.update { it.copy(sheetOpen = true, error = null) } }
    fun dismiss() { mutable.update { it.copy(sheetOpen = false) } }

    fun refresh() {
        if (mutable.value.offline) return
        refreshRequested = true
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            while (refreshRequested && !mutable.value.offline) {
                refreshRequested = false
                try {
                    refreshSavedRoster(classroomId)
                    mutable.update { it.copy(error = null) }
                    // A refresh can complete without a new Room emission (unchanged data).
                    if (openAfterRefresh && mutable.value.pending.isNotEmpty()) {
                        mutable.update { it.copy(sheetOpen = true) }; openAfterRefresh = false
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure(e) }
            }
        }
    }
    fun decide(studentId: String, accept: Boolean) {
        val current = mutable.value
        if (current.offline || studentId in current.busy || current.pending.none { it.enrollment.studentId == studentId }) return
        val target = if (accept) "ACTIVE" else "REJECTED"
        mutable.update { it.copy(busy = it.busy + studentId, optimistic = it.optimistic + (studentId to target), error = null) }
        viewModelScope.launch {
            var accepted = false
            try {
                remote.decide(classroomId, studentId, accept)
                accepted = true
                refreshSavedRoster(classroomId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!accepted) mutable.update { it.copy(optimistic = it.optimistic - studentId) }
                failure(e)
            } finally {
                mutable.update { it.copy(busy = it.busy - studentId) }
            }
        }
    }
    private fun failure(e: Exception) {
        val error = when ((e as? HubFailure)?.status) {
            401 -> ApprovalError.SESSION_EXPIRED
            403 -> ApprovalError.FORBIDDEN
            else -> ApprovalError.REQUEST_FAILED
        }
        mutable.update { it.copy(error = error) }
        if (error == ApprovalError.SESSION_EXPIRED) onSessionExpired()
    }
}
