package org.lara.app.data.remote

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.domain.enrollment.*
import java.util.concurrent.atomic.AtomicReference

class RealtimeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: RealtimeClient
    @Before fun setup() { server = MockWebServer().apply { start() }; client = RealtimeClient() }
    @After fun close() { client.close(); server.shutdown() }
    private fun connection(): EnrollmentConnection {
        val endpoint = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port, server.port))
        return EnrollmentConnection(endpoint, StoredToken("synthetic-token", "learner", Long.MAX_VALUE, endpoint.restBase))
    }
    @Test fun firstFrameAuthenticatesAndOnlyOwnPostHandshakeApprovalIsPublished() = runBlocking {
        val hello = AtomicReference<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                hello.set(text)
                socket.send("""{"event":"EVENT_JOIN_APPROVAL","classroom_id":"early","student_id":"learner","status":"ACTIVE","timestamp":1}""")
                socket.send("""{"event":"EVENT_HELLO_ACK","hub_id":"hub","sync_epoch":1,"server_time":2}""")
                socket.send("""{"event":"EVENT_JOIN_APPROVAL","classroom_id":"foreign","student_id":"other","status":"ACTIVE","timestamp":3}""")
                socket.send("""{"event":"EVENT_JOIN_APPROVAL","classroom_id":"class","student_id":"learner","status":"ACTIVE","timestamp":4}""")
            }
        }))
        val events = withTimeout(15_000) { client.observe(connection()).take(2).toList() }
        val first = Json.parseToJsonElement(hello.get()).jsonObject
        assertEquals("EVENT_HELLO", first["event"]?.jsonPrimitive?.content)
        assertEquals("synthetic-token", first["token"]?.jsonPrimitive?.content)
        assertTrue(events.first() is HubEvent.HelloAck)
        assertEquals("class", (events.last() as HubEvent.JoinApproval).classroomId)
    }
    @Test fun temporaryHandshakeFailureReconnectsThenAuthenticates() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                socket.send("""{"event":"EVENT_HELLO_ACK","hub_id":"hub","sync_epoch":1,"server_time":2}""")
            }
        }))
        val event = withTimeout(15_000) { client.observe(connection()).take(1).toList().single() }
        assertTrue(event is HubEvent.HelloAck)
        assertEquals(2, server.requestCount)
    }
    @Test fun unauthorizedCloseStopsReconnectsAndReportsSessionExpiry() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) { socket.close(4401, "") }
        }))
        val events = withTimeout(15_000) { client.observe(connection()).toList() }
        assertEquals(listOf(HubEvent.SessionExpired), events)
        assertEquals(1, server.requestCount)
    }
    @Test fun retryBackoffIsBoundedAndMalformedEventsAreIgnored() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 10000L, 10000L), (0..5).map(::reconnectDelayMillis))
        assertNull(decodeHubEvent("not-json"))
        assertNull(decodeHubEvent("""{"event":"EVENT_JOIN_APPROVAL","classroom_id":"c","student_id":"s","status":"PENDING","timestamp":1}"""))
        assertNull(decodeHubEvent("""{"event":"EVENT_HELLO_ACK","hub_id":"h"}"""))
    }
}
