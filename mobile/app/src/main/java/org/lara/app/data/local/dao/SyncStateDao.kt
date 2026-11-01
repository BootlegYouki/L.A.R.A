package org.lara.app.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.lara.app.data.local.entities.*

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE substr(`key`,1,length(:prefix))=:prefix ORDER BY `key`")
    fun observePrefix(prefix: String): Flow<List<SyncStateEntity>>
    @Query("SELECT * FROM sync_state WHERE `key` = :key")
    fun observe(key: String): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): SyncStateEntity?

    @Upsert
    suspend fun put(row: SyncStateEntity)
}
