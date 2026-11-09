package org.lara.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.repository.*
import org.lara.app.domain.Cached

abstract class RoomBehaviorTests {
    private lateinit var database: LaraDatabase
    private lateinit var scope: CoroutineScope
    private val classroom = ClassroomEntity("class", "Synthetic science", "A", "K7M4QX", "teacher", createdAt = 1, updatedAt = 1)
    private val announcement = AnnouncementEntity("post", "class", "Lesson", "Read the lesson", createdAt = 1, updatedAt = 1)

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LaraDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun close() {
        scope.cancel()
        database.close()
    }

    @Test fun streamStateFlowStartsLoadingAndUpdatesFromLocalWrites(): Unit = runBlocking {
        val repository = RoomStreamRepository(database.streamDao())
        val stream = repository.stream("class", scope)
        assertEquals(Cached.Loading, stream.value)
        withTimeout(10_000) { stream.first { it is Cached.Content && it.value.isEmpty() } }
        database.cacheDao().upsertBatch(CacheBatch(classrooms = listOf(classroom), announcements = listOf(announcement)))
        val content = withTimeout(10_000) { stream.first { it is Cached.Content && it.value.isNotEmpty() } }
        assertEquals("Lesson", (content as Cached.Content).value.single().title)
        database.cacheDao().upsertBatch(CacheBatch(announcements = listOf(announcement.copy(title = "Updated"))))
        withTimeout(10_000) { stream.first { it is Cached.Content && it.value.singleOrNull()?.title == "Updated" } }
    }

    @Test fun repositoryQueuesCommentWithoutHubAndProtectsItFromRefreshAndDelete(): Unit = runBlocking {
        database.cacheDao().upsertBatch(CacheBatch(classrooms = listOf(classroom), announcements = listOf(announcement)))
        val repository = RoomStreamRepository(database.streamDao(), now = { 12 }, newId = { "comment" })
        assertEquals("comment", repository.addComment("post", "synthetic-learner", "My question"))
        val local = database.streamDao().observeComments("post").first().single()
        assertEquals("QUEUED_FOR_SYNC", local.syncStatus)
        database.cacheDao().upsertBatch(CacheBatch(announcementComments = listOf(local.copy(content = "stale", syncStatus = "SYNCED"))))
        database.cacheDao().deleteBatch(announcementIds = listOf("post"), commentIds = listOf("comment"))
        assertEquals("My question", database.streamDao().observeComments("post").first().single().content)
        assertEquals(1, database.streamDao().observeByClassroom("class").first().size)
    }

    @Test fun batchRollsBackDataAndCursorOnForeignKeyFailure(): Unit = runBlocking {
        database.syncStateDao().put(SyncStateEntity("cursor", "7", 1))
        var failed = false
        try {
            database.withTransaction {
                database.syncStateDao().put(SyncStateEntity("cursor", "8", 2))
                database.cacheDao().upsertBatch(CacheBatch(
                    classrooms = listOf(classroom),
                    announcements = listOf(announcement.copy(classroomId = "missing")),
                ))
            }
        } catch (_: android.database.sqlite.SQLiteConstraintException) { failed = true }
        assertTrue("Expected foreign-key failure", failed)
        assertTrue(database.classroomDao().observeAll().first().isEmpty())
        assertEquals("7", database.syncStateDao().get("cursor")?.value)
    }

    @Test fun uniqueKeysRollBackEntireBatch(): Unit = runBlocking {
        var failed = false
        try {
            database.cacheDao().upsertBatch(CacheBatch(classrooms = listOf(classroom), enrollments = listOf(
                EnrollmentEntity("one", "class", "learner", joinedAt = 1, updatedAt = 1),
                EnrollmentEntity("two", "class", "learner", joinedAt = 1, updatedAt = 1),
            )))
        } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
        assertTrue(database.classroomDao().observeAll().first().isEmpty())
    }

    @Test fun missingRosterUserIsAllowedAndLaterResolves(): Unit = runBlocking {
        database.cacheDao().upsertBatch(CacheBatch(classrooms = listOf(classroom), enrollments = listOf(
            EnrollmentEntity("enrollment", "class", "learner", status = "ACTIVE", joinedAt = 1, updatedAt = 1),
        )))
        assertNull(database.rosterDao().observeByClassroom("class").first().single().user)
        database.cacheDao().upsertBatch(CacheBatch(users = listOf(UserEntity("learner", fullName = "Synthetic learner", role = "STUDENT", createdAt = 1, updatedAt = 1))))
        val member = database.rosterDao().observeByClassroom("class").first().single()
        assertEquals("Synthetic learner", member.user?.fullName)
        assertNull(member.user?.lrnOrId)
    }

    @Test fun quizLockoutIsPerLearnerAndPendingAttemptSurvivesRefresh(): Unit = runBlocking {
        val quiz = QuizEntity("quiz", "class", "Review", timeLimitMinutes = 10, createdAt = 1, updatedAt = 1)
        val attempt = QuizAttemptEntity("attempt", "quiz", "learner", startedAt = 2, updatedAt = 2)
        database.cacheDao().upsertBatch(CacheBatch(classrooms = listOf(classroom), quizzes = listOf(quiz), quizAttempts = listOf(attempt)))
        val repository = RoomQuizRepository(database.quizDao())
        assertTrue(repository.hasInProgressAttempt("learner").first())
        assertFalse(repository.hasInProgressAttempt("someone-else").first())
        database.cacheDao().upsertBatch(CacheBatch(quizAttempts = listOf(attempt.copy(status = "SUBMITTED", submittedAt = 3, syncStatus = "SYNCED"))))
        assertTrue(repository.hasInProgressAttempt("learner").first())
    }

