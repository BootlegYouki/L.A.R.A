package org.lara.app.domain.enrollment

import org.lara.app.domain.model.Classroom
import org.lara.app.domain.model.Enrollment

/** Membership comes from the authoritative enrollment row, never a locally generated ID. */
data class LearnerClass(val classroom: Classroom, val enrollment: Enrollment) {
    val canOpen: Boolean get() = enrollment.status == "ACTIVE"
}

enum class EnrollmentStatus { PENDING, ACTIVE, REJECTED, REMOVED }

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
    data class Connected(val hubId: String, val syncEpoch: Long) : EnrollmentRefreshHint
    data class MembershipChanged(val classroomId: String) : EnrollmentRefreshHint
}
