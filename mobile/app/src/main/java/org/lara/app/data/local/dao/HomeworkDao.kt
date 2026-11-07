package org.lara.app.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.lara.app.data.local.entities.AssignmentSubmissionEntity

@Dao
interface HomeworkDao {
    @Query("SELECT * FROM assignment_submissions WHERE assignment_id=:assignmentId AND student_id=:userId")
    fun observe(assignmentId: String, userId: String): Flow<AssignmentSubmissionEntity?>

    @Query("SELECT * FROM assignment_submissions WHERE assignment_id=:assignmentId AND student_id=:userId")
    suspend fun find(assignmentId: String, userId: String): AssignmentSubmissionEntity?

    @Query("SELECT * FROM assignment_submissions WHERE student_id=:userId AND sync_status='QUEUED_FOR_SYNC' ORDER BY submitted_at, id")
    suspend fun queued(userId: String): List<AssignmentSubmissionEntity>

    @Query("UPDATE assignment_submissions SET id=:canonicalId WHERE id=:localId")
    suspend fun adoptId(localId: String, canonicalId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM assignment_submissions WHERE file_path=:path)")
    suspend fun referencesFile(path: String): Boolean

    @Upsert
    suspend fun save(row: AssignmentSubmissionEntity)
}
