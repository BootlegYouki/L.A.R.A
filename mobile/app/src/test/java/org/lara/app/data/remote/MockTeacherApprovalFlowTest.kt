package org.lara.app.data.remote

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.domain.enrollment.*

/** Opt-in protocol test against a fresh local scripts/mock_hub.py instance, without touching installed sessions. */
class MockTeacherApprovalFlowTest {
    @Test fun realMockDeliversJoinAndDecisionEventsAndUpdatesLearnerMembership() = runBlocking {
        assumeTrue("Requires a fresh local mock on ports 18080/18081", System.getenv("LARA_APPROVAL_MOCK") == "1")
        val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", 18080, 18081))
        val auth = AuthApi()
        val teacher = auth.authenticate(endpoint, "T-0001", "1234", null)
        val syntheticId = "9" + System.nanoTime().toString().takeLast(11).padStart(11, '0')
        val learner = auth.authenticate(endpoint, syntheticId, "1234", "Synthetic approval test")
        fun api(session: AuthSession): EnrollmentApi {
            val tokens = object : SessionTokenStore {
                override fun load() = StoredToken(session.token, session.user.id, session.expiresAt, endpoint.restBase)
                override fun save(value: StoredToken) = Unit
                override fun clear() = Unit
            }
            return EnrollmentApi(HubClient(), tokens, { endpoint })
        }
        val teacherApi = api(teacher)
        val learnerApi = api(learner)
        val remote = HubClassManagementRepository(teacher.user.id, teacherApi, HubClient())
        val realtime = RealtimeClient()
        val teacherEvents = Channel<HubEvent>(Channel.UNLIMITED)
        val learnerEvents = Channel<HubEvent>(Channel.UNLIMITED)
        val teacherJob = launch { realtime.observe(teacherApi.connection(teacher.user.id)).collect { teacherEvents.send(it) } }
        val learnerJob = launch { realtime.observe(learnerApi.connection(learner.user.id)).collect { learnerEvents.send(it) } }
        suspend fun next(channel: Channel<HubEvent>, predicate: (HubEvent) -> Boolean): HubEvent = withTimeout(10_000) {
            var event = channel.receive()
            while (!predicate(event)) event = channel.receive()
            event
        }
        try {
            next(teacherEvents) { it is HubEvent.HelloAck }
            next(learnerEvents) { it is HubEvent.HelloAck }
            val receipt = learnerApi.join(learner.user.id, "K7M-4QX")
            assertEquals(EnrollmentStatus.PENDING, receipt.status)
            val request = next(teacherEvents) { it is HubEvent.JoinRequest } as HubEvent.JoinRequest
            assertEquals(learner.user.id, request.studentId)
            assertEquals(EnrollmentStatus.PENDING, remote.roster(receipt.classroomId).single { it.studentId == learner.user.id }.status)
            remote.decide(receipt.classroomId, learner.user.id, true)
            val approved = next(learnerEvents) { it is HubEvent.JoinApproval } as HubEvent.JoinApproval
            assertEquals(EnrollmentStatus.ACTIVE, approved.status)
            assertEquals("ACTIVE", learnerApi.classrooms(learner.user.id).single { it.id == receipt.classroomId }.enrollmentStatus)
            remote.decide(receipt.classroomId, learner.user.id, false)
            val rejected = next(learnerEvents) { it is HubEvent.JoinApproval } as HubEvent.JoinApproval
            assertEquals(EnrollmentStatus.REJECTED, rejected.status)
            assertEquals(EnrollmentStatus.REJECTED, remote.roster(receipt.classroomId).single { it.studentId == learner.user.id }.status)
        } finally {
            teacherJob.cancelAndJoin(); learnerJob.cancelAndJoin(); realtime.close()
            teacherEvents.close(); learnerEvents.close()
        }
    }
}
