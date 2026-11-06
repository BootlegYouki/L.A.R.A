package org.lara.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import org.lara.app.data.local.entities.UserEntity
import org.lara.app.data.local.entities.SyncStateEntity

data class CachedTeacherName(val id: String, val full_name: String)
data class CommentAuthorName(val id: String, val full_name: String, val role: String)

@Dao
abstract class AuthDao {
    @Query("SELECT id, full_name, role FROM users")
    abstract fun observeCommentNames(): kotlinx.coroutines.flow.Flow<List<CommentAuthorName>>
    @Query("SELECT id, full_name FROM users WHERE role = 'TEACHER'")
    abstract fun observeTeacherNames(): kotlinx.coroutines.flow.Flow<List<CachedTeacherName>>
    @Query("SELECT * FROM users WHERE id = :id")
    abstract suspend fun user(id: String): UserEntity?
    @Query("SELECT value FROM sync_state WHERE `key` = 'current_user_id'")
    abstract suspend fun currentUserId(): String?
    @Query("SELECT NOT EXISTS(SELECT 1 FROM users) AND NOT EXISTS(SELECT 1 FROM classrooms) AND NOT EXISTS(SELECT 1 FROM sync_state)")
    abstract suspend fun emptyForClaim(): Boolean
    @Query("""SELECT
        (SELECT COUNT(*) FROM announcement_comments WHERE author_id=:userId AND sync_status='QUEUED_FOR_SYNC') +
        (SELECT COUNT(*) FROM private_comments WHERE author_id=:userId AND sync_status='QUEUED_FOR_SYNC') +
        (SELECT COUNT(*) FROM assignment_submissions WHERE student_id=:userId AND sync_status='QUEUED_FOR_SYNC') +
        (SELECT COUNT(*) FROM quiz_attempts WHERE student_id=:userId AND sync_status='QUEUED_FOR_SYNC')""")
    abstract suspend fun queuedCount(userId: String): Int
    @Upsert
    protected abstract suspend fun putUser(user: UserEntity)
    @Upsert
    protected abstract suspend fun putState(state: SyncStateEntity)
    @Transaction
    open suspend fun saveProfile(user: UserEntity) {
        putUser(user)
        putState(SyncStateEntity("current_user_id", user.id, user.updatedAt))
    }
}
