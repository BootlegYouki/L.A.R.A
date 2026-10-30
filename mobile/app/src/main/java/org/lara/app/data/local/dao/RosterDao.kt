package org.lara.app.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.lara.app.data.local.entities.*

data class RosterRow(
    @Embedded val enrollment: EnrollmentEntity,
    @Relation(parentColumn = "student_id", entityColumn = "id") val user: UserEntity?,
)

@Dao
interface RosterDao {
    @Transaction
    @Query("SELECT * FROM enrollments WHERE classroom_id = :classroomId ORDER BY joined_at, id")
    fun observeByClassroom(classroomId: String): Flow<List<RosterRow>>
}
