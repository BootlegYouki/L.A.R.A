package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "assignments",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = ClassroomEntity::class, parentColumns = ["id"], childColumns = ["classroom_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["classroom_id", "due_date"], name = "idx_client_assignments_due", unique = false, orders = [Index.Order.ASC, Index.Order.ASC])
    ],
)
data class AssignmentEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "classroom_id") val classroomId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "instructions", defaultValue = "''") val instructions: String = "",
    @ColumnInfo(name = "due_date") val dueDate: Long,
    @ColumnInfo(name = "allow_late", defaultValue = "0") val allowLate: Boolean = false,
    @ColumnInfo(name = "max_points", defaultValue = "100") val maxPoints: Long = 100L,
    @ColumnInfo(name = "topic_id") val topicId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
