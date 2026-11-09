package org.lara.app.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.*
import org.lara.app.data.sync.*

abstract class SettingsBehaviorTests {
    private lateinit var db: LaraDatabase
    private val hub = requireNotNull(HubEndpoint.lan("127.0.0.1"))
    private var saved: StoredToken? = null
    private var calls = 0
    private val tokens = object : SessionTokenStore {
        override fun load() = saved
        override fun save(value: StoredToken) { saved = value }
        override fun clear() { saved = null }
    }
    private val transport = object : SyncTransport {
        override suspend fun pull(userId: String, request: org.lara.app.data.remote.dto.SyncPullRequestDto): org.lara.app.data.remote.dto.SyncPullResponseDto = error("Unexpected sync")
        override suspend fun push(userId: String, request: org.lara.app.data.remote.dto.SyncPushRequestDto): org.lara.app.data.remote.dto.SyncPushResponseDto = error("Unexpected sync")
    }
    @Before fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LaraDatabase::class.java).build()
        db.authDao().saveProfile(UserEntity("learner", "synthetic", "Learner", "STUDENT", 1, 1))
        db.cacheDao().upsertBatch(CacheBatch(
            classrooms = listOf(ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt=1, updatedAt=1)),
            announcements = listOf(AnnouncementEntity("post", "class", "Lesson", "Read", createdAt=1, updatedAt=1)),
            assignments = listOf(AssignmentEntity("assignment", "class", "Work", dueDate=10, createdAt=1, updatedAt=1)),
            quizzes = listOf(QuizEntity("quiz", "class", "Quiz", timeLimitMinutes=5, createdAt=1, updatedAt=1)),
            syncState = listOf(SyncStateEntity("cursor", "42", 1))))
        saved = StoredToken("synthetic-token", "learner", Long.MAX_VALUE, hub.restBase)
        calls = 0
    }
    @After fun close() { db.close() }
    private suspend fun queue() = db.cacheDao().upsertBatch(CacheBatch(
        announcementComments = listOf(AnnouncementCommentEntity("comment", "post", "learner", "Question", 1, 1, "QUEUED_FOR_SYNC"),
            AnnouncementCommentEntity("foreign-comment", "post", "another", "Other work", 1, 1, "QUEUED_FOR_SYNC")),
        privateComments = listOf(PrivateCommentEntity("private", "assignment", "learner", "learner", "Question", 1, 1, "QUEUED_FOR_SYNC")),
        assignmentSubmissions = listOf(AssignmentSubmissionEntity("photo", "assignment", "learner", "/local/retained.jpg", submittedAt=1, updatedAt=1)),
        quizAttempts = listOf(QuizAttemptEntity("attempt", "quiz", "learner", "SUBMITTED", 1, 2, updatedAt=2))))
    private fun repository(fail: Boolean = false, endpoint: HubEndpoint? = hub) = SettingsRepository(
        db.authDao(), tokens, SyncRepository(db, transport), { endpoint }) { actual, token ->
        calls++
        assertEquals(hub.restBase, actual.restBase)
        assertEquals("learner", token.userId)
        assertNull(saved)
        if (fail) throw java.io.IOException("Offline")
    }
    @Test fun allFourOwnQueueTypesRequireConfirmationWithoutClearingAnything(): Unit = runBlocking {
        queue()
        assertEquals(SignOutResult.NeedsConfirmation(4), repository().signOut("learner"))
        assertNotNull(saved); assertEquals(0, calls)
        assertEquals(4, db.authDao().queuedCount("learner"))
    }
    @Test fun confirmedOfflineSignOutKeepsQueueFilesProfileAndCursor(): Unit = runBlocking {
        queue()
        assertEquals(SignOutResult.SignedOut, repository(fail=true).signOut("learner", 4))
        assertNull(saved); assertEquals(1, calls)
        assertEquals(4, db.authDao().queuedCount("learner"))
        assertEquals("/local/retained.jpg", db.syncDao().submission("photo")?.filePath)
        assertEquals("learner", db.authDao().currentUserId())
        assertNotNull(db.authDao().user("learner"))
        assertEquals("42", db.syncStateDao().get("cursor")?.value)
        assertEquals(1, db.syncDao().queuedComments("another").size)
    }
    @Test fun newQueuedItemsRequireANewConfirmation(): Unit = runBlocking {
        queue()
        assertEquals(SignOutResult.NeedsConfirmation(4), repository().signOut("learner", 3))
        assertNotNull(saved); assertEquals(0, calls)
    }
    @Test fun emptyQueueSignsOutAndWrongHubNeverReceivesToken(): Unit = runBlocking {
        assertEquals(SignOutResult.SignedOut, repository(endpoint=requireNotNull(HubEndpoint.lan("127.0.0.2"))).signOut("learner"))
        assertNull(saved); assertEquals(0, calls)
    }
}
