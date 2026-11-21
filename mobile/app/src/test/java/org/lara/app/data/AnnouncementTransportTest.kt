package org.lara.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.lara.app.data.remote.AnnouncementApi
import org.lara.app.data.remote.EnrollmentApi
import org.lara.app.data.remote.HubClient
import org.lara.app.data.remote.SessionTokenStore
import org.lara.app.data.remote.StoredToken
import org.lara.app.data.remote.discovery.HubEndpoint
import java.util.concurrent.TimeUnit

class AnnouncementTransportTest {
    @Test fun postsTheContractPayloadWithTheTeachersSession(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{}"))
            val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port))
            val tokens = object : SessionTokenStore {
                override fun load() = StoredToken("synthetic", "teacher", Long.MAX_VALUE, endpoint.restBase)
                override fun save(value: StoredToken) = Unit
                override fun clear() = Unit
            }
            val client = HubClient()
            val api = AnnouncementApi(EnrollmentApi(client, tokens, { endpoint }), client)

            api.post("teacher", "classroom", " Reminder ", " Bring your notes ", allowComments = false)

            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/api/announcements", request.path)
            assertEquals("Bearer synthetic", request.getHeader("Authorization"))
            assertEquals(
                Json.parseToJsonElement(
                    """{"classroom_id":"classroom","title":"Reminder","content":"Bring your notes","allow_comments":false}""",
                ),
                Json.parseToJsonElement(request.body.readUtf8()),
            )
        }
    }
}
