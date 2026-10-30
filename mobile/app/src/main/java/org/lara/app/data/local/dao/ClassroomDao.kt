package org.lara.app.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.lara.app.data.local.entities.*

@Dao
interface ClassroomDao {
    @Query("SELECT * FROM classrooms WHERE archived_at IS NULL ORDER BY name, id")
    fun observeAll(): Flow<List<ClassroomEntity>>
}
