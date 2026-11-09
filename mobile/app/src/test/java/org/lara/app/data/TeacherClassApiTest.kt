package org.lara.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import org.lara.app.data.remote.*
import org.lara.app.data.remote.discovery.HubEndpoint

class TeacherClassApiTest {
    @Test fun typedCommandsSerializeContractFieldsAndRejectWrongAccountBeforeSending(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", server.port))
            var saved = StoredToken("synthetic", "owner", Long.MAX_VALUE, hub.restBase)
            val tokens = object : SessionTokenStore {
                override fun load() = saved
                override fun save(value: StoredToken) { saved = value }
                override fun clear() { }
            }
            val api = TeacherClassApi("owner", EnrollmentApi(HubClient(), tokens, { hub }), HubClient())
            val response = """{"id":"class","name":"Science","section":"A","class_code":"K7M4QX","teacher_id":"owner"}"""
            server.enqueue(MockResponse().setBody(response))
            assertEquals("K7M4QX", api.create(" Science ", " A ").classCode)
            assertEquals("Science", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["name"]?.jsonPrimitive?.content)
            server.enqueue(MockResponse().setBody(response))
            api.addTeacher("class", " T-0002 ")
            assertEquals("T-0002", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["lrn_or_id"]?.jsonPrimitive?.content)
            server.enqueue(MockResponse().setBody("{}"))
            api.removeLearner("class", "learner")
            assertEquals("learner", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["student_id"]?.jsonPrimitive?.content)
            saved = saved.copy(userId = "someone-else")
            try { api.create("Science", "A"); fail() } catch (e: HubFailure) { assertEquals(401, e.status) }
            assertEquals(3, server.requestCount)
        }
    }
}
