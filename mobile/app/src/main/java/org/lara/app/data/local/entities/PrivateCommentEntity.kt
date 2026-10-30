package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "private_comments",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = AssignmentEntity::class, parentColumns = ["id"], childColumns = ["assignment_id"], onDelete = ForeignKey.CASCADE)],
)
data class PrivateCommentEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "assignment_id") val assignmentId: String,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "author_id") val authorId: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_status", defaultValue = "'SYNCED'") val syncStatus: String = "SYNCED",
)
 {
    init {
        require(syncStatus in setOf("SYNCED", "QUEUED_FOR_SYNC"))
    }
}
