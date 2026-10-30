package org.lara.app.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.lara.app.data.local.entities.*

data class CacheBatch(
    val users: List<UserEntity> = emptyList(),
    val classrooms: List<ClassroomEntity> = emptyList(),
    val enrollments: List<EnrollmentEntity> = emptyList(),
    val announcements: List<AnnouncementEntity> = emptyList(),
    val announcementComments: List<AnnouncementCommentEntity> = emptyList(),
    val materials: List<MaterialEntity> = emptyList(),
    val materialChunks: List<MaterialChunkEntity> = emptyList(),
    val assignments: List<AssignmentEntity> = emptyList(),
    val assignmentSubmissions: List<AssignmentSubmissionEntity> = emptyList(),
    val quizzes: List<QuizEntity> = emptyList(),
    val quizQuestions: List<QuizQuestionEntity> = emptyList(),
    val quizAttempts: List<QuizAttemptEntity> = emptyList(),
    val aiChatMessages: List<AiChatMessageEntity> = emptyList(),
    val topics: List<TopicEntity> = emptyList(),
    val privateComments: List<PrivateCommentEntity> = emptyList(),
    val syncState: List<SyncStateEntity> = emptyList(),
)

@Dao
abstract class CacheDao {
    @Upsert
    protected abstract suspend fun upsertUser(rows: List<UserEntity>)

    @Upsert
    protected abstract suspend fun upsertClassroom(rows: List<ClassroomEntity>)

    @Upsert
    protected abstract suspend fun upsertEnrollment(rows: List<EnrollmentEntity>)

    @Upsert
    protected abstract suspend fun upsertAnnouncement(rows: List<AnnouncementEntity>)

    @Upsert
    protected abstract suspend fun upsertAnnouncementComment(rows: List<AnnouncementCommentEntity>)

    @Query("SELECT * FROM announcement_comments WHERE id = :id")
    protected abstract suspend fun findAnnouncementComment(id: String): AnnouncementCommentEntity?

    @Upsert
    protected abstract suspend fun upsertMaterial(rows: List<MaterialEntity>)

    @Query("SELECT * FROM materials WHERE id = :id")
    protected abstract suspend fun findMaterial(id: String): MaterialEntity?

    @Upsert
    protected abstract suspend fun upsertMaterialChunk(rows: List<MaterialChunkEntity>)

    @Upsert
    protected abstract suspend fun upsertAssignment(rows: List<AssignmentEntity>)

    @Upsert
    protected abstract suspend fun upsertAssignmentSubmission(rows: List<AssignmentSubmissionEntity>)

    @Query("SELECT * FROM assignment_submissions WHERE id = :id")
    protected abstract suspend fun findAssignmentSubmission(id: String): AssignmentSubmissionEntity?

    @Upsert
    protected abstract suspend fun upsertQuiz(rows: List<QuizEntity>)

    @Upsert
    protected abstract suspend fun upsertQuizQuestion(rows: List<QuizQuestionEntity>)

    @Upsert
    protected abstract suspend fun upsertQuizAttempt(rows: List<QuizAttemptEntity>)

    @Query("SELECT * FROM quiz_attempts WHERE id = :id")
    protected abstract suspend fun findQuizAttempt(id: String): QuizAttemptEntity?

    @Upsert
    protected abstract suspend fun upsertAiChatMessage(rows: List<AiChatMessageEntity>)

    @Upsert
    protected abstract suspend fun upsertTopic(rows: List<TopicEntity>)

    @Upsert
    protected abstract suspend fun upsertPrivateComment(rows: List<PrivateCommentEntity>)

    @Query("SELECT * FROM private_comments WHERE id = :id")
    protected abstract suspend fun findPrivateComment(id: String): PrivateCommentEntity?

    @Upsert
    protected abstract suspend fun upsertSyncState(rows: List<SyncStateEntity>)

    @Query("SELECT id FROM enrollments WHERE classroom_id = :first AND student_id = :second")
    protected abstract suspend fun enrollmentsKey(first: String, second: String): String?

    @Query("SELECT id FROM material_chunks WHERE material_id = :first AND order_index = :second")
    protected abstract suspend fun materialChunksKey(first: String, second: Long): String?

    @Query("SELECT id FROM assignment_submissions WHERE assignment_id = :first AND student_id = :second")
    protected abstract suspend fun assignmentSubmissionsKey(first: String, second: String): String?

    @Query("SELECT id FROM quiz_questions WHERE quiz_id = :first AND order_index = :second")
    protected abstract suspend fun quizQuestionsKey(first: String, second: Long): String?

