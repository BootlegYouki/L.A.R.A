package org.lara.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import org.lara.app.data.repository.HomeworkRepository
import org.lara.app.data.sync.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HomeworkMockTest {
    @Test fun queuedPhotoReachesTheMockAndSurvivesASubsequentPull(): Unit = runBlocking {
        assumeTrue(System.getenv("LARA_SYNC_MOCK") == "1")
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", 18080, 18081))
        fun api(session: AuthSession): EnrollmentApi {
            val tokens = object : SessionTokenStore {
                override fun load() = StoredToken(session.token, session.user.id, session.expiresAt, hub.restBase)
                override fun save(value: StoredToken) = Unit
                override fun clear() = Unit
            }
            return EnrollmentApi(HubClient(), tokens, { hub })
        }
        val teacher = AuthApi().authenticate(hub, "T-0001", "1234", null)
        val classroom = TeacherClassApi(teacher.user.id, api(teacher), HubClient()).create("Synthetic homework", "Test")
        val learner = AuthApi().authenticate(hub, "7" + System.nanoTime().toString().takeLast(11).padStart(11, '0'), "1234", "Synthetic homework learner")
        val learnerApi = api(learner)
        learnerApi.join(learner.user.id, classroom.classCode)
        HubClassManagementRepository(teacher.user.id, api(teacher), HubClient()).decide(classroom.id, learner.user.id, true)
        val request = Request.Builder().url(hub.restBase + "/api/assignments")
            .header("Authorization", "Bearer ${teacher.token}")
            .post("""{"classroom_id":"${classroom.id}","title":"Notebook","instructions":"Photograph the page","due_date":4102444800000,"max_points":100}""".toRequestBody("application/json".toMediaType())).build()
        val assignmentId = OkHttpClient().newCall(request).execute().use {
            assertEquals(200, it.code)
            Json.parseToJsonElement(requireNotNull(it.body).string()).jsonObject.getValue("id").jsonPrimitive.content
        }
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, LaraDatabase::class.java).build()
        val root = File(context.cacheDir, "mock-homework-${System.nanoTime()}").apply { mkdirs() }
        try {
            db.authDao().saveProfile(UserEntity(learner.user.id, learner.user.lrnOrId, learner.user.fullName, "STUDENT", 1, 1))
            val sync = SyncRepository(db, HubSyncTransport(learnerApi, HubClient()))
            sync.pull(learner.user.id)
            val upload = HomeworkApi(learnerApi)
            val repository = HomeworkRepository(db, root, learner.user.id,
                { assignment, id, file, turnedInAt -> upload.upload(learner.user.id, assignment, id, file, turnedInAt) }, {})
            val file = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())) }
            repository.turnIn(assignmentId, file)
            repository.uploadQueued()
            sync.pull(learner.user.id)
            val saved = requireNotNull(db.homeworkDao().find(assignmentId, learner.user.id))
            assertEquals("SYNCED", saved.syncStatus)
            assertEquals(file.path, saved.filePath)
            assertTrue(file.isFile)
            assertNotNull(saved.receivedAt)
            fun patch(path: String, body: String) {
                val edit = Request.Builder().url(hub.restBase + path)
                    .header("Authorization", "Bearer ${teacher.token}")
                    .patch(body.toRequestBody("application/json".toMediaType())).build()
                OkHttpClient().newCall(edit).execute().use { assertEquals(200, it.code) }
            }
            val deadline = saved.submittedAt + 1
            patch("/api/assignments/$assignmentId", """{"due_date":$deadline,"allow_late":false}""")
            fun replacement(marker: Byte) = File.createTempFile("replacement-", ".jpg", root).apply {
                writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), marker, 0xff.toByte(), 0xd9.toByte()))
            }
            repository.turnIn(assignmentId, replacement(1))
            val offline = requireNotNull(db.homeworkDao().find(assignmentId, learner.user.id))
            db.homeworkDao().save(offline.copy(submittedAt = deadline - 1))
            repository.uploadQueued()
            val received = requireNotNull(db.homeworkDao().find(assignmentId, learner.user.id))
            assertEquals("SYNCED", received.syncStatus)
            assertEquals(deadline - 1, received.submittedAt)
            assertTrue(requireNotNull(received.receivedAt) > deadline)
            repository.turnIn(assignmentId, replacement(2))
            repository.uploadQueued()
            assertEquals("LATE_SUBMISSION", db.homeworkDao().find(assignmentId, learner.user.id)?.syncRejectReason)
            patch("/api/submissions/${received.id}", """{"score":90,"teacher_feedback":"Synthetic feedback"}""")
            repository.turnIn(assignmentId, replacement(3))
            repository.uploadQueued()
            val rejected = requireNotNull(db.homeworkDao().find(assignmentId, learner.user.id))
            assertEquals("REJECTED", rejected.syncStatus)
            assertEquals("ALREADY_GRADED", rejected.syncRejectReason)
            assertTrue(File(rejected.filePath).isFile)
            repository.uploadQueued()
            assertEquals(rejected, db.homeworkDao().find(assignmentId, learner.user.id))
        } finally { db.close(); root.deleteRecursively() }
    }
}
