package org.lara.app.sync

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.dto.*
import org.lara.app.data.sync.*

/** Same transaction/queue suite runs under Robolectric and Android instrumentation. */
abstract class SyncBehaviorTests {
    protected lateinit var db: LaraDatabase
    protected val classroom = ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt=1, updatedAt=1)
    protected val post = AnnouncementEntity("post", "class", "Lesson", "Read", createdAt=1, updatedAt=1)
    @Before fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LaraDatabase::class.java).build()
        db.authDao().saveProfile(UserEntity("learner", "synthetic", "Test", "STUDENT", 1, 1))
        db.cacheDao().upsertBatch(CacheBatch(classrooms=listOf(classroom), announcements=listOf(post), syncState=listOf(
            SyncStateEntity("hub_id", "hub", 1), SyncStateEntity("sync_epoch", "1", 1), SyncStateEntity("cursor", "0", 1))))
    }
    @After fun close() { db.close() }
    private fun page(cursor: Long = 1, more: Boolean = false) = SyncPullResponseDto(100, "hub", 1, cursor, more, false)
    private suspend fun queue(id: String = "comment") = db.cacheDao().upsertBatch(CacheBatch(announcementComments=listOf(
        AnnouncementCommentEntity(id, "post", "learner", "Local work", 2, 2, "QUEUED_FOR_SYNC"))))
    private suspend fun cursor() = db.syncStateDao().get("cursor")?.value
    private fun transport(pull: suspend (SyncPullRequestDto) -> SyncPullResponseDto = { page() },
        push: suspend (SyncPushRequestDto) -> SyncPushResponseDto = { SyncPushResponseDto(true, emptyList()) }) = object : SyncTransport {
        override suspend fun pull(userId: String, request: SyncPullRequestDto) = pull(request)
        override suspend fun push(userId: String, request: SyncPushRequestDto) = push(request)
    }
    @Test fun pageUsersRecordsAndCursorCommitAtomicallyAndLearnerLrnsAreStripped(): Unit = runBlocking {
        val store = SyncStore(db)
        store.apply("learner", store.request("learner"), page().copy(
            users=listOf(PublicUserDto("peer", "Peer", "STUDENT", "private-not-allowed")),
            enrollments=listOf(EnrollmentDto("server-enrollment", "class", "learner", "ACTIVE", 2, 1)),
            announcements=listOf(AnnouncementDto("new", "class", "New", "Read", 1, 2))))
        assertEquals("1", cursor())
        assertNull(db.syncDao().user("peer")?.lrnOrId)
        assertEquals("server-enrollment", db.rosterDao().observeByClassroom("class").first().single().enrollment.id)
        assertEquals(2, db.streamDao().observeByClassroom("class").first().size)
    }
    @Test fun invalidChildRollsBackEarlierUpsertsAndCursor(): Unit = runBlocking {
        val store = SyncStore(db)
        try {
            store.apply("learner", store.request("learner"), page().copy(
                users=listOf(PublicUserDto("peer", "Peer", "STUDENT")),
                announcements=listOf(AnnouncementDto("bad", "missing-parent", "New", "Read", 1, 2))))
            fail()
        } catch (_: Exception) { }
        assertEquals("0", cursor()); assertNull(db.syncDao().user("peer"))
        assertEquals(listOf("post"), db.streamDao().observeByClassroom("class").first().map { it.id })
    }
    @Test fun droppedResponseDoesNotChangeDatabase(): Unit = runBlocking {
        val repo = SyncRepository(db, transport(pull={ throw java.io.EOFException() }))
        try { repo.pull("learner"); fail() } catch (_: java.io.IOException) { }
        assertEquals("0", cursor())
        assertEquals(listOf("post"), db.streamDao().observeByClassroom("class").first().map { it.id })
    }
    @Test fun hasMoreUsesCommittedSequenceAndNeverServerClock(): Unit = runBlocking {
        val cursors = mutableListOf<Long>()
        val repo = SyncRepository(db, transport(pull={ request ->
            cursors.add(request.cursor)
            page(request.cursor + 1, more=request.cursor == 0L).copy(serverTime=9_999_999)
        }))
        repo.pull("learner")
        assertEquals(listOf(0L, 1L), cursors); assertEquals("2", cursor())
    }
    @Test fun stalePageAndNonAdvancingPaginationAreRejected(): Unit = runBlocking {
        val store = SyncStore(db)
        val request = store.request("learner")
        store.apply("learner", request, page())
        try { store.apply("learner", request, page(2)); fail() } catch (_: IllegalStateException) { }
        try { store.apply("learner", store.request("learner"), page(1, more=true)); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("1", cursor())
    }
    @Test fun resetPreservesAllQueuedWorkAndParentsAndBindsItToOldHub(): Unit = runBlocking {
        queue()
        val assignment = AssignmentEntity("assignment", "class", "Work", dueDate=10, createdAt=1, updatedAt=1)
        val quiz = QuizEntity("quiz", "class", "Quiz", timeLimitMinutes=5, createdAt=1, updatedAt=1)
        db.cacheDao().upsertBatch(CacheBatch(assignments=listOf(assignment), quizzes=listOf(quiz),
            assignmentSubmissions=listOf(AssignmentSubmissionEntity("photo", "assignment", "learner", "/local/photo.jpg", submittedAt=2, updatedAt=2)),
            privateComments=listOf(PrivateCommentEntity("private", "assignment", "learner", "learner", "Question", 2, 2, "QUEUED_FOR_SYNC")),
            quizAttempts=listOf(QuizAttemptEntity("attempt", "quiz", "learner", "SUBMITTED", 1, 2, updatedAt=2)),
            classrooms=listOf(classroom.copy(id="stale-class"))))
        val store = SyncStore(db)
        store.apply("learner", store.request("learner"), page().copy(hubId="new-hub", reset=true))
        assertEquals(1, db.syncDao().queuedComments("learner").size)
        assertEquals(1, db.syncDao().queuedPrivateComments("learner").size)
        assertEquals(1, db.syncDao().queuedAttempts("learner").size)
        assertEquals("/local/photo.jpg", db.syncDao().submission("photo")?.filePath)
        assertNotNull(db.syncDao().classroom("class")); assertNull(db.syncDao().classroom("stale-class"))
        assertEquals("hub", db.syncStateDao().get("queued_hub:announcement_comments:comment")?.value)
        var pushed = false
        val repo = SyncRepository(db, transport(push={ pushed=true; error("Wrong Hub") }))
        assertEquals(0, repo.pushQueued("learner")); assertFalse(pushed)
    }
    @Test fun failedResetRollsBackTheWipeAndQueueBinding(): Unit = runBlocking {
        queue()
        val store = SyncStore(db)
        try {
            store.apply("learner", store.request("learner"), page().copy(hubId="new-hub", reset=true,
                enrollments=listOf(EnrollmentDto("bad", "missing-class", "learner", "ACTIVE", 2))))
            fail()
        } catch (_: Exception) { }
        assertEquals("hub", db.syncStateDao().get("hub_id")?.value)
        assertNull(db.syncStateDao().get("queued_hub:announcement_comments:comment"))
        assertNotNull(db.syncDao().classroom("class")); assertEquals(1, db.syncDao().queuedComments("learner").size)
    }
    @Test fun tombstoneDoesNotCascadeAwayQueuedComments(): Unit = runBlocking {
        queue()
        val store = SyncStore(db)
        store.apply("learner", store.request("learner"), page().copy(deleted=listOf(
            DeletedRecordDto("announcement_comments", "comment"), DeletedRecordDto("announcements", "post"))))
        assertEquals(1, db.syncDao().queuedComments("learner").size)
        assertEquals(1, db.streamDao().observeByClassroom("class").first().size)
    }
    @Test fun syncedCommentTombstoneAndUnsupportedEntityAreHandledWithoutCursorLoss(): Unit = runBlocking {
        val store = SyncStore(db)
        store.apply("learner", store.request("learner"), page().copy(deleted=listOf(DeletedRecordDto("announcements", "post"))))
        assertTrue(db.streamDao().observeByClassroom("class").first().isEmpty())
        try { store.apply("learner", store.request("learner"), page(2).copy(deleted=listOf(DeletedRecordDto("users", "learner")))); fail() }
        catch (_: IllegalStateException) { }
        assertEquals("1", cursor()); assertNotNull(db.syncDao().user("learner"))
    }
    @Test fun onlyReceiptedRowsBecomeSyncedAndRejectionReasonPersists(): Unit = runBlocking {
        queue("accepted"); queue("rejected")
        var calls = 0
        val repo = SyncRepository(db, transport(push={ request ->
            calls++
            assertEquals(setOf("accepted", "rejected"), request.comments.map { it.id }.toSet())
            SyncPushResponseDto(true, listOf(SyncReceiptDto("accepted", "SYNCED"), SyncReceiptDto("rejected", "REJECTED", "COMMENTS_DISABLED")))
        }))
        assertEquals(1, repo.pushQueued("learner"))
        assertEquals(listOf("rejected"), db.syncDao().queuedComments("learner").map { it.id })
        assertEquals("COMMENTS_DISABLED", repo.receipt("announcement_comments", "rejected")?.reason)
        assertEquals(0, repo.pushQueued("learner")); assertEquals(1, calls)
    }
    @Test fun unacknowledgedAndUnknownReceiptsNeverMarkWorkSynced(): Unit = runBlocking {
        queue()
        for (response in listOf(SyncPushResponseDto(false, listOf(SyncReceiptDto("comment","SYNCED"))),
            SyncPushResponseDto(true, listOf(SyncReceiptDto("foreign", "SYNCED"))))) {
            val repo = SyncRepository(db, transport(push={ response }))
            try { repo.pushQueued("learner"); fail() } catch (_: Exception) { }
            assertEquals(1, db.syncDao().queuedComments("learner").size)
        }
    }
    @Test fun oldReceiptCannotAcknowledgeAnEditMadeWhileUploading(): Unit = runBlocking {
        queue()
        var calls = 0
        val repo = SyncRepository(db, transport(push={
            calls++
            if (calls > 1) throw java.io.IOException("Dropped")
            db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE announcement_comments SET content='Edited', updated_at=3 WHERE id='comment'") }
            SyncPushResponseDto(true, listOf(SyncReceiptDto("comment", "SYNCED")))
        }))
        try { repo.pushQueued("learner"); fail() } catch (_: java.io.IOException) { }
        assertEquals("Edited", db.syncDao().queuedComments("learner").single().content)
    }
    @Test fun rejectedFirstPageDoesNotStarveLaterQueuedRows(): Unit = runBlocking {
        repeat(51) { queue("comment-${it.toString().padStart(3, '0')}") }
        var calls = 0
        val repo = SyncRepository(db, transport(push={ request ->
            calls++
            SyncPushResponseDto(true, request.comments.map { SyncReceiptDto(it.id, if(it.id.endsWith("050")) "SYNCED" else "REJECTED", "FORBIDDEN") })
        }))
        assertEquals(1, repo.pushQueued("learner")); assertEquals(2, calls)
        assertEquals(50, db.syncDao().queuedComments("learner").size)
    }
    @Test fun foreignOwnersAndUnfinishedQuizAttemptsAreNeverUploaded(): Unit = runBlocking {
        db.cacheDao().upsertBatch(CacheBatch(announcementComments=listOf(
            AnnouncementCommentEntity("foreign", "post", "other", "Private", 1, 1, "QUEUED_FOR_SYNC")),
            quizzes=listOf(QuizEntity("quiz", "class", "Quiz", timeLimitMinutes=5, createdAt=1, updatedAt=1)),
            quizAttempts=listOf(QuizAttemptEntity("unfinished", "quiz", "learner", startedAt=1, updatedAt=1))))
        val repo = SyncRepository(db, transport(push={ error("Must not upload") }))
        assertEquals(0, repo.pushQueued("learner"))
    }
    @Test fun deferredTombstoneRunsAfterTheQueuedCommentIsAcknowledged(): Unit = runBlocking {
        queue()
        val store = SyncStore(db)
        store.apply("learner", store.request("learner"), page().copy(deleted=listOf(DeletedRecordDto("announcements", "post"))))
        assertNotNull(db.syncStateDao().get("deferred_tombstone:announcements:post"))
        val repo = SyncRepository(db, transport(pull={ page(2) }, push={
            SyncPushResponseDto(true, listOf(SyncReceiptDto("comment", "SYNCED")))
        }))
        repo.pushQueued("learner"); repo.pull("learner")
        assertTrue(db.streamDao().observeByClassroom("class").first().isEmpty())
        assertNull(db.syncStateDao().get("deferred_tombstone:announcements:post"))
    }
    @Test fun newlyApprovedClassRescansOlderRecordsWithoutWipingQueuedWork(): Unit = runBlocking {
        queue()
        db.syncStateDao().put(SyncStateEntity("cursor", "10", 1))
        val requests = mutableListOf<Long>()
        val repo = SyncRepository(db, transport(pull={ request ->
            requests.add(request.cursor)
            val response = page(11).copy(enrollments=listOf(EnrollmentDto("new-enrollment", "new-class", "learner", "ACTIVE", 3, 2)))
            if (request.cursor == 0L) response.copy(classrooms=listOf(ClassroomDto("new-class", "New class", "A", "K7M4QX", "teacher")),
                announcements=listOf(AnnouncementDto("older-post", "new-class", "Earlier lesson", "Read", 1, 1))) else response
        }))
        repo.pull("learner")
        assertEquals(listOf(10L, 0L), requests)
        assertEquals("11", cursor())
        assertEquals("Earlier lesson", db.streamDao().observeByClassroom("new-class").first().single().title)
        assertEquals(1, db.syncDao().queuedComments("learner").size)
    }
    @Test fun officialSubmissionGradesDoNotOverwriteQueuedPhotoOrItsLocalPath(): Unit = runBlocking {
        db.cacheDao().upsertBatch(CacheBatch(assignments=listOf(AssignmentEntity("assignment", "class", "Work", dueDate=10, createdAt=1, updatedAt=1)),
            assignmentSubmissions=listOf(AssignmentSubmissionEntity("photo", "assignment", "learner", "/local/work.jpg", submittedAt=2, updatedAt=2))))
        val store = SyncStore(db)
        store.apply("learner", store.request("learner"), page().copy(submissions=listOf(SubmissionDto("photo", "assignment", "learner", "IMAGE", 2, 8, "Well done"))))
        val saved = requireNotNull(db.syncDao().submission("photo"))
        assertEquals("/local/work.jpg", saved.filePath)
        assertEquals("QUEUED_FOR_SYNC", saved.syncStatus)
        assertEquals(8L, saved.score)
        assertEquals("Well done", saved.teacherFeedback)
    }
    @Test fun unknownGradeReceiptIsRetainedWithoutInventingAttemptIdentity(): Unit = runBlocking {
        val store = SyncStore(db)
        store.apply("learner", store.request("learner"), page().copy(quizAttempts=listOf(GradeReceiptDto("unknown", null, 10, 2))))
        assertNull(db.syncDao().attempt("unknown"))
        assertNotNull(db.syncStateDao().get("grade_receipt:unknown"))
        assertEquals("1", cursor())
    }
}
