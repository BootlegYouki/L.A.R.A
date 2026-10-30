package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "assignment_submissions",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = AssignmentEntity::class, parentColumns = ["id"], childColumns = ["assignment_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["sync_status", "submitted_at"], name = "idx_client_submissions_sync", unique = false, orders = [Index.Order.ASC, Index.Order.DESC]),
        Index(value = ["assignment_id", "student_id"], name = "index_assignment_submissions_assignment_id_student_id", unique = true, orders = [Index.Order.ASC, Index.Order.ASC])
    ],
)
data class AssignmentSubmissionEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "assignment_id") val assignmentId: String,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "file_type", defaultValue = "'IMAGE'") val fileType: String = "IMAGE",
    @ColumnInfo(name = "submitted_at") val submittedAt: Long,
    @ColumnInfo(name = "score") val score: Long? = null,
    @ColumnInfo(name = "teacher_feedback") val teacherFeedback: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_status", defaultValue = "'QUEUED_FOR_SYNC'") val syncStatus: String = "QUEUED_FOR_SYNC",
)
 {
    init {
        require(fileType in setOf("IMAGE", "DOCUMENT"))
        require(syncStatus in setOf("SYNCED", "QUEUED_FOR_SYNC"))
    }
}
