package org.lara.app.ui.screens.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.serialization.json.Json
import org.lara.app.AppContainer
import org.lara.app.domain.model.Classroom

internal fun Classroom.managedBy(teacherId: String): Boolean = archivedAt == null &&
    (this.teacherId == teacherId || runCatching {
        Json.decodeFromString<List<String>>(coTeacherIdsJson ?: "[]").contains(teacherId)
    }.getOrDefault(false))

/** The signed-in session now owns the socket; this model owns only teacher class reads and commands. */
class TeacherApprovalSession(container: AppContainer, userId: String) : ViewModel() {
    val classrooms = container.classrooms.classrooms(viewModelScope)
    val remote = container.classManagement(userId)
    val refresher = container.savedRosterRefresher(remote)
}
