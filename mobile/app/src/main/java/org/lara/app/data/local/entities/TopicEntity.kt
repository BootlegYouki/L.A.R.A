package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "topics",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = ClassroomEntity::class, parentColumns = ["id"], childColumns = ["classroom_id"], onDelete = ForeignKey.CASCADE)],
)
data class TopicEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "classroom_id") val classroomId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "order_index", defaultValue = "0") val orderIndex: Long = 0L,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
