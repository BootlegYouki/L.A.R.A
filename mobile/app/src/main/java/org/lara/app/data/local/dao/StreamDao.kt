package org.lara.app.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.lara.app.data.local.entities.*

data class UnreadPrivateThread(val assignmentId: String, val studentId: String, val count: Int)

@Dao
interface StreamDao {
    @Query("SELECT * FROM announcements WHERE classroom_id = :classroomId ORDER BY created_at DESC, id")
    fun observeByClassroom(classroomId: String): Flow<List<AnnouncementEntity>>

    @Query("SELECT * FROM announcement_comments WHERE announcement_id = :announcementId ORDER BY created_at, id")
    fun observeComments(announcementId: String): Flow<List<AnnouncementCommentEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertComment(row: AnnouncementCommentEntity)

    @Query("SELECT * FROM private_comments WHERE assignment_id = :assignmentId ORDER BY created_at, id")
    fun observePrivateComments(assignmentId: String): Flow<List<PrivateCommentEntity>>

    @Query("SELECT * FROM private_comments WHERE assignment_id = :assignmentId AND student_id = :studentId ORDER BY created_at, id")
    suspend fun privateThread(assignmentId: String, studentId: String): List<PrivateCommentEntity>

    @Query("""SELECT pc.assignment_id AS assignmentId, pc.student_id AS studentId, COUNT(*) AS count
        FROM private_comments pc
        JOIN assignments a ON a.id = pc.assignment_id
        JOIN enrollments e ON e.classroom_id = a.classroom_id AND e.student_id = pc.student_id
        WHERE a.classroom_id = :classroomId AND e.status = 'ACTIVE' AND pc.author_id = pc.student_id
          AND NOT EXISTS (
              SELECT 1 FROM sync_state seen WHERE seen.`key` =
                :readPrefix || COALESCE((SELECT value FROM sync_state WHERE `key` = 'hub_id'), '') ||
                '/' || pc.assignment_id || '/' || pc.student_id || '/' || pc.id
          )
        GROUP BY pc.assignment_id, pc.student_id
        ORDER BY pc.assignment_id, pc.student_id""")
    fun observeUnreadPrivateThreads(classroomId: String, readPrefix: String): Flow<List<UnreadPrivateThread>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPrivateComment(row: PrivateCommentEntity)

    @Query("SELECT allow_comments FROM announcements WHERE id = :id")
    suspend fun allowsComments(id: String): Boolean?

    @Transaction
    suspend fun enqueueComment(row: AnnouncementCommentEntity) {
        check(allowsComments(row.announcementId) == true) { "Comments unavailable" }
        insertComment(row)
    }
}
