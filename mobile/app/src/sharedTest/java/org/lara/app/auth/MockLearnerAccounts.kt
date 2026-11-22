package org.lara.app.auth

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.lara.app.data.remote.AuthSession
import org.lara.app.data.remote.HubClient
import org.lara.app.data.remote.discovery.HubEndpoint

private val mockAccountJson = Json { ignoreUnknownKeys = true }

/** Account provisioning for isolated mock fixtures only. The app itself only signs in. */
internal suspend fun createMockLearner(hub: HubEndpoint, lrn: String, name: String) = withContext(Dispatchers.IO) {
    require(hub.ip == "127.0.0.1")
    val admin = mockAccountJson.decodeFromString(
        AuthSession.serializer(), HubClient().postAuth(hub, buildJsonObject {
            put("lrn_or_id", "ADMIN-0001")
            put("pin", "123456")
        }.toString()),
    )
    val connection = URL(hub.restBase + "/api/admin/users").openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer ${admin.token}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { output ->
            output.write(buildJsonObject {
                put("lrn_or_id", lrn)
                put("full_name", name)
                put("role", "STUDENT")
                put("pin", "123456")
            }.toString().toByteArray(Charsets.UTF_8))
        }
        val status = connection.responseCode
        check(status == 200) { "Mock Hub admin could not create learner (HTTP $status)" }
    } finally {
        connection.disconnect()
    }
}
