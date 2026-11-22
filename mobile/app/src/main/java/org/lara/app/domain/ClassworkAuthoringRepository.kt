package org.lara.app.domain

import kotlinx.coroutines.flow.Flow

data class AssignmentDraft(val title: String, val instructions: String, val maxPoints: Long,
    val dueDate: Long, val allowLate: Boolean, val topicId: String?)
data class MaterialDraft(val title: String, val fileType: String, val topicId: String? = null,
    val assignmentId: String? = null, val announcementId: String? = null)
data class AuthoringUpload(val id: String, val title: String, val state: String, val percent: Int, val announcementId: String? = null)

interface ClassworkAuthoringRepository {
    suspend fun saveAssignment(classroomId: String, id: String?, draft: AssignmentDraft): String
    suspend fun saveTopic(classroomId: String, id: String?, name: String)
    suspend fun reorderTopic(classroomId: String, id: String, name: String, order: Long) = saveTopic(classroomId, id, name)
    suspend fun saveMaterial(id: String, title: String, topicId: String?)
    suspend fun delete(kind: String, id: String)
    suspend fun upload(classroomId: String, uri: String, draft: MaterialDraft)
    fun uploads(classroomId: String): Flow<List<AuthoringUpload>>
    suspend fun retry(uploadId: String)
    suspend fun retryUncertain(uploadId: String) = retry(uploadId)
    suspend fun cancel(uploadId: String) = Unit
    suspend fun pendingUploadCount(): Int = 0
}
