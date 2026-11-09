package org.lara.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "quiz_questions",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = QuizEntity::class, parentColumns = ["id"], childColumns = ["quiz_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["quiz_id", "order_index"], name = "index_quiz_questions_quiz_id_order_index", unique = true, orders = [Index.Order.ASC, Index.Order.ASC])
    ],
)
data class QuizQuestionEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "quiz_id") val quizId: String,
    @ColumnInfo(name = "order_index") val orderIndex: Long,
    @ColumnInfo(name = "question_text") val questionText: String,
    @ColumnInfo(name = "question_type") val questionType: String,
    @ColumnInfo(name = "options_json") val optionsJson: String? = null,
    @ColumnInfo(name = "points", defaultValue = "1") val points: Long = 1L,
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
 {
    init {
        require(questionType in setOf("MULTIPLE_CHOICE", "TRUE_FALSE", "IDENTIFICATION"))
    }
}
