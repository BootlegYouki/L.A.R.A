package org.lara.app.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.lara.app.data.local.entities.*

data class RosterRow(
    @Embedded val enrollment: EnrollmentEntity,
    @Relation(parentColumn = "student_id", entityColumn = "id") val user: UserEntity?,
)

data class LearnerClassRow(
    @Embedded val enrollment: EnrollmentEntity,
    @Relation(parentColumn = "classroom_id", entityColumn = "id") val classroom: ClassroomEntity,
)

@Dao
interface RosterDao {
    @Query("UPDATE enrollments SET status = :status WHERE classroom_id = :classroomId AND student_id = :studentId")
    suspend fun updateSavedStatus(classroomId: String, studentId: String, status: String): Int

    @Query("UPDATE users SET full_name = :name, lrn_or_id = :lrn WHERE id = :studentId AND role = 'STUDENT'")
    suspend fun updateSavedLearner(studentId: String, name: String, lrn: String)

    @Transaction
    @Query("SELECT * FROM enrollments WHERE student_id = :studentId ORDER BY joined_at, id")
    fun observeForStudent(studentId: String): Flow<List<LearnerClassRow>>

    @Transaction
    @Query("SELECT * FROM enrollments WHERE classroom_id = :classroomId ORDER BY joined_at, id")
    fun observeByClassroom(classroomId: String): Flow<List<RosterRow>>
}
