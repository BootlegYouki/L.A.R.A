package org.lara.app.media

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.UserEntity
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.RoomVideoRepository
import org.lara.app.data.sync.*
import org.lara.app.domain.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class VideoMockIntegrationTest {
    @Test fun actualMobileDataSourceReadsMockRangesAndRejectsPlaceholderDownload(): Unit = runBlocking {
        assumeTrue(System.getenv("LARA_SYNC_MOCK") == "1")
        val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", 18080, 18081))
        val session = AuthApi().authenticate(endpoint, "123456789012", "1234", null)
        val tokens = object : SessionTokenStore {
            override fun load() = StoredToken(session.token, session.user.id, session.expiresAt, endpoint.restBase)
            override fun save(value: StoredToken) = Unit
            override fun clear() = Unit
        }
        val api = EnrollmentApi(HubClient(), tokens, { endpoint })
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, LaraDatabase::class.java).build()
        val root = File(context.cacheDir, "mock-video-${System.nanoTime()}").apply { mkdirs() }
        try {
            db.authDao().saveProfile(UserEntity(session.user.id, null, "Synthetic seeded learner", "STUDENT", 1, 1))
            SyncRepository(db, HubSyncTransport(api, HubClient())).pull(session.user.id)
            val material = db.classworkDao().videos().first { it.id.startsWith("m2000000") }
            val repository = RoomVideoRepository(db, api, root, session.user.id)
            assertTrue(repository.source(material.id) is VideoSource.Hub)
            val http = MediaHttp(api, session.user.id)
            val source = VideoDataSource(OkHttpDataSource.Factory(http.client(endpoint.restBase)).createDataSource(), material.fileSizeBytes)
            for (position in listOf(0L, 200_000L, 1_000L)) {
                source.open(DataSpec.Builder().setUri(Uri.parse("${endpoint.restBase}/api/materials/${material.id}/stream"))
                    .setPosition(position).setLength(1000).build())
                try {
                    val buffer = ByteArray(1000)
                    var total = 0
                    while (total < buffer.size) {
                        val count = source.read(buffer, total, buffer.size - total)
                        assertTrue(count > 0); total += count
                    }
                    assertTrue(buffer.all { it == '0'.code.toByte() })
                    assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
                } finally { source.close() }
            }
            try { repository.save(material.id) {}; fail("PDF mock placeholder must never become a saved video") }
            catch (e: VideoFailure) { assertEquals(VideoProblem.INVALID_FILE, e.problem) }
            assertNull(db.classworkDao().material(material.id)?.localFilePath)
            assertFalse(root.listFiles().orEmpty().any { it.extension == "video" })
        } finally { db.close(); root.deleteRecursively() }
    }
}
