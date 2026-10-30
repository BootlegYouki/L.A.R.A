package org.lara.app.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.lara.app.data.local.entities.*

@Dao
interface QuizDao {
    @Query("SELECT * FROM quizzes WHERE classroom_id = :classroomId ORDER BY created_at DESC, id")
    fun observeByClassroom(classroomId: String): Flow<List<QuizEntity>>

    @Query("SELECT * FROM quiz_questions WHERE quiz_id = :quizId ORDER BY order_index, id")
    fun observeQuestions(quizId: String): Flow<List<QuizQuestionEntity>>

    @Query("SELECT * FROM quiz_attempts WHERE quiz_id = :quizId AND student_id = :studentId")
    fun observeAttempt(quizId: String, studentId: String): Flow<QuizAttemptEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM quiz_attempts WHERE student_id = :studentId AND status = 'IN_PROGRESS')")
    fun hasInProgressAttempt(studentId: String): Flow<Boolean>
}