    @Query("SELECT id FROM quiz_attempts WHERE quiz_id = :first AND student_id = :second")
    protected abstract suspend fun quizAttemptsKey(first: String, second: String): String?

    // Room 2.6 Upsert can swallow a secondary-key conflict when its UPDATE matches no ID.
    // Validate those keys explicitly so the whole batch rolls back instead of losing a row.
    @Transaction
    open suspend fun upsertBatch(batch: CacheBatch) {
        upsertUser(batch.users)
        upsertClassroom(batch.classrooms)
        for (row in batch.enrollments) {
            val existingId = enrollmentsKey(row.classroomId, row.studentId)
            require(existingId == null || existingId == row.id) { "Conflicting enrollments unique key" }
            upsertEnrollment(listOf(row))
        }
        upsertTopic(batch.topics)
        upsertAnnouncement(batch.announcements)
        upsertAnnouncementComment(batch.announcementComments.filter { findAnnouncementComment(it.id)?.syncStatus != "QUEUED_FOR_SYNC" })
        upsertMaterial(batch.materials.map { it.copy(localFilePath = findMaterial(it.id)?.localFilePath ?: it.localFilePath) })
        for (row in batch.materialChunks) {
            val existingId = materialChunksKey(row.materialId, row.orderIndex)
            require(existingId == null || existingId == row.id) { "Conflicting material_chunks unique key" }
            upsertMaterialChunk(listOf(row))
        }
        upsertAssignment(batch.assignments)
        for (row in batch.assignmentSubmissions.filter { findAssignmentSubmission(it.id)?.syncStatus != "QUEUED_FOR_SYNC" }) {
            val existingId = assignmentSubmissionsKey(row.assignmentId, row.studentId)
            require(existingId == null || existingId == row.id) { "Conflicting assignment_submissions unique key" }
            upsertAssignmentSubmission(listOf(row))
        }
        upsertQuiz(batch.quizzes)
        for (row in batch.quizQuestions) {
            val existingId = quizQuestionsKey(row.quizId, row.orderIndex)
            require(existingId == null || existingId == row.id) { "Conflicting quiz_questions unique key" }
            upsertQuizQuestion(listOf(row))
        }
        for (row in batch.quizAttempts.filter { findQuizAttempt(it.id)?.syncStatus != "QUEUED_FOR_SYNC" }) {
            val existingId = quizAttemptsKey(row.quizId, row.studentId)
            require(existingId == null || existingId == row.id) { "Conflicting quiz_attempts unique key" }
            upsertQuizAttempt(listOf(row))
        }
        upsertAiChatMessage(batch.aiChatMessages)
        upsertPrivateComment(batch.privateComments.filter { findPrivateComment(it.id)?.syncStatus != "QUEUED_FOR_SYNC" })
        upsertSyncState(batch.syncState)
    }

    @Query("DELETE FROM material_chunks WHERE material_id IN (:materialIds)")
    protected abstract suspend fun deleteChunks(materialIds: List<String>)

    @Query("DELETE FROM materials WHERE id IN (:ids)")
    protected abstract suspend fun deleteMaterials(ids: List<String>)

    @Query("DELETE FROM topics WHERE id IN (:ids)")
    protected abstract suspend fun deleteTopics(ids: List<String>)

    @Query("DELETE FROM announcements WHERE id IN (:ids) AND NOT EXISTS (SELECT 1 FROM announcement_comments WHERE announcement_id = announcements.id AND sync_status = 'QUEUED_FOR_SYNC')")
    protected abstract suspend fun deleteAnnouncements(ids: List<String>)

    @Query("DELETE FROM announcement_comments WHERE id IN (:ids) AND sync_status = 'SYNCED'")
    protected abstract suspend fun deleteComments(ids: List<String>)

    @Query("DELETE FROM private_comments WHERE id IN (:ids) AND sync_status = 'SYNCED'")
    protected abstract suspend fun deletePrivateComments(ids: List<String>)

    @Transaction
    open suspend fun deleteBatch(
        materialIds: List<String> = emptyList(),
        topicIds: List<String> = emptyList(),
        announcementIds: List<String> = emptyList(),
        commentIds: List<String> = emptyList(),
        privateCommentIds: List<String> = emptyList(),
    ) {
        deleteChunks(materialIds)
        deleteMaterials(materialIds)
        deleteTopics(topicIds)
        deleteComments(commentIds)
        deletePrivateComments(privateCommentIds)
        deleteAnnouncements(announcementIds)
    }
}
