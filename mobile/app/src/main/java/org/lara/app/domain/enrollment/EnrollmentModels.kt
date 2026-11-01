package org.lara.app.domain.enrollment

import org.lara.app.domain.model.Classroom
import org.lara.app.domain.model.Enrollment

/** A learner's REST membership status can authorize access without fabricating an enrollment ID. */
data class LearnerClass(val classroom: Classroom, val enrollment: Enrollment? = null,
    val restStatus: EnrollmentStatus? = null) {
    val status: EnrollmentStatus get() = restStatus ?: enrollment?.status?.let(EnrollmentStatus::valueOf) ?: EnrollmentStatus.PENDING
    val canOpen: Boolean get() = status == EnrollmentStatus.ACTIVE
}

enum class EnrollmentStatus { PENDING, ACTIVE, REJECTED, REMOVED }

/** Request metadata is not an enrollment and never grants class access. */
data class EnrollmentNotice(val classroomId: String, val title: String?, val status: EnrollmentStatus)

interface EnrollmentNoticeRepository {
    fun notices(userId: String): kotlinx.coroutines.flow.Flow<List<EnrollmentNotice>>
    suspend fun save(userId: String, notice: EnrollmentNotice)
}

interface EnrollmentSessionSource {
    val reachable: kotlinx.coroutines.flow.StateFlow<Boolean>
    val events: kotlinx.coroutines.flow.Flow<HubEvent>
}

sealed interface HubEvent {
    data class HelloAck(val hubId: String, val syncEpoch: Long, val serverTime: Long) : HubEvent
    data class JoinApproval(val classroomId: String, val studentId: String,
        val status: EnrollmentStatus, val timestamp: Long) : HubEvent
    data class JoinRequest(val classCode: String, val studentId: String, val fullName: String,
        val lrn: String, val timestamp: Long) : HubEvent {
        override fun toString() = "JoinRequest([redacted])"
    }
    data object SessionExpired : HubEvent
}

sealed interface EnrollmentRefreshHint {
    data object Foreground : EnrollmentRefreshHint
    data class Connected(val hubId: String, val syncEpoch: Long) : EnrollmentRefreshHint
    data class MembershipChanged(val classroomId: String) : EnrollmentRefreshHint
}
