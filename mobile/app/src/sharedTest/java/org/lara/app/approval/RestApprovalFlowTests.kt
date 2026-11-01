package org.lara.app.approval

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.UserEntity
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.*
import org.lara.app.domain.enrollment.*
import org.lara.app.domain.Cached
import org.lara.app.data.sync.*
import java.util.UUID

abstract class RestApprovalFlowTests {
    protected abstract fun mockEnabled(): Boolean
    @Test fun freshTeacherRestAndCanonicalLearnerSyncApproveAndSurviveReopen(): Unit = runBlocking {
        assumeTrue("Requires isolated mock on 28080/28081", mockEnabled())
        val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", 28080, 28081))
        val teacher = AuthApi().authenticate(endpoint, "T-0001", "1234", null)
        val learner = AuthApi().authenticate(endpoint, "7" + System.nanoTime().toString().takeLast(11).padStart(11, '0'), "1234", "REST approval learner")
        fun api(session: AuthSession): EnrollmentApi {
            val tokens = object : SessionTokenStore {
                override fun load() = StoredToken(session.token, session.user.id, session.expiresAt, endpoint.restBase)
                override fun save(value: StoredToken) = Unit
                override fun clear() = Unit
            }
            return EnrollmentApi(HubClient(), tokens, { endpoint })
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val teacherName = "rt${UUID.randomUUID().toString().take(8)}.db"
        val learnerName = "rl${UUID.randomUUID().toString().take(8)}.db"
        fun open(name: String) = Room.databaseBuilder(context, LaraDatabase::class.java, name).build()
        var teacherDb = open(teacherName)
        var learnerDb = open(learnerName)
        val teacherApi = api(teacher)
        val learnerApi = api(learner)
        suspend fun refreshLearner() {
            val sync = SyncRepository(learnerDb, HubSyncTransport(learnerApi, HubClient()))
            sync.refreshClassrooms(learner.user.id) { learnerApi.classroomSnapshot(learner.user.id) }
            sync.pull(learner.user.id)
        }
        suspend fun savedLearnerClasses(): List<LearnerClass> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return try {
                (RoomEnrollmentRepository(learnerDb.rosterDao()).learnerClasses(learner.user.id, scope)
                    .first { it is Cached.Content } as Cached.Content).value
            } finally { scope.cancel() }
        }
        val remote = HubClassManagementRepository(teacher.user.id, teacherApi, HubClient())
        val realtime = RealtimeClient()
        val teacherEvents = Channel<HubEvent>(Channel.UNLIMITED)
        val learnerEvents = Channel<HubEvent>(Channel.UNLIMITED)
        var teacherJob: Job? = null
        var learnerJob: Job? = null
        suspend fun next(channel: Channel<HubEvent>, matches: (HubEvent) -> Boolean) = withTimeout(10_000) {
            var event = channel.receive()
            while (!matches(event)) event = channel.receive()
            event
        }
        try {
            for ((db, session) in listOf(teacherDb to teacher, learnerDb to learner)) {
                db.authDao().saveProfile(UserEntity(session.user.id, session.user.lrnOrId, session.user.fullName, session.user.role, 1, 1))
            }
            assertTrue(teacherDb.classroomDao().observeAll().first().isEmpty())
            SavedRosterRefresher(teacherDb, remote, teacher.user.id).refreshClasses()
            val classroom = teacherDb.classroomDao().observeAll().first().single { it.classCode == "K7M4QX" }
            teacherJob = launch { realtime.observe(teacherApi.connection(teacher.user.id)).collect { teacherEvents.send(it) } }
            learnerJob = launch { realtime.observe(learnerApi.connection(learner.user.id)).collect { learnerEvents.send(it) } }
            next(teacherEvents) { it is HubEvent.HelloAck }
            next(learnerEvents) { it is HubEvent.HelloAck }
            assertEquals(EnrollmentStatus.PENDING, learnerApi.join(learner.user.id, "K7M-4QX").status)
            next(teacherEvents) { it is HubEvent.JoinRequest && it.studentId == learner.user.id }
            SavedRosterRefresher(teacherDb, remote, teacher.user.id).refresh(classroom.id)
            val roster = remote.roster(classroom.id).single { it.studentId == learner.user.id }
            val savedId = teacherDb.rosterDao().observeByClassroom(classroom.id).first()
                .single { it.enrollment.studentId == learner.user.id }.enrollment.id
            assertEquals(roster.enrollmentId, savedId)
            refreshLearner()
            teacherDb.close(); teacherDb = open(teacherName)
            learnerDb.close(); learnerDb = open(learnerName)
            assertEquals("PENDING", teacherDb.rosterDao().observeByClassroom(classroom.id).first()
                .single { it.enrollment.studentId == learner.user.id }.enrollment.status)
            assertTrue(savedLearnerClasses().isEmpty())
            assertEquals(EnrollmentStatus.PENDING, RoomEnrollmentNoticeRepository(learnerDb)
                .notices(learner.user.id).first().single().status)
            remote.decide(classroom.id, learner.user.id, true)
            next(learnerEvents) { it is HubEvent.JoinApproval && it.status == EnrollmentStatus.ACTIVE }
            refreshLearner()
            SavedRosterRefresher(teacherDb, remote, teacher.user.id).refresh(classroom.id)
            assertEquals(savedId, teacherDb.rosterDao().observeByClassroom(classroom.id).first()
                .single { it.enrollment.studentId == learner.user.id }.enrollment.id)
            learnerDb.close(); learnerDb = open(learnerName)
            assertTrue(savedLearnerClasses().single().canOpen)
            assertEquals(savedId, savedLearnerClasses().single().enrollment.id)
            assertNull(learnerDb.syncStateDao().get("rest_class_access:${learner.user.id}"))
            assertNull(teacherDb.syncStateDao().get("cursor")); assertNotNull(learnerDb.syncStateDao().get("cursor"))
        } finally {
            teacherJob?.cancelAndJoin(); learnerJob?.cancelAndJoin(); realtime.close()
            teacherDb.close(); learnerDb.close()
            context.deleteDatabase(teacherName); context.deleteDatabase(learnerName)
        }
    }
}
