package org.lara.app.data.grading

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.HubFailure
import org.lara.app.data.remote.dto.SubmissionDto
import org.lara.app.data.repository.GradeOutbox
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GradeOutboxTest {
    @Test fun offlineEditSurvivesRetryAndUpdatesTheCachedGradeOnlyOnReceipt(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, LaraDatabase::class.java).build()
        try {
            db.authDao().saveProfile(UserEntity("teacher", fullName = "Teacher", role = "TEACHER", createdAt = 1, updatedAt = 1))
            db.syncStateDao().put(SyncStateEntity("hub_id", "hub", 1))
            db.cacheDao().upsertBatch(CacheBatch(
                classrooms = listOf(ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt = 1, updatedAt = 1)),
                assignments = listOf(AssignmentEntity("assignment", "class", "Homework", dueDate = 100, createdAt = 1, updatedAt = 1)),
            ))
            val submission = AssignmentSubmissionEntity("submission", "assignment", "learner", "",
                submittedAt = 1, updatedAt = 7, syncStatus = "SYNCED")
            db.homeworkDao().save(submission)
            var response: Exception? = IOException("Offline")
            val send: suspend (org.lara.app.data.repository.QueuedGrade) -> SubmissionDto = { grade ->
                assertEquals(7L, grade.expectedUpdatedAt)
                response?.let { throw it }
                SubmissionDto(grade.submissionId, grade.assignmentId, grade.learnerId, "IMAGE",
                    1, grade.score, grade.feedback, updatedAt = 8)
            }
            val original = GradeOutbox(db, send, "teacher") {}
            original.save(submission, 5, "Draft")
            try { original.flush(); fail("Expected a transport retry") } catch (_: IOException) { }
            val reopened = GradeOutbox(db, send, "teacher") {}
            assertEquals(5L, reopened.observe().first().getValue(submission.id).score)
            assertNull(db.syncDao().submission(submission.id)?.score)
            reopened.save(submission, 0, "Revised offline")
            response = HubFailure(409, "GRADE_CHANGED")
            reopened.flush()
            assertTrue(reopened.observe().first().getValue(submission.id).rejected)
            response = null
            reopened.save(submission, 0, "Revised offline")
            reopened.flush()
            assertTrue(reopened.observe().first().isEmpty())
            assertEquals(0L, db.syncDao().submission(submission.id)?.score)
            assertEquals("Revised offline", db.syncDao().submission(submission.id)?.teacherFeedback)
        } finally { db.close() }
    }
}
