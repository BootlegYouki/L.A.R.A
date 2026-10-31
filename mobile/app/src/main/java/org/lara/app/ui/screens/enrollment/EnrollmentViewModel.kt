package org.lara.app.ui.screens.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.lara.app.data.remote.*
import org.lara.app.domain.EnrollmentRepository
import org.lara.app.domain.enrollment.*

/** The owner must supply the sync-owned refresh boundary; this class never invents enrollment rows. */
class EnrollmentViewModel(
    private val userId: String,
    repository: EnrollmentRepository,
    private val api: EnrollmentApi,
    private val realtime: RealtimeClient,
    reachable: StateFlow<Boolean>,
    private val onMembershipHint: suspend (EnrollmentRefreshHint) -> Unit,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {
    private val mutable = MutableStateFlow(EnrollmentScreenState())
    val state = mutable.asStateFlow()
    private var foreground = false
    private var socketJob: Job? = null

    init {
        viewModelScope.launch {
            repository.learnerClasses(userId, viewModelScope).collect { rows ->
                mutable.update { it.copy(classes = rows) }
            }
        }
        viewModelScope.launch {
            reachable.collect { online ->
                mutable.update { it.copy(offline = !online) }
                if (online && foreground) connect()
                if (!online) { socketJob?.cancel(); socketJob = null }
            }
        }
    }

    fun start() { foreground = true; connect() }
    fun stop() { foreground = false; socketJob?.cancel(); socketJob = null }
    fun showJoin() { mutable.update { it.copy(showJoinDialog = true, error = null) } }
    fun dismissJoin() { if (!mutable.value.busy) mutable.update { it.copy(showJoinDialog = false, error = null) } }

    private fun connect() {
        if (mutable.value.offline || socketJob?.isActive == true) return
        socketJob = viewModelScope.launch {
            try {
                val connection = api.connection(userId)
                realtime.events(connection, CoroutineScope(currentCoroutineContext()))
                    .onEach { if (it == HubEvent.SessionExpired) expire() }
                    .takeWhile { it != HubEvent.SessionExpired }
                    .collect { event ->
                        when (event) {
                            is HubEvent.HelloAck -> refresh(EnrollmentRefreshHint.Connected(event.hubId, event.syncEpoch))
                            is HubEvent.JoinApproval -> if (event.studentId == userId) {
                                notice(JoinRequestNotice(event.classroomId, null, event.status))
                                refresh(EnrollmentRefreshHint.MembershipChanged(event.classroomId))
                            }
                            else -> Unit
                        }
                    }
            } catch (e: CancellationException) { throw e }
            catch (e: HubFailure) { if (e.status == 401) expire() else failure(JoinClassError.REQUEST_FAILED) }
            catch (_: Exception) { failure(JoinClassError.HUB_UNREACHABLE) }
            finally { currentCoroutineContext().cancelChildren() }
        }
    }

    fun submit(input: String) {
        if (mutable.value.busy) return
        if (mutable.value.offline) { failure(JoinClassError.HUB_UNREACHABLE); return }
        val code = ClassCode.canonicalOrNull(input)
        if (code == null) { failure(JoinClassError.INVALID_CODE); return }
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val receipt = api.join(userId, code)
                notice(JoinRequestNotice(receipt.classroomId, null, receipt.status))
                mutable.update { it.copy(showJoinDialog = false) }
                refresh(EnrollmentRefreshHint.MembershipChanged(receipt.classroomId))
            } catch (e: CancellationException) { throw e }
            catch (e: HubFailure) {
                when {
                    e.status == 401 -> expire()
                    e.status == 404 && e.code == "CLASS_CODE_INVALID" -> failure(JoinClassError.INVALID_CODE)
                    else -> failure(JoinClassError.REQUEST_FAILED)
                }
            }
            catch (_: java.io.IOException) { failure(JoinClassError.HUB_UNREACHABLE) }
            catch (_: Exception) { failure(JoinClassError.REQUEST_FAILED) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private fun notice(value: JoinRequestNotice) {
        mutable.update { old ->
            val previous = old.requests.firstOrNull { it.classroomId == value.classroomId }
            old.copy(requests = old.requests.filterNot { it.classroomId == value.classroomId } +
                value.copy(title = value.title ?: previous?.title))
        }
    }

    private suspend fun refresh(hint: EnrollmentRefreshHint) {
        try {
            onMembershipHint(hint)
            api.classrooms(userId).forEach { row ->
                val status = row.enrollmentStatus?.let { runCatching { EnrollmentStatus.valueOf(it) }.getOrNull() }
                if (status != null) notice(JoinRequestNotice(row.id, row.name, status))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: HubFailure) { if(e.status == 401) expire() else failure(JoinClassError.REFRESH_FAILED) }
        catch (_: Exception) { failure(JoinClassError.REFRESH_FAILED) }
    }

    private fun failure(error: JoinClassError) { mutable.update { it.copy(error = error) } }
    private fun expire() {
        mutable.update { it.copy(error = JoinClassError.SESSION_EXPIRED) }
        onSessionExpired()
    }
}
