package org.lara.app.data.authoring

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.UserEntity
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.sync.*
import org.lara.app.data.repository.ClassworkAuthoringRepository
import org.lara.app.domain.AssignmentDraft

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ClassworkMockTest {
    @Test fun createEditDeleteAndTopicRemovalReachLearnerInOnePullEach(): Unit = runBlocking {
        assumeTrue(System.getenv("LARA_AUTHORING_MOCK") == "1")
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", 38080, 38081))
        fun login(id: String): AuthSession {
            // Transport fixture bypasses the unrelated four-digit AuthApi validator. Mock is unchanged.
            val response = HubClient().postAuth(hub, "/api/auth/login", buildJsonObject {
                put("lrn_or_id", id); put("pin", "123456")
            }.toString())
            return Json { ignoreUnknownKeys = true }.decodeFromString(AuthSession.serializer(), response)
        }
        fun sessions(session: AuthSession): EnrollmentApi {
            val tokens = object : SessionTokenStore {
                override fun load() = StoredToken(session.token, session.user.id, session.expiresAt, hub.restBase)
                override fun save(value: StoredToken) = Unit
                override fun clear() = Unit
            }
            return EnrollmentApi(HubClient(), tokens, { hub })
        }
        val teacher = login("T-0001")
        val learner = login("123456789012")
        val teacherApi = sessions(teacher)
        val learnerApi = sessions(learner)
        val classroom = teacherApi.classrooms(teacher.user.id).first()
        val app = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        val db = Room.inMemoryDatabaseBuilder(app, LaraDatabase::class.java).build()
        try {
            db.authDao().saveProfile(UserEntity(learner.user.id, learner.user.lrnOrId, "Synthetic learner", "STUDENT", 1, 1))
            val sync = SyncRepository(db, HubSyncTransport(learnerApi, HubClient()))
            val api = ClassworkAuthoringApi(teacherApi)
            val repository = ClassworkAuthoringRepository(app, teacher.user.id, teacherApi, api) {}
            repository.saveTopic(classroom.id, null, "Authoring test topic")
            sync.pull(learner.user.id)
            val topic = db.classworkDao().observeTopics(classroom.id).first().first { it.name == "Authoring test topic" }
            val draft = AssignmentDraft("Phone authoring", "Read the lesson", 25, System.currentTimeMillis() + 86_400_000, true, topic.id)
            val id = repository.saveAssignment(classroom.id, null, draft)
            sync.pull(learner.user.id)
            assertEquals("Phone authoring", db.classworkDao().observeAssignments(classroom.id).first().first { it.id == id }.title)
            repository.saveAssignment(classroom.id, id, draft.copy(title = "Edited title"))
            sync.pull(learner.user.id)
            assertEquals("Edited title", db.classworkDao().observeAssignments(classroom.id).first().first { it.id == id }.title)
            repository.delete("topics", topic.id)
            sync.pull(learner.user.id)
            assertNull(db.classworkDao().observeAssignments(classroom.id).first().first { it.id == id }.topicId)
            assertFalse(db.classworkDao().observeTopics(classroom.id).first().any { it.id == topic.id })
            repository.delete("assignments", id)
            sync.pull(learner.user.id)
            assertFalse(db.classworkDao().observeAssignments(classroom.id).first().any { it.id == id })
        } finally { db.close() }
    }
}
