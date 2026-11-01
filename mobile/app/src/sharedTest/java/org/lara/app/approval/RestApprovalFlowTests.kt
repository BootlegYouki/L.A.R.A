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
import java.util.UUID

abstract class RestApprovalFlowTests {
    protected abstract fun mockEnabled(): Boolean
    @Test fun freshRestCachesApproveAndSurviveReopenWithoutAnySync(): Unit = runBlocking {
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
            val pendingSnapshot = learnerApi.classroomSnapshot(learner.user.id)
            RoomLearnerClassAccess(learnerDb) { endpoint }.save(learner.user.id, pendingSnapshot.rows, pendingSnapshot.hub)
            teacherDb.close(); teacherDb = open(teacherName)
            learnerDb.close(); learnerDb = open(learnerName)
            assertEquals("PENDING", teacherDb.rosterDao().observeByClassroom(classroom.id).first()
                .single { it.enrollment.studentId == learner.user.id }.enrollment.status)
            assertFalse(requireNotNull(RoomLearnerClassAccess(learnerDb) { endpoint }.classes(learner.user.id).first()).single().canOpen)
            remote.decide(classroom.id, learner.user.id, true)
            next(learnerEvents) { it is HubEvent.JoinApproval && it.status == EnrollmentStatus.ACTIVE }
            val active = learnerApi.classroomSnapshot(learner.user.id)
            RoomLearnerClassAccess(learnerDb) { endpoint }.save(learner.user.id, active.rows, active.hub)
            SavedRosterRefresher(teacherDb, remote, teacher.user.id).refresh(classroom.id)
            assertEquals(savedId, teacherDb.rosterDao().observeByClassroom(classroom.id).first()
                .single { it.enrollment.studentId == learner.user.id }.enrollment.id)
            learnerDb.close(); learnerDb = open(learnerName)
            assertTrue(requireNotNull(RoomLearnerClassAccess(learnerDb) { endpoint }.classes(learner.user.id).first()).single().canOpen)
            assertTrue(learnerDb.rosterDao().observeForStudent(learner.user.id).first().isEmpty())
            assertNull(teacherDb.syncStateDao().get("cursor")); assertNull(learnerDb.syncStateDao().get("cursor"))
            remote.decide(classroom.id, learner.user.id, false)
            next(learnerEvents) { it is HubEvent.JoinApproval && it.status == EnrollmentStatus.REJECTED }
            val rejected = learnerApi.classroomSnapshot(learner.user.id)
            RoomLearnerClassAccess(learnerDb) { endpoint }.save(learner.user.id, rejected.rows, rejected.hub)
            assertTrue(requireNotNull(RoomLearnerClassAccess(learnerDb) { endpoint }.classes(learner.user.id).first()).isEmpty())
        } finally {
            teacherJob?.cancelAndJoin(); learnerJob?.cancelAndJoin(); realtime.close()
            teacherDb.close(); learnerDb.close()
            context.deleteDatabase(teacherName); context.deleteDatabase(learnerName)
        }
    }
}
