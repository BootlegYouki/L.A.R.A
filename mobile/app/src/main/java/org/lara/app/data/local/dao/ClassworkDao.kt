package org.lara.app.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.lara.app.data.local.entities.*

@Dao
interface ClassworkDao {
    @Query("SELECT local_file_path FROM materials WHERE local_file_path IS NOT NULL")
    suspend fun savedFiles(): List<String>
    @Query("SELECT * FROM materials WHERE id = :id")
    suspend fun material(id: String): MaterialEntity?

    @Query("UPDATE materials SET local_file_path = :path, mime_type = :mime WHERE id = :id AND updated_at = :version")
    suspend fun saveFile(id: String, version: Long, path: String, mime: String): Int

    @Query("SELECT * FROM topics WHERE classroom_id = :classroomId ORDER BY order_index, id")
    fun observeTopics(classroomId: String): Flow<List<TopicEntity>>

    @Query("SELECT * FROM materials WHERE classroom_id = :classroomId ORDER BY created_at DESC, id")
    fun observeMaterials(classroomId: String): Flow<List<MaterialEntity>>

    @Query("SELECT * FROM assignments WHERE classroom_id = :classroomId ORDER BY due_date, id")
    fun observeAssignments(classroomId: String): Flow<List<AssignmentEntity>>

    @Query("SELECT * FROM material_chunks WHERE material_id = :materialId ORDER BY order_index, id")
    fun observeChunks(materialId: String): Flow<List<MaterialChunkEntity>>
}
