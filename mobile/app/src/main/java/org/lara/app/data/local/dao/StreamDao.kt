package org.lara.app.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.lara.app.data.local.entities.*

@Dao
interface StreamDao {
    @Query("SELECT * FROM announcements WHERE classroom_id = :classroomId ORDER BY created_at DESC, id")
    fun observeByClassroom(classroomId: String): Flow<List<AnnouncementEntity>>

    @Query("SELECT * FROM announcement_comments WHERE announcement_id = :announcementId ORDER BY created_at, id")
    fun observeComments(announcementId: String): Flow<List<AnnouncementCommentEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertComment(row: AnnouncementCommentEntity)

    @Query("SELECT allow_comments FROM announcements WHERE id = :id")
    suspend fun allowsComments(id: String): Boolean?

    @Transaction
    suspend fun enqueueComment(row: AnnouncementCommentEntity) {
        check(allowsComments(row.announcementId) == true) { "Comments unavailable" }
        insertComment(row)
    }
}
