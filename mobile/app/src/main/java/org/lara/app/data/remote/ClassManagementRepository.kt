package org.lara.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.lara.app.domain.enrollment.EnrollmentStatus

@Serializable
data class TeacherRosterEntry(
    @SerialName("student_id") val studentId: String,
    @SerialName("full_name") val fullName: String,
    val lrn: String,
    val status: EnrollmentStatus,
    @SerialName("joined_at") val joinedAt: Long? = null,
) {
    override fun toString() = "TeacherRosterEntry([redacted])"
}
@Serializable
private data class StudentRef(@SerialName("student_id") val studentId: String)

interface ClassManagementRepository {
    suspend fun roster(classroomId: String): List<TeacherRosterEntry>
    suspend fun decide(classroomId: String, studentId: String, accept: Boolean)
}

/** Teacher authorization is enforced by the Hub; credentials use the existing session boundary. */
class HubClassManagementRepository(
    private val userId: String,
    private val sessions: EnrollmentApi,
    private val client: HubClient,
) : ClassManagementRepository {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun roster(classroomId: String): List<TeacherRosterEntry> = withContext(Dispatchers.IO) {
        val c = sessions.connection(userId)
        json.decodeFromString(ListSerializer(TeacherRosterEntry.serializer()),
            client.teacherRoster(c, classroomId, null, null))
    }
    override suspend fun decide(classroomId: String, studentId: String, accept: Boolean) = withContext(Dispatchers.IO) {
        require(studentId.isNotBlank())
        val c = sessions.connection(userId)
        client.teacherRoster(c, classroomId, if (accept) "approve" else "reject",
            json.encodeToString(StudentRef.serializer(), StudentRef(studentId)))
        Unit
    }
}
