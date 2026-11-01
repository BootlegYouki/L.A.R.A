package org.lara.app.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.UserEntity
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.*
import org.lara.app.data.sync.*
import org.lara.app.domain.enrollment.*

abstract class MockSyncBehaviorTests {
    protected abstract fun mockEnabled(): Boolean
    @Test fun actualMockPullQueueApprovalAndAnnouncementEventPersistIntoRoom(): Unit = runBlocking {
        assumeTrue("Requires isolated local mock on 18080/18081", mockEnabled())
        val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", 18080, 18081))
        val auth = AuthApi()
        val teacher = auth.authenticate(endpoint, "T-0001", "1234", null)
        val syntheticId = "8" + System.nanoTime().toString().takeLast(11).padStart(11, '0')
        val learner = auth.authenticate(endpoint, syntheticId, "1234", "Synthetic sync learner")
        fun api(session: AuthSession): EnrollmentApi {
            val tokens = object : SessionTokenStore {
                override fun load() = StoredToken(session.token, session.user.id, session.expiresAt, endpoint.restBase)
                override fun save(value: StoredToken) = Unit
                override fun clear() = Unit
            }
            return EnrollmentApi(HubClient(), tokens, { endpoint })
        }
        suspend fun database(session: AuthSession): LaraDatabase {
            val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LaraDatabase::class.java).build()
            db.authDao().saveProfile(UserEntity(session.user.id, session.user.lrnOrId, session.user.fullName, session.user.role, 1, 1))
            return db
        }
        val teacherDb = database(teacher)
        val learnerDb = database(learner)
        val teacherApi = api(teacher)
        val learnerApi = api(learner)
        val teacherSync = SyncRepository(teacherDb, HubSyncTransport(teacherApi, HubClient()))
        val learnerSync = SyncRepository(learnerDb, HubSyncTransport(learnerApi, HubClient()))
        val remote = HubClassManagementRepository(teacher.user.id, teacherApi, HubClient())
        val realtime = RealtimeClient()
        var socketJob: Job? = null
        fun publish(classroomId: String, title: String) {
            val connection = URL(endpoint.restBase + "/api/announcements").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer " + teacher.token)
                connection.setRequestProperty("Content-Type", "application/json")
                val body = buildJsonObject { put("classroom_id", classroomId); put("title", title); put("content", "Read offline") }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                assertEquals(200, connection.responseCode)
                connection.inputStream.close()
            } finally { connection.disconnect() }
        }
        try {
            learnerSync.pull(learner.user.id)
            val joined = learnerApi.join(learner.user.id, "K7M-4QX")
            teacherSync.pull(teacher.user.id)
            // Pending learner identities are obtained from the authorized roster response; enrollment IDs come from sync.
            SavedRosterRefresher(teacherDb, remote, teacher.user.id).refresh(joined.classroomId)
            val pending = teacherDb.rosterDao().observeByClassroom(joined.classroomId).first().single { it.enrollment.studentId == learner.user.id }
            assertEquals("PENDING", pending.enrollment.status)
            assertEquals("Synthetic sync learner", pending.user?.fullName)
            remote.decide(joined.classroomId, learner.user.id, true)
            learnerSync.pull(learner.user.id)
            val saved = learnerDb.rosterDao().observeByClassroom(joined.classroomId).first().single()
            assertEquals(pending.enrollment.id, saved.enrollment.id)
            assertEquals("ACTIVE", saved.enrollment.status)
            val posts = learnerDb.streamDao().observeByClassroom(joined.classroomId).first()
            assertTrue(posts.isNotEmpty())
            val id = RoomStreamRepository(learnerDb.streamDao()).addComment(posts.first().id, learner.user.id, "Synthetic queued question")
            learnerSync.synchronize(learner.user.id)
            assertEquals("SYNCED", learnerDb.streamDao().observeComments(posts.first().id).first().single { it.id == id }.syncStatus)
            val events = Channel<HubEvent>(Channel.UNLIMITED)
            socketJob = launch { realtime.observe(learnerApi.connection(learner.user.id)).collect { events.send(it) } }
            withTimeout(10_000) { while (events.receive() !is HubEvent.HelloAck) { } }
            val start = System.nanoTime()
            val title = "Synthetic live sync ${System.nanoTime()}"
            publish(joined.classroomId, title)
            withTimeout(10_000) { while (events.receive() !is HubEvent.Announcement) { } }
            learnerSync.pull(learner.user.id)
            assertTrue(learnerDb.streamDao().observeByClassroom(joined.classroomId).first().any { it.title == title })
            val elapsed = (System.nanoTime() - start) / 1_000_000
            println("Local mock announcement event to Room: $elapsed ms")
            assertTrue("Local event-driven refresh exceeded 1 second", elapsed < 1_000)
            socketJob.cancelAndJoin(); socketJob = null
            val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            var networkAvailable = false
            val offline = CompletableDeferred<Unit>()
            val refreshed = CompletableDeferred<Long>()
            val reconnectJob = launch {
                observeHubReachability(changes) {
                    networkAvailable && org.lara.app.data.remote.discovery.HubProbe().responds(endpoint) {
                        it.openConnection() as HttpURLConnection
                    }
                }.collect { online ->
                    if (!online) offline.complete(Unit)
                    else { learnerSync.pull(learner.user.id); refreshed.complete(System.nanoTime()) }
                }
            }
            try {
                withTimeout(5_000) { offline.await() }
                val missedTitle = "Synthetic reconnect sync ${System.nanoTime()}"
                publish(joined.classroomId, missedTitle)
                assertFalse(learnerDb.streamDao().observeByClassroom(joined.classroomId).first().any { it.title == missedTitle })
                val reconnectedAt = System.nanoTime()
                networkAvailable = true
                changes.emit(Unit)
                val savedAt = withTimeout(5_000) { refreshed.await() }
                assertTrue(learnerDb.streamDao().observeByClassroom(joined.classroomId).first().any { it.title == missedTitle })
                val reconnectMs = (savedAt - reconnectedAt) / 1_000_000
                println("Injected network-regained signal through Hub probe to Room: $reconnectMs ms")
                assertTrue("Network-regained refresh exceeded 1 second", reconnectMs < 1_000)
            } finally { reconnectJob.cancelAndJoin() }
            // No network operation is needed to read the saved class, roster or announcement after this point.
            assertNotNull(learnerDb.syncStateDao().get("cursor"))
            events.close()
        } finally {
            socketJob?.cancelAndJoin(); realtime.close(); teacherDb.close(); learnerDb.close()
        }
    }
}
