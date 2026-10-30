package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "quiz_attempts",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = QuizEntity::class, parentColumns = ["id"], childColumns = ["quiz_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["sync_status", "submitted_at"], name = "idx_client_attempts_sync", unique = false, orders = [Index.Order.ASC, Index.Order.DESC]),
        Index(value = ["quiz_id", "student_id"], name = "index_quiz_attempts_quiz_id_student_id", unique = true, orders = [Index.Order.ASC, Index.Order.ASC])
    ],
)
data class QuizAttemptEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "quiz_id") val quizId: String,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "status", defaultValue = "'IN_PROGRESS'") val status: String = "IN_PROGRESS",
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "submitted_at") val submittedAt: Long? = null,
    @ColumnInfo(name = "score") val score: Long? = null,
    @ColumnInfo(name = "total_points") val totalPoints: Long? = null,
    @ColumnInfo(name = "answers_json", defaultValue = "'[]'") val answersJson: String = "[]",
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_status", defaultValue = "'QUEUED_FOR_SYNC'") val syncStatus: String = "QUEUED_FOR_SYNC",
)
 {
    init {
        require(status in setOf("IN_PROGRESS", "SUBMITTED"))
        require(syncStatus in setOf("SYNCED", "QUEUED_FOR_SYNC"))
        require(status == "IN_PROGRESS" || submittedAt != null)
    }
}
