package org.lara.app.ui.screens.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lara.app.data.remote.*
import org.lara.app.domain.EnrollmentRepository
import org.lara.app.domain.enrollment.*

/** The session owns the socket; Room owns saved memberships and request notices. */
class EnrollmentViewModel(
    private val userId: String,
    repository: EnrollmentRepository,
    private val api: EnrollmentApi,
    private val events: Flow<HubEvent>,
    reachable: StateFlow<Boolean>,
    private val notices: EnrollmentNoticeRepository,
    private val onMembershipHint: suspend (EnrollmentRefreshHint) -> Unit,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {
    private val mutable = MutableStateFlow(EnrollmentScreenState())
    val state = mutable.asStateFlow()
    private var foreground = false
    private var eventJob: Job? = null
    private val refreshLock = Mutex()

    init {
        viewModelScope.launch {
            repository.learnerClasses(userId, viewModelScope).collect { rows -> mutable.update { it.copy(classes = rows) } }
        }
        viewModelScope.launch {
            notices.notices(userId).catch { e ->
                if (e is CancellationException) throw e
                failure(JoinClassError.REFRESH_FAILED)
            }.collect { rows -> mutable.update { it.copy(requests = rows, noticesLoaded = true) } }
        }
        viewModelScope.launch {
            reachable.collect { online ->
                mutable.update { it.copy(offline = !online) }
                if (online && foreground) viewModelScope.launch { refresh(EnrollmentRefreshHint.Foreground) }
            }
        }
    }
    fun start() {
        if (foreground) return
        foreground = true
        eventJob = viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    is HubEvent.HelloAck -> refresh(EnrollmentRefreshHint.Connected(event.hubId, event.syncEpoch))
                    is HubEvent.JoinApproval -> if (event.studentId == userId) {
                        // A restrictive event closes access promptly. ACTIVE needs an authoritative refresh.
                        if (event.status != EnrollmentStatus.ACTIVE) {
                            try { notices.save(userId, EnrollmentNotice(event.classroomId, null, event.status)) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { failure(JoinClassError.REFRESH_FAILED) }
                        }
                        refresh(EnrollmentRefreshHint.MembershipChanged(event.classroomId))
                    }
                    HubEvent.SessionExpired -> expire()
                    else -> Unit
                }
            }
        }
        if (!mutable.value.offline) viewModelScope.launch { refresh(EnrollmentRefreshHint.Foreground) }
    }
    fun stop() { foreground = false; eventJob?.cancel(); eventJob = null }
    fun showJoin() { mutable.update { it.copy(showJoinDialog = true, error = null) } }
    fun onInputChanged() { if (!mutable.value.busy) mutable.update { it.copy(error = null) } }
    fun dismissJoin() { if (!mutable.value.busy) mutable.update { it.copy(showJoinDialog = false, error = null) } }
    fun submit(input: String) {
        if (mutable.value.busy) return
        if (mutable.value.offline) { failure(JoinClassError.HUB_UNREACHABLE); return }
        val code = ClassCode.canonicalOrNull(input)
        if (code == null) { failure(JoinClassError.INVALID_CODE); return }
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val receipt = api.join(userId, code)
                notices.save(userId, EnrollmentNotice(receipt.classroomId, null, receipt.status))
                mutable.update { it.copy(showJoinDialog = false) }
                refresh(EnrollmentRefreshHint.MembershipChanged(receipt.classroomId))
            } catch (e: CancellationException) { throw e }
            catch (e: HubFailure) {
                when {
                    e.status == 401 -> expire()
                    e.status == 404 && e.code == "CLASS_CODE_INVALID" -> failure(JoinClassError.INVALID_CODE)
                    else -> failure(JoinClassError.REQUEST_FAILED)
                }
            } catch (_: java.io.IOException) { failure(JoinClassError.HUB_UNREACHABLE) }
            catch (_: Exception) { failure(JoinClassError.REQUEST_FAILED) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun refresh(hint: EnrollmentRefreshHint) = refreshLock.withLock {
        if (mutable.value.offline) return@withLock
        try {
            api.classrooms(userId).forEach { row ->
                val status = row.enrollmentStatus?.let { runCatching { EnrollmentStatus.valueOf(it) }.getOrNull() }
                if (status != null) notices.save(userId, EnrollmentNotice(row.id, row.name, status))
            }
            onMembershipHint(hint)
            mutable.update { it.copy(error = null) }
        } catch (e: CancellationException) { throw e }
        catch (e: HubFailure) { if (e.status == 401) expire() else failure(JoinClassError.REFRESH_FAILED) }
        catch (_: Exception) { failure(JoinClassError.REFRESH_FAILED) }
    }
    private fun failure(error: JoinClassError) { mutable.update { it.copy(error = error) } }
    private fun expire() { mutable.update { it.copy(error = JoinClassError.SESSION_EXPIRED) }; onSessionExpired() }
}
