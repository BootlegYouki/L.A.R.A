package org.lara.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.*
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint

class TeacherClassTransportTest {
    @Test fun commandsUseContractRoutesAndNeverFollowRedirects(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port))
            val c = EnrollmentConnection(hub, StoredToken("synthetic", "teacher", Long.MAX_VALUE, hub.restBase))
            val client = HubClient()
            val cases = listOf(
                Triple(null, null, "POST") to "/api/classrooms",
                Triple("class", "remove", "POST") to "/api/classrooms/class/remove",
                Triple("class", "teachers", "POST") to "/api/classrooms/class/teachers",
            )
            for ((args, path) in cases) {
                server.enqueue(MockResponse().setBody("{}"))
                client.manageClass(c, args.first, args.second, null, args.third, "{}")
                val request = server.takeRequest()
                assertEquals(path, request.path)
                assertEquals(args.third, request.method)
                assertEquals("Bearer synthetic", request.getHeader("Authorization"))
            }
            server.enqueue(MockResponse().setBody("{}"))
            client.manageClass(c, "class", "teachers", "co-teacher", "DELETE", null)
            assertEquals("/api/classrooms/class/teachers/co-teacher", server.takeRequest().path)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.invalid"))
            try { client.manageClass(c, null, null, null, "POST", "{}"); fail() }
            catch (e: HubFailure) { assertEquals(302, e.status) }
            assertEquals(5, server.requestCount)
            try { client.manageClass(c, "../auth", null, null, "PATCH", "{}"); fail() }
            catch (_: IllegalArgumentException) { }
            assertEquals(5, server.requestCount)
        }
    }
}
