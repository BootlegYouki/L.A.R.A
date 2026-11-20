package org.lara.app.data.grading

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GradingMockTest {
    @Test fun gradeReachesLearnerAndUnrelatedTeacherIsForbidden() = runBlocking {
        assumeTrue(System.getenv("LARA_GRADING_MOCK") == "1")
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", 38080, 38081))
        fun login(id: String): AuthSession = Json { ignoreUnknownKeys = true }.decodeFromString(
            AuthSession.serializer(), HubClient().postAuth(hub, "/api/auth/login", buildJsonObject {
                put("lrn_or_id", id); put("pin", "123456")
            }.toString()))
        fun session(auth: AuthSession): EnrollmentApi {
            val tokens = object : SessionTokenStore {
                override fun load() = StoredToken(auth.token, auth.user.id, auth.expiresAt, hub.restBase)
                override fun save(value: StoredToken) = Unit
                override fun clear() = Unit
            }
            return EnrollmentApi(HubClient(), tokens, { hub })
        }
        val admin = login("ADMIN-0001")
        val otherId = "T-" + UUID.randomUUID().toString().take(8)
        val create = java.net.URL(hub.restBase + "/api/admin/users").openConnection() as java.net.HttpURLConnection
        create.requestMethod = "POST"
        create.doOutput = true
        create.setRequestProperty("Authorization", "Bearer ${admin.token}")
        create.setRequestProperty("Content-Type", "application/json")
        create.outputStream.use { it.write(buildJsonObject { put("lrn_or_id", otherId); put("full_name", "Other teacher"); put("role", "TEACHER"); put("pin", "123456") }.toString().toByteArray()) }
        assertEquals(200, create.responseCode)
        create.disconnect()
        val otherTeacher = login(otherId)
        val teacher = login("T-0001")
        val learner = login("123456789012")
        val teacherSession = session(teacher)
        val learnerSession = session(learner)
        val classroom = teacherSession.classrooms(teacher.user.id).first()
        val assignment = ClassworkAuthoringApi(teacherSession).mutate(teacher.user.id, "assignments", null, "POST", buildJsonObject {
            put("classroom_id", classroom.id); put("title", "Review test"); put("instructions", "Read")
            put("max_points", 25); put("due_date", System.currentTimeMillis() + 86400000); put("allow_late", true)
        }).let { Json.parseToJsonElement(it).jsonObject.getValue("id").jsonPrimitive.content }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, LaraDatabase::class.java).build()
        val file = File.createTempFile("review-test", ".jpg", app.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val downloaded = File.createTempFile("review-download", ".jpg", app.cacheDir)
        try {
            db.authDao().saveProfile(UserEntity(learner.user.id, learner.user.lrnOrId, "Synthetic learner", "STUDENT", 1, 1))
            val sync = SyncRepository(db, HubSyncTransport(learnerSession, HubClient()))
            sync.pull(learner.user.id)
            val id = UUID.randomUUID().toString()
            HomeworkApi(learnerSession).upload(learner.user.id, assignment, id, file, System.currentTimeMillis())
            val api = GradingApi(teacherSession)
            assertEquals(id, api.list(teacher.user.id, assignment).single().id)
            api.file(teacher.user.id, id, downloaded)
            assertArrayEquals(file.readBytes(), downloaded.readBytes())
            val receipt = api.grade(teacher.user.id, id, 20, "Great effort!")
            assertEquals(20L, receipt.score)
            sync.pull(learner.user.id)
            val cached = db.homeworkDao().observe(assignment, learner.user.id).first()
            assertEquals(20L, cached?.score)
            assertEquals("Great effort!", cached?.teacherFeedback)
            try {
                GradingApi(session(otherTeacher)).list(otherTeacher.user.id, assignment)
                fail("Expected 403")
            } catch (error: HubFailure) { assertEquals(403, error.status) }
        } finally { file.delete(); downloaded.delete(); db.close() }
    }
}