    @Test fun classworkAndCachedFilePathsRemainAvailableWithoutNetwork(): Unit = runBlocking {
        val material = MaterialEntity("material", "class", "Lesson", "DOCUMENT", fileSizeBytes = 120, localFilePath = "/private/synthetic.pdf", createdAt = 1, updatedAt = 1)
        database.cacheDao().upsertBatch(CacheBatch(classrooms = listOf(classroom), materials = listOf(material), topics = listOf(TopicEntity("topic", "class", "Unit", createdAt = 1, updatedAt = 1))))
        database.cacheDao().upsertBatch(CacheBatch(materials = listOf(material.copy(localFilePath = null, title = "Refreshed"))))
        val state = RoomClassworkRepository(database.classworkDao(), database.quizDao()).classwork("class", scope)
        val content = withTimeout(10_000) { state.first { it is Cached.Content } } as Cached.Content
        assertEquals("/private/synthetic.pdf", content.value.materials.single().localFilePath)
        assertEquals("Unit", content.value.topics.single().name)
    }

    @Test fun deleteBatchRemovesSyncedCommentsAndCascadesMaterialChunks(): Unit = runBlocking {
        database.cacheDao().upsertBatch(CacheBatch(
            classrooms = listOf(classroom), announcements = listOf(announcement),
            announcementComments = listOf(AnnouncementCommentEntity("comment", "post", "learner", "Hello", 1, 1)),
            materials = listOf(MaterialEntity("material", "class", "Lesson", "DOCUMENT", fileSizeBytes = 1, createdAt = 1, updatedAt = 1)),
            materialChunks = listOf(MaterialChunkEntity("chunk", "material", 0, text = "A lesson", updatedAt = 1)),
        ))
        database.cacheDao().deleteBatch(materialIds = listOf("material"), announcementIds = listOf("post"))
        assertTrue(database.classworkDao().observeChunks("material").first().isEmpty())
        assertTrue(database.classworkDao().observeMaterials("class").first().isEmpty())
        assertTrue(database.streamDao().observeComments("post").first().isEmpty())
    }

    @Test fun fileDatabasePersistsCursorAndRecordsAcrossReopen(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "room-41-persistence-test.db"
        context.deleteDatabase(name)
        fun create(): LaraDatabase = Room.databaseBuilder(context, LaraDatabase::class.java, name).build()
        try {
            val first = create()
            try {
                first.cacheDao().upsertBatch(CacheBatch(classrooms = listOf(classroom), syncState = listOf(SyncStateEntity("cursor", "91", 1))))
            } finally { first.close() }
            val reopened = create()
            try {
                assertEquals("91", reopened.syncStateDao().get("cursor")?.value)
                assertEquals("class", reopened.classroomDao().observeAll().first().single().id)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun entityChecksRejectInvalidValuesBeforeWrites() {
        assertThrows(IllegalArgumentException::class.java) {
            UserEntity("user", fullName = "Synthetic", role = "ADMIN", createdAt = 1, updatedAt = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuizAttemptEntity("attempt", "quiz", "learner", status = "SUBMITTED", startedAt = 1, updatedAt = 2)
        }
    }

    @Test fun completeContractGraphPersistsAllSixteenTables(): Unit = runBlocking {
        database.cacheDao().upsertBatch(CacheBatch(
            users = listOf(UserEntity("learner", fullName = "Synthetic learner", role = "STUDENT", createdAt = 1, updatedAt = 1)),
            classrooms = listOf(classroom),
            enrollments = listOf(EnrollmentEntity("enrollment", "class", "learner", joinedAt = 1, updatedAt = 1)),
            announcements = listOf(announcement),
            announcementComments = listOf(AnnouncementCommentEntity("comment", "post", "learner", "Question", 1, 1)),
            materials = listOf(MaterialEntity("material", "class", "Lesson", "DOCUMENT", fileSizeBytes = 1, createdAt = 1, updatedAt = 1)),
            materialChunks = listOf(MaterialChunkEntity("chunk", "material", 0, text = "Read this", updatedAt = 1)),
            assignments = listOf(AssignmentEntity("assignment", "class", "Task", dueDate = 10, createdAt = 1, updatedAt = 1)),
            assignmentSubmissions = listOf(AssignmentSubmissionEntity("submission", "assignment", "learner", "/private/synthetic.jpg", submittedAt = 3, updatedAt = 3)),
            quizzes = listOf(QuizEntity("quiz", "class", "Review", timeLimitMinutes = 5, createdAt = 1, updatedAt = 1)),
            quizQuestions = listOf(QuizQuestionEntity("question", "quiz", 0, "Explain the lesson", "IDENTIFICATION", createdAt = 1, updatedAt = 1)),
            quizAttempts = listOf(QuizAttemptEntity("attempt", "quiz", "learner", startedAt = 2, updatedAt = 2)),
            aiChatMessages = listOf(AiChatMessageEntity("message", "class", "learner", role = "USER", content = "Help me understand", createdAt = 1)),
            topics = listOf(TopicEntity("topic", "class", "Unit", createdAt = 1, updatedAt = 1)),
            privateComments = listOf(PrivateCommentEntity("private", "assignment", "learner", "learner", "Question", 1, 1, "QUEUED_FOR_SYNC")),
            syncState = listOf(SyncStateEntity("cursor", "1", 1)),
        ))
        withContext(Dispatchers.IO) {
            val sql = database.openHelper.writableDatabase
            val tables = sql.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT IN ('room_master_table', 'android_metadata')").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertEquals(16, tables.size)
            tables.forEach { table ->
                sql.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(table, 1L, cursor.getLong(0))
                }
            }
        }
    }
}
