package org.lara.app.data.local.dao

import androidx.room.*
import org.lara.app.data.local.entities.*

/** Reads and conditional acknowledgements used by the sync transaction owner. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM users WHERE id=:id") suspend fun user(id: String): UserEntity?
    @Query("SELECT * FROM classrooms WHERE id=:id") suspend fun classroom(id: String): ClassroomEntity?
    @Query("SELECT * FROM enrollments WHERE classroom_id=:classroomId AND student_id=:studentId")
    suspend fun enrollment(classroomId: String, studentId: String): EnrollmentEntity?
    @Query("SELECT * FROM announcements WHERE id=:id") suspend fun announcement(id: String): AnnouncementEntity?
    @Query("SELECT * FROM materials WHERE id=:id") suspend fun material(id: String): MaterialEntity?
    @Query("SELECT * FROM assignments WHERE id=:id") suspend fun assignment(id: String): AssignmentEntity?
    @Query("SELECT * FROM quizzes WHERE id=:id") suspend fun quiz(id: String): QuizEntity?
    @Query("SELECT * FROM quiz_attempts WHERE id=:id") suspend fun attempt(id: String): QuizAttemptEntity?
    @Query("SELECT * FROM assignment_submissions WHERE id=:id") suspend fun submission(id: String): AssignmentSubmissionEntity?
    @Query("SELECT * FROM announcement_comments WHERE author_id=:userId AND sync_status='QUEUED_FOR_SYNC' ORDER BY created_at, id LIMIT 50 OFFSET :offset")
    suspend fun queuedComments(userId: String, offset: Int = 0): List<AnnouncementCommentEntity>
    @Query("SELECT * FROM private_comments WHERE author_id=:userId AND sync_status='QUEUED_FOR_SYNC' ORDER BY created_at, id LIMIT 50 OFFSET :offset")
    suspend fun queuedPrivateComments(userId: String, offset: Int = 0): List<PrivateCommentEntity>
    @Query("SELECT * FROM quiz_attempts WHERE student_id=:userId AND status='SUBMITTED' AND sync_status='QUEUED_FOR_SYNC' ORDER BY submitted_at, id LIMIT 50 OFFSET :offset")
    suspend fun queuedAttempts(userId: String, offset: Int = 0): List<QuizAttemptEntity>
    @Query("UPDATE announcement_comments SET sync_status='SYNCED' WHERE id=:id AND content=:content AND updated_at=:version AND sync_status='QUEUED_FOR_SYNC'")
    suspend fun acknowledgeComment(id: String, content: String, version: Long)
    @Query("UPDATE private_comments SET sync_status='SYNCED' WHERE id=:id AND content=:content AND updated_at=:version AND sync_status='QUEUED_FOR_SYNC'")
    suspend fun acknowledgePrivateComment(id: String, content: String, version: Long)
    @Query("UPDATE quiz_attempts SET sync_status='SYNCED' WHERE id=:id AND answers_json=:answers AND submitted_at=:submittedAt AND updated_at=:version AND sync_status='QUEUED_FOR_SYNC'")
    suspend fun acknowledgeAttempt(id: String, answers: String, submittedAt: Long, version: Long)
    @Query("UPDATE quiz_attempts SET score=:score,total_points=:total WHERE id=:id")
    suspend fun applyGrade(id: String, score: Long?, total: Long)
    @Query("UPDATE assignment_submissions SET score=:score,teacher_feedback=:feedback WHERE id=:id")
    suspend fun applySubmissionGrade(id: String, score: Long?, feedback: String?)
}
