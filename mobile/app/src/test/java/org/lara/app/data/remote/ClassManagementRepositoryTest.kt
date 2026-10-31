package org.lara.app.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.domain.enrollment.HubEvent

class ClassManagementRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: HubClassManagementRepository
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port))
        val tokens = object : SessionTokenStore {
            override fun load() = StoredToken("synthetic", "teacher", Long.MAX_VALUE, endpoint.restBase)
            override fun save(value: StoredToken) = Unit
            override fun clear() = Unit
        }
        repository = HubClassManagementRepository("teacher", EnrollmentApi(HubClient(), tokens, { endpoint }), HubClient())
    }
    @After fun close() { server.shutdown() }
    @Test fun rosterAndBothDecisionsFollowContractWithBearerAndOnlyStudentReference() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"student_id":"learner","full_name":"Test","lrn":"synthetic","status":"PENDING"}]"""))
        assertEquals("learner", repository.roster("class").single().studentId)
        assertEquals("/api/classrooms/class/roster", server.takeRequest().path)
        for (accept in listOf(true, false)) {
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            repository.decide("class", "learner", accept)
            val request = server.takeRequest()
            assertEquals("/api/classrooms/class/${if(accept) "approve" else "reject"}", request.path)
            assertEquals("POST", request.method)
            assertEquals("Bearer synthetic", request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(setOf("student_id"), body.keys)
            assertEquals("learner", body["student_id"]?.jsonPrimitive?.content)
        }
    }
    @Test fun unsafeClassPathNeverSendsCredentials() = runBlocking {
        try { repository.roster("../auth/login"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun forbiddenDecisionRemainsFailureForRollback() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"FORBIDDEN"}}"""))
        try { repository.decide("class", "learner", true); fail() }
        catch(e: HubFailure) { assertEquals(403, e.status) }
    }
    @Test fun joinRequestDecodingRequiresAllContractFieldsAndRedactsSensitiveValues() {
        val event = """{"event":"EVENT_JOIN_REQUEST","class_code":"K7M4QX","student_id":"learner","full_name":"Private Name","lrn":"private-lrn","timestamp":1}"""
        val decoded = decodeHubEvent(event) as HubEvent.JoinRequest
        assertEquals("K7M4QX", decoded.classCode)
        assertFalse(decoded.toString().contains("Private Name"))
        assertFalse(decoded.toString().contains("private-lrn"))
        assertNull(decodeHubEvent(event.replace(""","timestamp":1""", "")))
        assertNull(decodeHubEvent(event.replace("private-lrn", "").replace("lrn", "missing")))
    }
}
