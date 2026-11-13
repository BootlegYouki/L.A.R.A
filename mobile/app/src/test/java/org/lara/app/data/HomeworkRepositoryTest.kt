package org.lara.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.HubFailure
import org.lara.app.data.remote.dto.SubmissionDto
import org.lara.app.data.repository.HomeworkRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HomeworkRepositoryTest {
    private lateinit var db: LaraDatabase
    private lateinit var root: File
    private lateinit var repository: HomeworkRepository
    private var queued = 0
    private var calls = mutableListOf<String>()
    private val sentTimes = mutableListOf<Long>()
    private var failure: Exception? = null
    private var canonicalId: String? = null

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, LaraDatabase::class.java).build()
        root = File(context.cacheDir, "homework-${System.nanoTime()}").apply { mkdirs() }
        db.authDao().saveProfile(UserEntity("learner", fullName = "Synthetic learner", role = "STUDENT", createdAt = 1, updatedAt = 1))
        db.cacheDao().upsertBatch(CacheBatch(
            classrooms = listOf(ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt = 1, updatedAt = 1)),
            enrollments = listOf(EnrollmentEntity("enrollment", "class", "learner", "ACTIVE", 1, 1)),
            assignments = listOf(AssignmentEntity("assignment", "class", "Notebook", dueDate = Long.MAX_VALUE, createdAt = 1, updatedAt = 1))))
        db.syncStateDao().put(SyncStateEntity("hub_id", "hub-a", 1))
        repository = HomeworkRepository(db, root, "learner", { assignment, id, _, turnedInAt ->
            calls.add(id)
            sentTimes.add(turnedInAt)
            failure?.let { throw it }
            SubmissionDto(canonicalId ?: id, assignment, "learner", "IMAGE", 2, updatedAt = 2, receivedAt = 3)
        }, { queued++ })
    }

    @After fun close() { db.close(); root.deleteRecursively() }

    private fun photo() = File.createTempFile("photo-", ".jpg", root).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test fun queuesDurablyAndRetriesWithTheSameId(): Unit = runBlocking {
        val file = photo()
        repository.turnIn("assignment", file)
        val saved = requireNotNull(db.homeworkDao().find("assignment", "learner"))
        assertEquals("QUEUED_FOR_SYNC", saved.syncStatus)
        assertEquals(1, queued)
        failure = IOException("Synthetic dropped connection")
        try { repository.uploadQueued(); fail() } catch (_: IOException) { }
        assertEquals(saved, db.homeworkDao().find("assignment", "learner"))
        assertTrue(file.isFile)
        failure = null
        repository.uploadQueued()
        assertEquals(listOf(saved.id, saved.id), calls)
        assertEquals(listOf(saved.submittedAt, saved.submittedAt), sentTimes)
        assertEquals("SYNCED", db.homeworkDao().find("assignment", "learner")?.syncStatus)
        assertEquals(3L, db.homeworkDao().find("assignment", "learner")?.receivedAt)
    }

    @Test fun lateAndGradedRejectionsKeepWorkWithoutRetrying(): Unit = runBlocking {
        for (reason in listOf("LATE_SUBMISSION", "ALREADY_GRADED")) {
            val file = photo()
            repository.turnIn("assignment", file)
            failure = HubFailure(409, reason)
            repository.uploadQueued()
            val saved = requireNotNull(db.homeworkDao().find("assignment", "learner"))
            assertEquals("REJECTED", saved.syncStatus)
            assertEquals(reason, saved.syncRejectReason)
            assertTrue(file.isFile)
            val uploaded = calls.size
            repository.uploadQueued()
            assertEquals(uploaded, calls.size)
        }
    }

    @Test fun refusesToUploadWorkBoundToAnotherHub(): Unit = runBlocking {
        repository.turnIn("assignment", photo())
        db.syncStateDao().put(SyncStateEntity("hub_id", "hub-b", 2))
        repository.uploadQueued()
        assertTrue(calls.isEmpty())
        assertEquals("QUEUED_FOR_SYNC", db.homeworkDao().find("assignment", "learner")?.syncStatus)
    }

    @Test fun forbiddenReceiptKeepsTheFileAndReason(): Unit = runBlocking {
        val file = photo()
        repository.turnIn("assignment", file)
        failure = HubFailure(403, "FORBIDDEN")
        repository.uploadQueued()
        val saved = requireNotNull(db.homeworkDao().find("assignment", "learner"))
        assertEquals("REJECTED", saved.syncStatus)
        assertEquals("FORBIDDEN", saved.syncRejectReason)
        assertTrue(file.isFile)
    }

    @Test fun resubmissionUsesTheExistingIdentity(): Unit = runBlocking {
        repository.turnIn("assignment", photo())
        val id = db.homeworkDao().find("assignment", "learner")?.id
        val createdAt = db.homeworkDao().find("assignment", "learner")?.createdAt
        repository.uploadQueued()
        val replacement = photo()
        repository.turnIn("assignment", replacement)
        assertEquals(id, db.homeworkDao().find("assignment", "learner")?.id)
        assertEquals(replacement.path, db.homeworkDao().find("assignment", "learner")?.filePath)
        assertEquals(createdAt, db.homeworkDao().find("assignment", "learner")?.createdAt)
    }

    @Test fun pullKeepsQueuedPhotoBeforeAdoptingAnotherDevicesCanonicalId(): Unit = runBlocking {
        val photo = photo()
        repository.turnIn("assignment", photo)
        canonicalId = "canonical"
        db.cacheDao().upsertBatch(CacheBatch(assignmentSubmissions = listOf(
            AssignmentSubmissionEntity("canonical", "assignment", "learner", "", submittedAt = 2, updatedAt = 2, syncStatus = "SYNCED"))))
        assertEquals(photo.path, db.homeworkDao().find("assignment", "learner")?.filePath)
        repository.uploadQueued()
        val saved = requireNotNull(db.homeworkDao().find("assignment", "learner"))
        assertEquals("canonical", saved.id)
        assertEquals("SYNCED", saved.syncStatus)
        assertEquals(photo.path, saved.filePath)
    }

    @Test fun refusesUnownedFilesAndRemovedLearners(): Unit = runBlocking {
        val outside = File.createTempFile("outside-", ".jpg")
        try {
            outside.writeBytes(byteArrayOf(1))
            try { repository.turnIn("assignment", outside); fail() } catch (_: IllegalArgumentException) { }
        } finally { outside.delete() }
        db.rosterDao().updateSavedStatus("class", "learner", "REMOVED")
        try { repository.turnIn("assignment", photo()); fail() } catch (_: IllegalArgumentException) { }
        assertNull(db.homeworkDao().find("assignment", "learner"))
    }

    @Test fun aMissingFileDoesNotBlockOtherQueuedHomework(): Unit = runBlocking {
        repository.turnIn("assignment", photo())
        val first = requireNotNull(db.homeworkDao().find("assignment", "learner"))
        File(first.filePath).delete()
        db.cacheDao().upsertBatch(CacheBatch(assignments = listOf(
            AssignmentEntity("second", "class", "Second page", dueDate = Long.MAX_VALUE, createdAt = 1, updatedAt = 1))))
        repository.turnIn("second", photo())
        try { repository.uploadQueued(); fail() } catch (_: IOException) { }
        assertEquals("QUEUED_FOR_SYNC", db.homeworkDao().find("assignment", "learner")?.syncStatus)
        assertEquals("SYNCED", db.homeworkDao().find("second", "learner")?.syncStatus)
    }

    @Test fun discardingAnOldDraftCannotDeleteAQueuedAttachment(): Unit = runBlocking {
        val queued = photo()
        repository.turnIn("assignment", queued)
        repository.discardDraft(queued.path)
        assertTrue(queued.isFile)
        val unused = photo()
        repository.discardDraft(unused.path)
        assertFalse(unused.exists())
    }
}
