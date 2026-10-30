package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "enrollments",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = ClassroomEntity::class, parentColumns = ["id"], childColumns = ["classroom_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["student_id", "status"], name = "idx_client_enrollments_student", unique = false, orders = [Index.Order.ASC, Index.Order.ASC]),
        Index(value = ["classroom_id", "student_id"], name = "index_enrollments_classroom_id_student_id", unique = true, orders = [Index.Order.ASC, Index.Order.ASC])
    ],
)
data class EnrollmentEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "classroom_id") val classroomId: String,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "status", defaultValue = "'PENDING'") val status: String = "PENDING",
    @ColumnInfo(name = "joined_at") val joinedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
 {
    init {
        require(status in setOf("PENDING", "ACTIVE", "REJECTED", "REMOVED"))
    }
}
