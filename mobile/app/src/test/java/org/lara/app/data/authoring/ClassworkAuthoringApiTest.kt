package org.lara.app.data.authoring

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.domain.MaterialDraft

class ClassworkAuthoringApiTest {
    @Test fun uploadStreamsMultipartAndNeverFollowsRedirectWithCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port))
            val tokens = object : SessionTokenStore {
                override fun load() = StoredToken("synthetic", "teacher", Long.MAX_VALUE, hub.restBase)
                override fun save(value: StoredToken) = Unit
                override fun clear() = Unit
            }
            val api = ClassworkAuthoringApi(EnrollmentApi(HubClient(), tokens, { hub }))
            val file = File.createTempFile("fixture-", ".pdf")
            try {
                file.writeText("%PDF-1.4 test")
                server.enqueue(MockResponse().setBody("{}"))
                val progress = mutableListOf<Int>()
                api.upload("teacher", hub.restBase, "class", file, MaterialDraft("Lesson", "DOCUMENT", assignmentId = "assignment"), progress::add)
                val request = server.takeRequest()
                assertEquals("Bearer synthetic", request.getHeader("Authorization"))
                val body = request.body.readUtf8()
                assertTrue(body.contains("name=\"assignment_id\""))
                assertTrue(body.contains("%PDF-1.4 test"))
                assertEquals(100, progress.last())
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:9/"))
                try { api.mutate("teacher", "topics", "topic", "DELETE", null); fail("Redirect accepted") }
                catch (error: HubFailure) { assertEquals(302, error.status) }
                assertEquals(2, server.requestCount)
                try { api.upload("teacher", "http://127.0.0.2:8080", "class", file, MaterialDraft("Lesson", "DOCUMENT")) {}; fail("Hub changed") }
                catch (_: IllegalArgumentException) { }
                assertEquals(2, server.requestCount)
            } finally { file.delete() }
        }
    }
}
