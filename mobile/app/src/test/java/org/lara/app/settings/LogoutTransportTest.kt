package org.lara.app.settings

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.*
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint

class LogoutTransportTest {
    @Test fun logoutUsesBearerAndDoesNotFollowRedirects(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port))
            val session = StoredToken("synthetic-token", "learner", Long.MAX_VALUE, hub.restBase)
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            AuthApi().logout(hub, session)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/auth/logout", request.path)
            assertEquals("Bearer synthetic-token", request.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.invalid"))
            try { AuthApi().logout(hub, session); fail() } catch (e: HubFailure) { assertEquals(302, e.status) }
            assertEquals(2, server.requestCount)
        }
    }
}
