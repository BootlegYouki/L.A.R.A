package org.lara.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class StreamReadingTest {
    private lateinit var db: LaraDatabase
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var files: MaterialFiles
    private val material = MaterialEntity("handout", "class", "Lesson", "DOCUMENT", fileSizeBytes=12, createdAt=1, updatedAt=1)
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, LaraDatabase::class.java).build()
        root = File(context.cacheDir, "reading-${System.nanoTime()}").apply { mkdirs() }
        server = MockWebServer().apply { start() }
        val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port))
        val tokens = object : SessionTokenStore {
            override fun load() = StoredToken("synthetic", "learner", Long.MAX_VALUE, endpoint.restBase)
            override fun save(value: StoredToken) = Unit
            override fun clear() = Unit
        }
        // SharedPreferences reloads a new HubEndpoint instance on every connection check.
        files = MaterialFiles(db, EnrollmentApi(HubClient(), tokens,
            endpoint = { requireNotNull(HubEndpoint.lan("127.0.0.1", server.port)) }), root, "learner")
        db.cacheDao().upsertBatch(CacheBatch(
            classrooms=listOf(ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt=1, updatedAt=1)),
            announcements=listOf(AnnouncementEntity("post", "class", "Lesson", "Read", createdAt=1, updatedAt=1)),
            materials=listOf(material)))
    }
    @After fun close() { db.close(); server.shutdown(); root.deleteRecursively() }

    @Test fun handoutDownloadsWithBearerAndReopensWithoutTheHub(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("%PDF-1.4 synthetic handout"))
        val file = files.open("handout")
        val request = server.takeRequest()
        assertEquals("/api/materials/handout/download", request.path)
        assertEquals("Bearer synthetic", request.getHeader("Authorization"))
        assertEquals("application/pdf", db.classworkDao().material("handout")?.mimeType)
        assertEquals(file.path, db.classworkDao().material("handout")?.localFilePath)
        server.shutdown()
        assertEquals(file, files.open("handout"))
    }
    @Test fun redirectsAreNotFollowedAndFailedDownloadHasNoCachedPath(): Unit = runBlocking {
        val other = MockWebServer().apply { start(); enqueue(MockResponse().setBody("outside")) }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/outside")))
            try { files.open("handout"); fail("Redirect accepted") } catch (_: HubFailure) { }
            assertEquals(0, other.requestCount)
            assertNull(db.classworkDao().material("handout")?.localFilePath)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { other.shutdown() }
    }
    @Test fun oversizeResponseIsRejectedBeforeTheBodyIsRead(): Unit = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Length", 250L * 1024 * 1024 + 1))
        try { files.open("handout"); fail("Oversize accepted") } catch (_: IllegalArgumentException) { }
        assertNull(db.classworkDao().material("handout")?.localFilePath)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }
    @Test fun interruptedTransferLeavesNoPartialFileOrSavedPath(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("%PDF-" + "x".repeat(8192))
            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try { files.open("handout"); fail("Truncated response accepted") } catch (_: Exception) { }
        assertNull(db.classworkDao().material("handout")?.localFilePath)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }
    @Test fun renamingMaterialKeepsItsFileAndTombstonesRemoveOnlyUnreferencedFiles(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("%PDF-1.4 old"))
        val file = files.open("handout")
        db.cacheDao().upsertBatch(CacheBatch(materials=listOf(material)))
        assertEquals(file.path, db.classworkDao().material("handout")?.localFilePath)
        db.cacheDao().upsertBatch(CacheBatch(materials=listOf(material.copy(title="Renamed", updatedAt=2))))
        assertEquals(file, files.open("handout"))
        files.prune()
        assertTrue(file.isFile)
        db.cacheDao().deleteBatch(materialIds=listOf("handout"))
        files.prune()
        assertFalse(file.exists())
    }
    @Test fun disabledCommentsNeverEnterTheQueueAndEnabledOnesKeepTheirIdentity(): Unit = runBlocking {
        val post = db.streamDao().observeByClassroom("class").first().single()
        val repository = RoomStreamRepository(db.streamDao(), newId={"queued"})
        db.cacheDao().upsertBatch(CacheBatch(announcements=listOf(post.copy(allowComments=false))))
        try { repository.addComment("post", "learner", "Saved question"); fail("Disabled comment queued") }
        catch (_: IllegalStateException) { }
        assertTrue(db.streamDao().observeComments("post").first().isEmpty())
        db.cacheDao().upsertBatch(CacheBatch(announcements=listOf(post)))
        repository.addComment("post", "learner", "Saved question")
        assertEquals("QUEUED_FOR_SYNC", db.streamDao().observeComments("post").first().single().syncStatus)
    }
}
