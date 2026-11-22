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
import org.lara.app.data.remote.EnrollmentApi
import org.lara.app.data.remote.HubClient
import org.lara.app.data.remote.PrivateCommentApi
import org.lara.app.data.remote.SessionTokenStore
import org.lara.app.data.remote.StoredToken
import org.lara.app.data.repository.TeacherPrivateCommentRepository
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TeacherPrivateCommentReadTest {
    @Test fun readMarksAffectOnlyTheRenderedLearnerMessagesAndCurrentHub(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val tokens = object : SessionTokenStore {
            override fun load(): StoredToken? = null
            override fun save(value: StoredToken) = Unit
            override fun clear() = Unit
        }
        val db = Room.inMemoryDatabaseBuilder(app, LaraDatabase::class.java).build()
        try {
            db.authDao().saveProfile(UserEntity("teacher", fullName = "Teacher", role = "TEACHER", createdAt = 1, updatedAt = 1))
            db.cacheDao().upsertBatch(CacheBatch(
                classrooms = listOf(ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt = 1, updatedAt = 1)),
                enrollments = listOf(
                    EnrollmentEntity("active-a", "class", "learner-a", "ACTIVE", 1, 1),
                    EnrollmentEntity("active-b", "class", "learner-b", "ACTIVE", 1, 1),
                ),
                assignments = listOf(AssignmentEntity("assignment", "class", "Homework", dueDate = 100, createdAt = 1, updatedAt = 1)),
                privateComments = listOf(
                    PrivateCommentEntity("seen", "assignment", "learner-a", "learner-a", "Question", 4, 4),
                    PrivateCommentEntity("reply", "assignment", "learner-a", "teacher", "Reply", 5, 5),
                    PrivateCommentEntity("other", "assignment", "learner-b", "learner-b", "Question", 5, 5),
                ),
                syncState = listOf(SyncStateEntity("hub_id", "hub", 1)),
            ))
            val repository = TeacherPrivateCommentRepository(db, "teacher", PrivateCommentApi(EnrollmentApi(HubClient(), tokens, { null })))
            val unread = repository.unreadThreads("class")
            assertEquals(mapOf("learner-a" to 1, "learner-b" to 1), unread.first().associate { it.studentId to it.count })
            repository.setAccessDenied("assignment", "learner-a", true)
            assertEquals(true, repository.accessDenied("assignment", "learner-a").first())
            repository.markRead("assignment", "learner-a", listOf("seen"))
            assertEquals(2, unread.first().size)
            repository.setAccessDenied("assignment", "learner-a", false)
            repository.markRead("assignment", "learner-a", listOf("seen", "reply", "other"))
            assertEquals("learner-b", unread.first().single().studentId)
            db.streamDao().insertPrivateComment(PrivateCommentEntity(
                "late-arrival", "assignment", "learner-a", "learner-a", "Earlier question", 3, 3,
            ))
            assertEquals(mapOf("learner-a" to 1, "learner-b" to 1), unread.first().associate { it.studentId to it.count })
            db.syncStateDao().put(SyncStateEntity("hub_id", "other-hub", 2))
            assertEquals(2, unread.first().first { it.studentId == "learner-a" }.count)
        } finally {
            db.close()
        }
    }
}
