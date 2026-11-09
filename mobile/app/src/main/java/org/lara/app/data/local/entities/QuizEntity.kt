package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "quizzes",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = ClassroomEntity::class, parentColumns = ["id"], childColumns = ["classroom_id"], onDelete = ForeignKey.CASCADE)],
)
data class QuizEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "classroom_id") val classroomId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "instructions") val instructions: String? = null,
    @ColumnInfo(name = "topic_id") val topicId: String? = null,
    @ColumnInfo(name = "time_limit_minutes") val timeLimitMinutes: Long,
    @ColumnInfo(name = "shuffle_questions", defaultValue = "0") val shuffleQuestions: Boolean = false,
    @ColumnInfo(name = "status", defaultValue = "'DRAFT'") val status: String = "DRAFT",
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
 {
    init {
        require(status in setOf("DRAFT", "ACTIVE", "CLOSED"))
    }
}
