package org.lara.app.data.grading

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.repository.observeGradebook
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ClassGradebookTest {
    @Test fun combinesSyncedAssignmentPointsAndClosedQuizReceiptsForActiveLearners(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, LaraDatabase::class.java).build()
        try {
            db.authDao().saveProfile(UserEntity("teacher", fullName = "Teacher", role = "TEACHER", createdAt = 1, updatedAt = 1))
            val learner = UserEntity("learner", fullName = "Learner", role = "STUDENT", createdAt = 1, updatedAt = 1)
            db.cacheDao().upsertBatch(CacheBatch(
                users = listOf(learner, learner.copy(id = "pending")),
                classrooms = listOf(ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt = 1, updatedAt = 1)),
                enrollments = listOf(
                    EnrollmentEntity("active", "class", learner.id, "ACTIVE", 1, 1),
                    EnrollmentEntity("pending", "class", "pending", "PENDING", 1, 1),
                ),
                assignments = listOf(AssignmentEntity("assignment", "class", "Homework", dueDate = 100, createdAt = 1, updatedAt = 1)),
                quizzes = listOf(
                    QuizEntity("quiz", "class", "Closed quiz", timeLimitMinutes = 10, status = "CLOSED", createdAt = 1, updatedAt = 1),
                    QuizEntity("draft", "class", "Draft quiz", timeLimitMinutes = 10, createdAt = 1, updatedAt = 1),
                ),
            ))
            db.homeworkDao().save(AssignmentSubmissionEntity("submission", "assignment", learner.id, "",
                submittedAt = 1, updatedAt = 7, score = 0, syncStatus = "SYNCED"))
            db.syncStateDao().put(SyncStateEntity("grade_receipt:attempt",
                """{"attempt_id":"attempt","quiz_id":"quiz","student_id":"learner","score":7,"total_points":10,"submitted_at":1}""", 1))
            db.syncStateDao().put(SyncStateEntity("grade_receipt:older",
                """{"attempt_id":"older","score":3,"total_points":4,"submitted_at":1}""", 1))
            val book = observeGradebook(db, "class").first()
            assertEquals(listOf("assignment", "quiz"), book.work.map { it.id })
            assertEquals(10L, book.work.last().points)
            assertEquals(learner.id, book.learners.single().id)
            assertEquals(mapOf("assignment" to 0L, "quiz" to 7L), book.learners.single().scores)
        } finally {
            db.close()
        }
    }
}
