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
import org.lara.app.data.remote.ClassroomSnapshot
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.RoomEnrollmentRepository
import org.lara.app.data.repository.RoomEnrollmentNoticeRepository
import org.lara.app.domain.Cached
import org.lara.app.domain.enrollment.*

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
    @Test fun repeatedPullsKeepLocalCreationTimestamps(): Unit = runBlocking {
        db.cacheDao().upsertBatch(CacheBatch(
            materials = listOf(MaterialEntity("material", "class", "Lesson", "DOCUMENT", fileSizeBytes = 5,
                createdAt = 1, updatedAt = 1)),
            assignments = listOf(AssignmentEntity("assignment", "class", "Work", dueDate = 1000,
                createdAt = 1, updatedAt = 1)),
        ))
        val first = page().copy(
            enrollments = listOf(EnrollmentDto("enrollment", "class", "learner", "ACTIVE", 10, 5)),
            materialChunks = listOf(MaterialChunkDto("chunk", "material", 0, "Synthetic lesson")),
            submissions = listOf(SubmissionDto("submission", "assignment", "learner", "IMAGE", 20)),
        )
        val store = SyncStore(db)
        store.apply("learner", store.request("learner"), first)
        assertEquals(100L, db.syncDao().enrollment("class", "learner")?.createdAt)
        assertEquals(100L, db.syncDao().materialChunk("chunk")?.createdAt)
        assertEquals(100L, db.syncDao().submission("submission")?.createdAt)
        val later = first.copy(serverTime = 500, nextCursor = 2,
            materialChunks = listOf(first.materialChunks.single().copy(text = "Edited lesson")),
            submissions = listOf(first.submissions.single().copy(submittedAt = 30, updatedAt = 500)))
        store.apply("learner", store.request("learner"), later)
        assertEquals(100L, db.syncDao().enrollment("class", "learner")?.createdAt)
        assertEquals(100L, db.syncDao().materialChunk("chunk")?.createdAt)
        assertEquals(100L, db.syncDao().submission("submission")?.createdAt)
        assertEquals("Edited lesson", db.syncDao().materialChunk("chunk")?.text)
        assertEquals(30L, db.syncDao().submission("submission")?.submittedAt)
    }
    private suspend fun queue(id: String = "comment") = db.cacheDao().upsertBatch(CacheBatch(announcementComments=listOf(
        AnnouncementCommentEntity(id, "post", "learner", "Local work", 2, 2, "QUEUED_FOR_SYNC"))))
    private suspend fun cursor() = db.syncStateDao().get("cursor")?.value
    private fun transport(pull: suspend (SyncPullRequestDto) -> SyncPullResponseDto = { page() },
        push: suspend (SyncPushRequestDto) -> SyncPushResponseDto = { SyncPushResponseDto(true, emptyList()) }) = object : SyncTransport {
        override suspend fun pull(userId: String, request: SyncPullRequestDto) = pull(request)
        override suspend fun push(userId: String, request: SyncPushRequestDto) = push(request)
    }
    private suspend fun savedClasses(): List<LearnerClass> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            (RoomEnrollmentRepository(db.rosterDao()).learnerClasses("learner", scope)
                .first { it is Cached.Content } as Cached.Content).value
        } finally { scope.cancel() }
    }
    @Test fun syncApprovalSuppliesCanonicalAccessAndReconcilesPendingNoticeWithoutLosingQueuedWork(): Unit = runBlocking {
        val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1"))
        val notices = RoomEnrollmentNoticeRepository(db)
        val repo = SyncRepository(db, transport(pull = { page().copy(enrollments = listOf(
            EnrollmentDto("server-enrollment", "class", "learner", "ACTIVE", 2, 1))) }))
        repo.refreshClassrooms("learner") { ClassroomSnapshot(endpoint.restBase, listOf(
            ClassroomDto("class", "Science", "A", "K7M4QX", "teacher", enrollmentStatus = "PENDING"))) }
        queue()
        assertTrue(savedClasses().isEmpty())
        repo.pull("learner")
        assertTrue(savedClasses().single().canOpen)
        assertEquals(EnrollmentStatus.ACTIVE, notices.notices("learner").first().single().status)
        assertEquals("server-enrollment", db.syncDao().enrollment("class", "learner")?.id)
        assertEquals(1, db.syncDao().queuedComments("learner").size)
        val store = SyncStore(db)
        store.apply("learner", store.request("learner"), page(2).copy(reset = true))
        assertTrue(savedClasses().isEmpty())
        assertEquals(1, db.syncDao().queuedComments("learner").size)
    }
    @Test fun slowRestSnapshotCannotOverwriteNewerSyncMembership(): Unit = runBlocking {
        val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1"))
        val fetching = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pulled = CompletableDeferred<Unit>()
        val repo = SyncRepository(db, transport(pull = {
            pulled.complete(Unit)
            page().copy(enrollments = listOf(EnrollmentDto("server-enrollment", "class", "learner", "ACTIVE", 2, 1)))
        }))
        val rest = async { repo.refreshClassrooms("learner") {
            fetching.complete(Unit)
            release.await()
            ClassroomSnapshot(endpoint.restBase, listOf(ClassroomDto("class", "Science", "A", "K7M4QX", "teacher", enrollmentStatus = "PENDING")))
        } }
        fetching.await()
        val sync = async(start = CoroutineStart.UNDISPATCHED) { repo.pull("learner") }
        assertFalse(pulled.isCompleted)
        release.complete(Unit)
        rest.await(); sync.await()
        assertTrue(savedClasses().single().canOpen)
        assertEquals(EnrollmentStatus.ACTIVE, RoomEnrollmentNoticeRepository(db).notices("learner").first().single().status)
    }
    @Test fun restActiveNoticeAndLegacyMetadataCannotGrantClassAccessWithoutSyncedEnrollment(): Unit = runBlocking {
        db.syncStateDao().put(SyncStateEntity("rest_class_access:learner",
            """{"hub":"http://127.0.0.1:8080","rows":[{"classroomId":"class","status":"ACTIVE"}]}""", 1))
        val repo = SyncRepository(db, transport())
        repo.refreshClassrooms("learner") { ClassroomSnapshot("http://127.0.0.1:8080", listOf(
            ClassroomDto("class", "Science", "A", "K7M4QX", "teacher", enrollmentStatus = "ACTIVE"))) }
        assertEquals(EnrollmentStatus.ACTIVE, RoomEnrollmentNoticeRepository(db).notices("learner").first().single().status)
        assertTrue(savedClasses().isEmpty())
        assertNull(db.syncDao().enrollment("class", "learner"))
    }
    @Test fun openingAnUpgradedDatabaseClearsOnlyLegacyRestAccessAndPreservesQueuedWork(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "upgrade-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, LaraDatabase::class.java, name)
            .addCallback(LaraDatabase.classAccessCleanup).build()
        var upgraded = open()
        try {
            upgraded.authDao().saveProfile(UserEntity("learner", "synthetic", "Test", "STUDENT", 1, 1))
            upgraded.cacheDao().upsertBatch(CacheBatch(classrooms = listOf(classroom), announcements = listOf(post),
                announcementComments = listOf(AnnouncementCommentEntity("comment", "post", "learner", "Local work", 2, 2, "QUEUED_FOR_SYNC")),
                enrollments = listOf(EnrollmentEntity("server-enrollment", "class", "learner", "ACTIVE", 1, 1)),
                syncState = listOf(SyncStateEntity("rest_class_access:learner", "legacy", 1),
                    SyncStateEntity("rest_class_access:another-user", "legacy", 1), SyncStateEntity("cursor", "42", 1))))
            upgraded.close(); upgraded = open()
            assertNull(upgraded.syncStateDao().get("rest_class_access:learner"))
            assertNull(upgraded.syncStateDao().get("rest_class_access:another-user"))
            assertEquals("42", upgraded.syncStateDao().get("cursor")?.value)
            assertEquals("server-enrollment", upgraded.syncDao().enrollment("class", "learner")?.id)
            assertEquals(1, upgraded.syncDao().queuedComments("learner").size)
            assertEquals("learner", upgraded.authDao().currentUserId())
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
    @Test fun pageUsersRecordsAndCursorCommitAtomicallyAndLearnerLrnsAreStripped(): Unit = runBlocking {
        db.cacheDao().upsertBatch(CacheBatch(users = listOf(UserEntity("peer", "old-private", "Peer", "STUDENT", 1, 1))))
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
    @Test fun nameOnlyPullPreservesAuthenticatedIdentifierAndUpdatesName(): Unit = runBlocking {
        val store = SyncStore(db)
        store.apply("learner", store.request("learner"), page().copy(
            users = listOf(PublicUserDto("learner", "Updated name", "STUDENT"))))
        val saved = requireNotNull(db.syncDao().user("learner"))
        assertEquals("synthetic", saved.lrnOrId)
        assertEquals("Updated name", saved.fullName)
        store.apply("learner", store.request("learner"), page(2).copy(
            users = listOf(PublicUserDto("learner", "Updated name", "STUDENT", "corrected-identifier"))))
        assertEquals("corrected-identifier", db.syncDao().user("learner")?.lrnOrId)
    }
    @Test fun nameOnlyPullPreservesPreviouslySavedTeacherRosterIdentifier(): Unit = runBlocking {
        db.authDao().saveProfile(UserEntity("teacher", "synthetic-teacher", "Teacher", "TEACHER", 1, 1))
        db.cacheDao().upsertBatch(CacheBatch(enrollments = listOf(
            EnrollmentEntity("enrollment", "class", "learner", "PENDING", 1, 1))))
        val store = SyncStore(db)
        store.apply("teacher", store.request("teacher"), page().copy(
            users = listOf(PublicUserDto("learner", "Updated learner name", "STUDENT"))))
        assertEquals("synthetic", db.syncDao().user("learner")?.lrnOrId)
        assertEquals("Updated learner name", db.syncDao().user("learner")?.fullName)
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
        assertTrue(db.syncDao().queuedComments("learner").isEmpty())
        assertEquals("REJECTED", db.syncDao().comment("rejected")?.syncStatus)
        assertEquals("COMMENTS_DISABLED", db.syncDao().comment("rejected")?.syncRejectReason)
        assertNull(db.syncStateDao().get("upload_receipt:announcement_comments:rejected"))
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
    @Test fun rejectedWorkAndAllItsParentsSurvivePullResetAndTombstonesWithoutRetry(): Unit = runBlocking {
        val assignment = AssignmentEntity("assignment", "class", "Work", dueDate=10, createdAt=1, updatedAt=1)
        val quiz = QuizEntity("quiz", "class", "Quiz", timeLimitMinutes=5, createdAt=1, updatedAt=1)
        val comment = AnnouncementCommentEntity("rejected", "post", "learner", "Saved question", 1, 2, "REJECTED", "COMMENTS_DISABLED")
        val private = PrivateCommentEntity("private", "assignment", "learner", "learner", "Saved private", 1, 2, "REJECTED", "FORBIDDEN")
        val photo = AssignmentSubmissionEntity("photo", "assignment", "learner", "/local/keep.jpg", submittedAt=2, updatedAt=2,
            syncStatus="REJECTED", syncRejectReason="LATE_SUBMISSION")
        val attempt = QuizAttemptEntity("attempt", "quiz", "learner", "SUBMITTED", 1, 2, updatedAt=2,
            syncStatus="REJECTED", syncRejectReason="TIME_LIMIT_EXCEEDED")
        db.cacheDao().upsertBatch(CacheBatch(assignments=listOf(assignment), quizzes=listOf(quiz),
            announcementComments=listOf(comment), privateComments=listOf(private), assignmentSubmissions=listOf(photo), quizAttempts=listOf(attempt)))
        db.cacheDao().upsertBatch(CacheBatch(
            announcementComments=listOf(comment.copy(content="Remote copy", syncStatus="SYNCED", syncRejectReason=null)),
            privateComments=listOf(private.copy(content="Remote copy", syncStatus="SYNCED", syncRejectReason=null)),
            assignmentSubmissions=listOf(photo.copy(filePath="", syncStatus="SYNCED", syncRejectReason=null)),
            quizAttempts=listOf(attempt.copy(answersJson="[]", syncStatus="SYNCED", syncRejectReason=null))))
        val store = SyncStore(db)
        store.apply("learner", store.request("learner"), page().copy(reset=true, hubId="new-hub",
            deleted=listOf(DeletedRecordDto("announcements", "post"), DeletedRecordDto("assignments", "assignment"))))
        assertEquals(comment, db.syncDao().comment("rejected"))
        assertEquals(private, db.syncDao().privateComment("private"))
        assertEquals(photo, db.syncDao().submission("photo"))
        assertEquals(attempt, db.syncDao().attempt("attempt"))
        assertNotNull(db.syncDao().classroom("class")); assertNotNull(db.syncDao().announcement("post"))
        assertNotNull(db.syncDao().assignment("assignment")); assertNotNull(db.syncDao().quiz("quiz"))
        assertEquals("hub", db.syncStateDao().get("queued_hub:announcement_comments:rejected")?.value)
        assertNotNull(db.syncStateDao().get("deferred_tombstone:announcements:post"))
        assertEquals(0, SyncRepository(db, transport(push={ error("Rejected work must never retry") })).pushQueued("learner"))
    }
    @Test fun privateCommentAndQuizRejectionsPersistOnTheirRowsAndNeverRetry(): Unit = runBlocking {
        db.cacheDao().upsertBatch(CacheBatch(
            assignments=listOf(AssignmentEntity("assignment", "class", "Work", dueDate=10, createdAt=1, updatedAt=1)),
            quizzes=listOf(QuizEntity("quiz", "class", "Quiz", timeLimitMinutes=5, createdAt=1, updatedAt=1)),
            privateComments=listOf(PrivateCommentEntity("private", "assignment", "learner", "learner", "Question", 1, 2, "QUEUED_FOR_SYNC")),
            quizAttempts=listOf(QuizAttemptEntity("attempt", "quiz", "learner", "SUBMITTED", 1, 2, updatedAt=2))))
        var calls = 0
        val repo = SyncRepository(db, transport(push={ request ->
            calls++
            assertEquals(listOf("private"), request.privateComments.map { it.id })
            assertEquals(listOf("attempt"), request.quizAttempts.map { it.id })
            SyncPushResponseDto(true, listOf(SyncReceiptDto("private", "REJECTED", "FORBIDDEN"),
                SyncReceiptDto("attempt", "REJECTED", "TIME_LIMIT_EXCEEDED")))
        }))
        assertEquals(0, repo.pushQueued("learner"))
        assertEquals("REJECTED", db.syncDao().privateComment("private")?.syncStatus)
        assertEquals("FORBIDDEN", db.syncDao().privateComment("private")?.syncRejectReason)
        assertEquals("REJECTED", db.syncDao().attempt("attempt")?.syncStatus)
        assertEquals("TIME_LIMIT_EXCEEDED", db.syncDao().attempt("attempt")?.syncRejectReason)
        assertEquals(0, repo.pushQueued("learner")); assertEquals(1, calls)
        assertTrue(db.syncStateDao().observePrefix("upload_receipt:").first().isEmpty())
    }
    @Test fun lateRejectionCannotRejectANewerEdit(): Unit = runBlocking {
        queue()
        var calls = 0
        val repo = SyncRepository(db, transport(push={
            calls++
            if (calls > 1) throw java.io.IOException("Dropped")
            db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE announcement_comments SET content='Edited', updated_at=3 WHERE id='comment'") }
            SyncPushResponseDto(true, listOf(SyncReceiptDto("comment", "REJECTED", "COMMENTS_DISABLED")))
        }))
        try { repo.pushQueued("learner"); fail() } catch (_: java.io.IOException) { }
        val saved = requireNotNull(db.syncDao().comment("comment"))
        assertEquals("Edited", saved.content)
        assertEquals("QUEUED_FOR_SYNC", saved.syncStatus)
        assertNull(saved.syncRejectReason)
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
        assertTrue(db.syncDao().queuedComments("learner").isEmpty())
        assertEquals(50, db.streamDao().observeComments("post").first().count { it.syncStatus == "REJECTED" })
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
