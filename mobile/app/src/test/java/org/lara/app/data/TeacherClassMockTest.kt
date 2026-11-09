package org.lara.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.UserEntity
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.sync.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TeacherClassMockTest {
    @Test fun createdClassCanBeJoinedAndRemovalRevokesLearnerAfterSync(): Unit = runBlocking {
        assumeTrue(System.getenv("LARA_APPROVAL_MOCK") == "1")
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", 28080, 28081))
        fun tokens(session: AuthSession) = object : SessionTokenStore {
            private var value: StoredToken? = StoredToken(session.token, session.user.id, session.expiresAt, hub.restBase)
            override fun load() = value
            override fun save(value: StoredToken) { this.value = value }
            override fun clear() { value = null }
        }
        val teacher = AuthApi().authenticate(hub, "T-0001", "1234", null)
        val teacherSessions = EnrollmentApi(HubClient(), tokens(teacher), { hub })
        val commands = TeacherClassApi(teacher.user.id, teacherSessions, HubClient())
        val classroom = commands.create("Synthetic mobile class", "Test")
        val learner = AuthApi().authenticate(hub, "8" + System.nanoTime().toString().takeLast(11).padStart(11, '0'), "1234", "Synthetic class learner")
        val learnerSessions = EnrollmentApi(HubClient(), tokens(learner), { hub })
        assertEquals(classroom.id, learnerSessions.join(learner.user.id, classroom.classCode).classroomId)
        HubClassManagementRepository(teacher.user.id, teacherSessions, HubClient()).decide(classroom.id, learner.user.id, true)
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LaraDatabase::class.java).build()
        try {
            db.authDao().saveProfile(UserEntity(learner.user.id, learner.user.lrnOrId, learner.user.fullName, "STUDENT", 1, 1))
            val sync = SyncRepository(db, HubSyncTransport(learnerSessions, HubClient()))
            sync.pull(learner.user.id)
            assertTrue(db.classroomDao().observeAll().first().any { it.id == classroom.id })
            commands.removeLearner(classroom.id, learner.user.id)
            sync.pull(learner.user.id)
            assertFalse(learnerSessions.classrooms(learner.user.id).any { it.id == classroom.id })
            val cached = db.rosterDao().savedEnrollment(classroom.id, learner.user.id)
            assertTrue(cached == null || cached.status == "REMOVED")
        } finally { db.close() }
    }
}
