package org.lara.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import org.lara.app.data.local.entities.UserEntity
import org.lara.app.data.local.entities.SyncStateEntity

@Dao
abstract class AuthDao {
    @Query("SELECT * FROM users WHERE id = :id")
    abstract suspend fun user(id: String): UserEntity?
    @Query("SELECT value FROM sync_state WHERE `key` = 'current_user_id'")
    abstract suspend fun currentUserId(): String?
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
