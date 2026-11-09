package org.lara.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.lara.app.data.remote.dto.ClassroomDto

@Serializable
private data class ClassDetails(val name: String, val section: String)
@Serializable
private data class ArchiveClass(val archived: Boolean = true)
@Serializable
private data class ClassLearner(@SerialName("student_id") val studentId: String)
@Serializable
private data class ClassTeacher(@SerialName("lrn_or_id") val teacherId: String)

/** Online-only commands. Receipts must be followed by an authoritative cache refresh. */
class TeacherClassApi(private val userId: String, private val sessions: EnrollmentApi,
    private val client: HubClient) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private suspend fun command(id: String?, action: String?, teacher: String?, method: String,
        body: String?): String = withContext(Dispatchers.IO) {
        client.manageClass(sessions.connection(userId), id, action, teacher, method, body)
    }
    suspend fun create(name: String, section: String): ClassroomDto {
        val details = details(name, section)
        return json.decodeFromString(ClassroomDto.serializer(), command(null, null, null, "POST",
            json.encodeToString(ClassDetails.serializer(), details)))
    }
    suspend fun rename(id: String, name: String, section: String): ClassroomDto =
        json.decodeFromString(ClassroomDto.serializer(), command(id, null, null, "PATCH",
            json.encodeToString(ClassDetails.serializer(), details(name, section))))
    suspend fun archive(id: String): ClassroomDto =
        json.decodeFromString(ClassroomDto.serializer(), command(id, null, null, "PATCH",
            json.encodeToString(ArchiveClass.serializer(), ArchiveClass())))
    suspend fun removeLearner(id: String, studentId: String) {
        require(studentId.isNotBlank())
        command(id, "remove", null, "POST", json.encodeToString(ClassLearner.serializer(), ClassLearner(studentId)))
    }
    suspend fun addTeacher(id: String, teacherId: String): ClassroomDto {
        require(teacherId.trim().isNotEmpty())
        return json.decodeFromString(ClassroomDto.serializer(), command(id, "teachers", null, "POST",
            json.encodeToString(ClassTeacher.serializer(), ClassTeacher(teacherId.trim()))))
    }
    suspend fun removeTeacher(id: String, teacherId: String) {
        command(id, "teachers", teacherId, "DELETE", null)
    }
    private fun details(name: String, section: String): ClassDetails {
        require(name.trim().isNotEmpty() && section.trim().isNotEmpty())
        return ClassDetails(name.trim(), section.trim())
    }
}
