package org.lara.app.data

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint

class HomeworkTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var photo: File
    private lateinit var api: HomeworkApi
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        photo = File.createTempFile("synthetic-homework-", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port))
        val tokens = object : SessionTokenStore {
            override fun load() = StoredToken("synthetic", "learner", Long.MAX_VALUE, endpoint.restBase)
            override fun save(value: StoredToken) = Unit
            override fun clear() = Unit
        }
        api = HomeworkApi(EnrollmentApi(HubClient(), tokens, { endpoint }))
    }
    @After fun close() { server.shutdown(); photo.delete() }

    @Test fun streamsMultipartAndAcceptsTheHubsCanonicalSubmissionId(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"canonical","assignment_id":"assignment","student_id":"learner","file_type":"IMAGE","submitted_at":1}"""))
        assertEquals("canonical", api.upload("learner", "assignment", "local", photo).id)
        val request = server.takeRequest()
        assertEquals("/api/assignments/assignment/submit", request.path)
        assertEquals("Bearer synthetic", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"submission_id\""))
        assertTrue(body.contains("local"))
        assertTrue(body.contains("filename=\"homework.jpg\""))
        assertFalse(body.contains(photo.path))
    }

    @Test fun rejectsReceiptsForAnotherLearner(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"local","assignment_id":"assignment","student_id":"someone-else","file_type":"IMAGE","submitted_at":1}"""))
        try { api.upload("learner", "assignment", "local", photo); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun neverFollowsRedirects(): Unit = runBlocking {
        val other = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", other.url("/outside")))
            try { api.upload("learner", "assignment", "local", photo); fail() }
            catch (error: HubFailure) { assertEquals(307, error.status) }
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }
}
