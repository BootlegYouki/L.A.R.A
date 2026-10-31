package org.lara.app.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.lara.app.data.remote.discovery.HubEndpoint

class EnrollmentApiTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: HubEndpoint
    private lateinit var tokens: MemoryTokens
    private lateinit var api: EnrollmentApi
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port))
        tokens = MemoryTokens(StoredToken("synthetic-token", "learner", 100_000, endpoint.restBase))
        api = EnrollmentApi(HubClient(), tokens, { endpoint }, now = { 10_000 })
    }
    @After fun close() { server.shutdown() }
    @Test fun joinUsesOnlyCanonicalCodeAndBearerHeader() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"PENDING","classroom_id":"class"}"""))
        val result = api.join("learner", "k7m-4qx")
        assertEquals("class", result.classroomId)
        assertEquals("PENDING", result.status.name)
        val request = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals("/api/classrooms/join", request.path)
        assertEquals("Bearer synthetic-token", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("class_code"), body.keys)
        assertEquals("K7M4QX", body["class_code"]?.jsonPrimitive?.content)
    }
    @Test fun invalidCodePreservesServerErrorCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"CLASS_CODE_INVALID","message":"synthetic"}}"""))
        try { api.join("learner", "ZZZZZZ"); fail() }
        catch(e: HubFailure) { assertEquals(404, e.status); assertEquals("CLASS_CODE_INVALID", e.code) }
    }
    @Test fun expiredForeignAndWrongHubSessionsNeverSendCredentials() = runBlocking {
        val saved = requireNotNull(tokens.value)
        for (value in listOf(saved.copy(expiresAt = 1), saved.copy(userId = "another"), saved.copy(hub = "http://127.0.0.2:8080"))) {
            tokens.value = value
            try { api.join("learner", "K7M4QX"); fail() }
            catch(e: HubFailure) { assertEquals(401, e.status) }
        }
        assertEquals(0, server.requestCount)
    }
    @Test fun redirectedJoinDoesNotForwardBearerToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/redirected"))
        try { api.join("learner", "K7M4QX"); fail() }
        catch(e: HubFailure) { assertEquals(307, e.status) }
        assertEquals(1, server.requestCount)
    }
    @Test fun classroomListRecoversServerReportedPendingState() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":"class","name":"Science","section":"A","class_code":"K7M4QX","teacher_id":"teacher","enrollment_status":"PENDING"}]"""))
        val row = api.classrooms("learner").single()
        assertEquals("PENDING", row.enrollmentStatus)
        assertEquals("/api/classrooms", server.takeRequest().path)
    }
    private class MemoryTokens(var value: StoredToken?) : SessionTokenStore {
        override fun load() = value
        override fun save(value: StoredToken) { this.value = value }
        override fun clear() { value = null }
    }
}
