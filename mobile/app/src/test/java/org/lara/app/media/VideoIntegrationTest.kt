package org.lara.app.media

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.RoomVideoRepository
import org.lara.app.domain.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class VideoIntegrationTest {
    private val bytes = ByteArray(900_000) { (it % 251).toByte() }.apply { "ftyp".toByteArray().copyInto(this, 4) }
    private lateinit var server: MockWebServer
    private lateinit var db: LaraDatabase
    private lateinit var root: File
    private lateinit var api: EnrollmentApi
    private lateinit var endpoint: HubEndpoint
    private var token: StoredToken? = null
    private val row = MaterialEntity("lesson", "class", "Synthetic video", "VIDEO", fileSizeBytes = bytes.size.toLong(), createdAt = 1, updatedAt = 1)
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    @Before fun setup(): Unit = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val range = requireNotNull(request.getHeader("Range")).removePrefix("bytes=").split('-')
                val start = range[0].toInt(); val end = range[1].toInt().coerceAtMost(bytes.lastIndex)
                return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    .setHeader("ETag", "\"v1\"").setBody(Buffer().write(bytes.copyOfRange(start, end + 1)))
            }
        }
        server.start()
        endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port, server.port))
        token = StoredToken("synthetic-token", "owner", Long.MAX_VALUE, endpoint.restBase)
        val store = object : SessionTokenStore {
            override fun load() = token
            override fun save(value: StoredToken) { token = value }
            override fun clear() { token = null }
        }
        api = EnrollmentApi(HubClient(), store, { endpoint })
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, LaraDatabase::class.java).build()
        root = File(context.cacheDir, "video-${System.nanoTime()}").apply { mkdirs() }
        db.authDao().saveProfile(UserEntity("owner", null, "Synthetic learner", "STUDENT", 1, 1))
        db.cacheDao().upsertBatch(CacheBatch(classrooms = listOf(ClassroomEntity("class", "Science", "A", "K7M4QX", "teacher", createdAt = 1, updatedAt = 1)), materials = listOf(row)))
    }
    @After fun cleanup() { db.close(); root.deleteRecursively(); server.shutdown() }
    @Test fun savingUpdatesRoomAndOfflineSelectionDoesNotNeedAuth(): Unit = runBlocking {
        val repository = RoomVideoRepository(db, api, root, "owner")
        assertTrue(repository.source("lesson") is VideoSource.Hub)
        val states = mutableListOf<VideoDownload>()
        repository.save("lesson", states::add)
        val saved = requireNotNull(db.classworkDao().material("lesson")?.localFilePath)
        assertArrayEquals(bytes, File(saved).readBytes())
        assertEquals(DownloadPhase.COMPLETE, states.last().phase)
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer synthetic-token" })
        assertTrue(requests.all { it.path == "/api/materials/lesson/download" })
        assertTrue(requests.drop(1).all { it.getHeader("If-Range") == "\"v1\"" })
        token = null
        assertEquals(VideoSource.Local(File(saved).canonicalPath), repository.source("lesson"))
        assertEquals(4, requests.size)
        // A normal metadata refresh must preserve the completed path.
        db.cacheDao().upsertBatch(CacheBatch(materials = listOf(row.copy(title = "Renamed", updatedAt = 2))))
        assertEquals(saved, db.classworkDao().material("lesson")?.localFilePath)
        assertEquals(VideoSource.Local(File(saved).canonicalPath), repository.source("lesson"))
    }
    @Test fun missingLocalFileClearsStalePathAndSelectsHub(): Unit = runBlocking {
        db.classworkDao().saveFile("lesson", 1, File(root, "gone.video").path, "video/mp4")
        val repository = RoomVideoRepository(db, api, root, "owner")
        assertTrue(repository.source("lesson") is VideoSource.Hub)
        assertNull(db.classworkDao().material("lesson")?.localFilePath)
        assertEquals(0, requests.size)
    }
    @Test fun foreignAccountAndExpiredTokenCannotStream(): Unit = runBlocking {
        val repository = RoomVideoRepository(db, api, root, "owner")
        token = requireNotNull(token).copy(userId = "other")
        try { repository.source("lesson"); fail() } catch (e: VideoFailure) { assertEquals(VideoProblem.SIGN_IN, e.problem) }
        token = requireNotNull(token).copy(userId = "owner", expiresAt = 1)
        try { repository.source("lesson"); fail() } catch (e: VideoFailure) { assertEquals(VideoProblem.SIGN_IN, e.problem) }
        assertTrue(requests.isEmpty())
    }
    @Test fun continuousReadAndForwardBackwardSeekUseBoundedAuthenticatedRanges(): Unit = runBlocking {
        val http = MediaHttp(api, "owner")
        val source = VideoDataSource(OkHttpDataSource.Factory(http.client(endpoint.restBase)).createDataSource(), bytes.size.toLong())
        fun read(position: Long, length: Long): ByteArray {
            source.open(DataSpec.Builder().setUri(Uri.parse("${endpoint.restBase}/api/materials/lesson/stream"))
                .setPosition(position).setLength(length).build())
            try {
                val output = ByteArrayOutputStream(); val buffer = ByteArray(32768)
                while (true) { val n = source.read(buffer, 0, buffer.size); if (n == C.RESULT_END_OF_INPUT) break; output.write(buffer, 0, n) }
                return output.toByteArray()
            } finally { source.close() }
        }
        assertArrayEquals(bytes.copyOfRange(0, 600000), read(0, 600000))
        assertArrayEquals(bytes.copyOfRange(700000, 710000), read(700000, 10000))
        assertArrayEquals(bytes.copyOfRange(1000, 11000), read(1000, 10000))
        assertEquals(5, requests.size)
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer synthetic-token" })
        assertEquals("bytes=700000-709999", requests[3].getHeader("Range"))
        assertEquals("bytes=1000-10999", requests[4].getHeader("Range"))
        assertArrayEquals(byteArrayOf(), read(bytes.size.toLong(), C.LENGTH_UNSET.toLong()))
        assertEquals(5, requests.size)
    }
    @Test fun redirectNeverForwardsCredentials(): Unit = runBlocking {
        val untrusted = MockWebServer(); untrusted.start()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(302)
                    .setHeader("Location", untrusted.url("/stolen"))
            }
            val http = MediaHttp(api, "owner")
            http.open("lesson", 0, 10, null).use { assertEquals(302, it.code) }
            assertEquals(0, untrusted.requestCount)
        } finally { untrusted.shutdown() }
    }
    @Test fun invalidRangeResponseFailsBeforeReadingVideo(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody("Not a partial response")
        }
        val http = MediaHttp(api, "owner")
        val source = VideoDataSource(OkHttpDataSource.Factory(http.client(endpoint.restBase)).createDataSource(), bytes.size.toLong())
        try {
            source.open(DataSpec.Builder().setUri(Uri.parse("${endpoint.restBase}/api/materials/lesson/stream")).build()); fail()
        } catch (e: VideoFailure) { assertEquals(VideoProblem.INVALID_FILE, e.problem) }
        finally { source.close() }
    }
    @Test fun tombstoneCleanupRemovesSavedAndPartialFiles(): Unit = runBlocking {
        val repository = RoomVideoRepository(db, api, root, "owner")
        repository.save("lesson") {}
        db.openHelper.writableDatabase.execSQL("DELETE FROM materials WHERE id='lesson'")
        File(root, "orphan.part").writeText("temporary")
        repository.prune()
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }
    @Test fun materialChangeDuringTransferPreventsRoomReceipt(): Unit = runBlocking {
        val repository = RoomVideoRepository(db, api, root, "owner")
        var changed = false
        try {
            repository.save("lesson") {
                if (!changed) {
                    changed = true
                    runBlocking { db.cacheDao().upsertBatch(CacheBatch(materials = listOf(row.copy(updatedAt = 2)))) }
                }
            }
            fail("Changed material received a completed path")
        } catch (e: VideoFailure) { assertEquals(VideoProblem.CHANGED, e.problem) }
        assertNull(db.classworkDao().material("lesson")?.localFilePath)
        assertFalse(root.listFiles().orEmpty().any { it.extension == "video" })
    }
    @Test fun unauthorizedResponseUsesSignInState(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401).setBody("{}")
        }
        val http = MediaHttp(api, "owner")
        try { http.open("lesson", 0, 100, null).close(); fail() }
        catch (e: VideoFailure) { assertEquals(VideoProblem.SIGN_IN, e.problem) }
    }
    @Test fun duplicateSaveAndConcurrentPruneKeepOneCompletedFile(): Unit = runBlocking {
        val repository = RoomVideoRepository(db, api, root, "owner")
        var pruned = false
        coroutineScope {
            launch {
                repository.save("lesson") {
                    if (!pruned) { pruned = true; runBlocking { repository.prune() } }
                }
            }
            launch { repository.save("lesson") {} }
        }
        assertEquals(4, requests.size)
        assertTrue(File(requireNotNull(db.classworkDao().material("lesson")?.localFilePath)).isFile)
    }

    @Test fun offlineRemoveClearsOnlyThisVideoAndCanDownloadAgain(): Unit = runBlocking {
        val repository = RoomVideoRepository(db, api, root, "owner")
        val other = row.copy(id = "other")
        db.cacheDao().upsertBatch(CacheBatch(materials = listOf(other)))
        repository.save("lesson") {}
        repository.save("other") {}
        val saved = File(requireNotNull(db.classworkDao().material("lesson")?.localFilePath))
        val otherFile = File(requireNotNull(db.classworkDao().material("other")?.localFilePath))
        val part = File(root, "${saved.nameWithoutExtension}.part").apply { writeText("partial") }
        val validator = File(root, "${saved.nameWithoutExtension}.validator").apply { writeText("etag") }
        val handout = File(root, "handout.pdf").apply { writeText("keep") }
        val queued = File(root, "homework.jpg").apply { writeText("keep") }
        val validToken = token
        token = null
        val before = requests.size
        repository.remove("lesson")
        assertEquals(before, requests.size)
        assertFalse(saved.exists()); assertFalse(part.exists()); assertFalse(validator.exists())
        assertNull(db.classworkDao().material("lesson")?.localFilePath)
        assertArrayEquals(bytes, otherFile.readBytes())
        assertEquals("keep", handout.readText()); assertEquals("keep", queued.readText())
        assertEquals(DownloadPhase.IDLE, repository.progress("lesson").phase)
        token = validToken
        repository.save("lesson") {}
        assertArrayEquals(bytes, File(requireNotNull(db.classworkDao().material("lesson")?.localFilePath)).readBytes())
    }

    @Test fun removalRejectsForeignPathsWithoutDeletingAnything(): Unit = runBlocking {
        val foreign = File(root.parentFile, "${System.nanoTime()}.video").apply { writeBytes(bytes) }
        try {
            db.classworkDao().saveFile("lesson", 1, foreign.path, "video/mp4")
            val repository = RoomVideoRepository(db, api, root, "owner")
            try { repository.remove("lesson"); fail("Foreign path accepted") }
            catch (e: VideoFailure) { assertEquals(VideoProblem.STORAGE, e.problem) }
            assertTrue(foreign.isFile)
            assertEquals(foreign.path, db.classworkDao().material("lesson")?.localFilePath)
        } finally { foreign.delete() }
    }

    @Test fun deletionFailureKeepsCompletedVideoAndRoomReceipt(): Unit = runBlocking {
        val repository = RoomVideoRepository(db, api, root, "owner")
        repository.save("lesson") {}
        val saved = File(requireNotNull(db.classworkDao().material("lesson")?.localFilePath))
        File(root, "${saved.nameWithoutExtension}.part").mkdir()
        try { repository.remove("lesson"); fail("Directory accepted as partial file") }
        catch (e: VideoFailure) { assertEquals(VideoProblem.STORAGE, e.problem) }
        assertTrue(saved.isFile)
        assertEquals(saved.path, db.classworkDao().material("lesson")?.localFilePath)
    }
}
