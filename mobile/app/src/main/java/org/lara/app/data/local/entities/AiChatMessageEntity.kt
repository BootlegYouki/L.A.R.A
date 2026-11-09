package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "ai_chat_messages",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = ClassroomEntity::class, parentColumns = ["id"], childColumns = ["classroom_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["classroom_id", "created_at"], name = "idx_client_ai_chat", unique = false, orders = [Index.Order.ASC, Index.Order.ASC])
    ],
)
data class AiChatMessageEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "classroom_id") val classroomId: String,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "material_id") val materialId: String? = null,
    @ColumnInfo(name = "chunk_id") val chunkId: String? = null,
    @ColumnInfo(name = "language") val language: String? = null,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
 {
    init {
        require(language == null || language in setOf("EN", "FIL"))
        require(role in setOf("USER", "TUTOR"))
    }
}
