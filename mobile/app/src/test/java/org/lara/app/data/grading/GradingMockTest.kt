package org.lara.app.data.grading

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
            AuthSession.serializer(), HubClient().postAuth(hub, buildJsonObject {
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
        val file = File.createTempFile("review-test", ".jpg", app.cacheDir).apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 1, 2, 3)) }
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
            val edited = api.grade(teacher.user.id, id, 0, "Revised", requireNotNull(receipt.updatedAt))
            assertEquals(0L, edited.score)
            val retry = api.grade(teacher.user.id, id, 0, "Revised", requireNotNull(receipt.updatedAt))
            assertEquals(edited.updatedAt, retry.updatedAt)
            sync.pull(learner.user.id)
            assertEquals(0L, db.homeworkDao().observe(assignment, learner.user.id).first()?.score)
            try {
                api.grade(teacher.user.id, id, 5, "Stale edit", requireNotNull(receipt.updatedAt))
                fail("Expected stale grade rejection")
            } catch (error: HubFailure) { assertEquals(409, error.status) }
            try {
                GradingApi(session(otherTeacher)).list(otherTeacher.user.id, assignment)
                fail("Expected 403")
            } catch (error: HubFailure) { assertEquals(403, error.status) }
            try {
                GradingApi(session(otherTeacher)).file(otherTeacher.user.id, id, downloaded)
                fail("Expected file access to be forbidden")
            } catch (error: HubFailure) { assertEquals(403, error.status) }
            for (extension in listOf("pdf", "docx")) {
                val document = File.createTempFile("review-document", ".$extension", app.cacheDir)
                try {
                    if (extension == "pdf") document.writeText("%PDF-1.4\nsynthetic")
                    else ZipOutputStream(document.outputStream()).use { zip ->
                        listOf("[Content_Types].xml", "word/document.xml").forEach { name ->
                            zip.putNextEntry(ZipEntry(name)); zip.write("synthetic".toByteArray()); zip.closeEntry()
                        }
                    }
                    val docAssignment = ClassworkAuthoringApi(teacherSession).mutate(teacher.user.id, "assignments", null, "POST", buildJsonObject {
                        put("classroom_id", classroom.id); put("title", "Document test"); put("instructions", "Read")
                        put("max_points", 25); put("due_date", System.currentTimeMillis() + 86400000); put("allow_late", true)
                    }).let { Json.parseToJsonElement(it).jsonObject.getValue("id").jsonPrimitive.content }
                    val documentId = UUID.randomUUID().toString()
                    val saved = HomeworkApi(learnerSession).upload(learner.user.id, docAssignment, documentId, document, System.currentTimeMillis())
                    assertEquals("DOCUMENT", saved.fileType)
                    assertEquals("DOCUMENT", api.list(teacher.user.id, docAssignment).single().fileType)
                    api.file(teacher.user.id, documentId, downloaded)
                    assertArrayEquals(document.readBytes(), downloaded.readBytes())
                    api.grade(teacher.user.id, documentId, 17, "Good work")
                    sync.pull(learner.user.id)
                    val synced = db.homeworkDao().observe(docAssignment, learner.user.id).first()
                    assertEquals(17L, synced?.score)
                    assertEquals("Good work", synced?.teacherFeedback)
                } finally { document.delete() }
            }
        } finally { file.delete(); downloaded.delete(); db.close() }
    }
}
