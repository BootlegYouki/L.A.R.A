package org.lara.app.ui.screens.teacher.classes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.*
import org.lara.app.domain.model.Classroom
import org.lara.app.data.remote.HubFailure

fun Classroom.teacherIds(): List<String> = listOf(teacherId) +
    runCatching { Json.decodeFromString<List<String>>(coTeacherIdsJson ?: "[]") }.getOrDefault(emptyList())
fun Classroom.canManage(userId: String, ownerOnly: Boolean): Boolean = archivedAt == null &&
    if (ownerOnly) teacherId == userId else userId in teacherIds()

data class ClassManagementState(val busy: Boolean = false, val error: Int? = null,
    val selected: String? = null, val roster: Cached<List<RosterMember>> = Cached.Loading)

class ClassManagementViewModel(private val container: AppContainer, private val userId: String,
    private val reachable: StateFlow<Boolean>, private val expired: () -> Unit) : ViewModel() {
    val classrooms = container.managedClasses.classrooms(viewModelScope)
    val teacherNames = container.teacherNames.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutable = MutableStateFlow(ClassManagementState())
    val state = mutable.asStateFlow()
    private val api = container.teacherClasses(userId)
    private val refresher = container.savedRosterRefresher(container.classManagement(userId), userId)
    private var rosterJob: Job? = null
    init { viewModelScope.launch { reachable.collect { if (it) refresh() } } }
    fun select(id: String) {
        if (mutable.value.busy) return
        mutable.update { it.copy(selected = id, roster = Cached.Loading, error = null) }
        rosterJob?.cancel()
        rosterJob = viewModelScope.launch { container.enrollments.roster(id, viewModelScope).collect { rows ->
            mutable.update { it.copy(roster = rows) }
        } }
        refresh()
    }
    private fun selected(): Classroom? = (classrooms.value as? Cached.Content)?.value?.find { it.id == state.value.selected }
    fun refresh() = run(false, false) { }
    fun create(name: String, section: String) = run(false, false) {
        api.create(name, section).also { row ->
            rosterJob?.cancel(); rosterJob = null
            mutable.update { it.copy(selected = row.id, roster = Cached.Loading) }
        }
    }
    fun rename(name: String, section: String) = run(true, true) { selected()?.let { api.rename(it.id, name, section) } }
    fun archive() = run(true, true) { selected()?.let { api.archive(it.id) } }
    fun addTeacher(id: String) = run(true, true) { selected()?.let { api.addTeacher(it.id, id) } }
    fun removeTeacher(id: String) = run(true, true) { selected()?.let { classroom ->
        require(id != classroom.teacherId && id in classroom.teacherIds()); api.removeTeacher(classroom.id, id)
    } }
    fun removeLearner(id: String) = run(true, false) { selected()?.let { classroom ->
        require((state.value.roster as? Cached.Content)?.value.orEmpty().any {
            it.enrollment.studentId == id && it.enrollment.status == "ACTIVE"
        }); api.removeLearner(classroom.id, id)
    } }
    private fun run(requireClass: Boolean, ownerOnly: Boolean, command: suspend () -> Unit) {
        if (state.value.busy) return
        if (!reachable.value) { mutable.update { it.copy(error = R.string.classes_offline) }; return }
        if (requireClass && selected()?.canManage(userId, ownerOnly) != true) {
            mutable.update { it.copy(error = R.string.classes_forbidden) }; return
        }
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            var committed = false
            try {
                command(); committed = true
                container.sync.refreshRoster(userId) {
                    refresher.refreshClasses()
                    val id = state.value.selected
                    if (id != null) refresher.refreshIfActive(id)
                }
                // Classroom receipts contain teacher IDs; sync supplies their public display names.
                container.sync.pull(userId)
                val id = state.value.selected
                if (id != null && rosterJob == null) {
                    rosterJob = viewModelScope.launch { container.enrollments.roster(id, viewModelScope).collect { rows ->
                        mutable.update { it.copy(roster = rows) }
                    } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is HubFailure && e.status == 401) expired()
                mutable.update { it.copy(error = if (committed) R.string.classes_refresh_failed else R.string.classes_failed) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
