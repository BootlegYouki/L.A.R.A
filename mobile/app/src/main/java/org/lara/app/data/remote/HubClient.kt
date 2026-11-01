package org.lara.app.data.remote

import kotlinx.serialization.json.*
import org.lara.app.data.remote.discovery.HubEndpoint
import java.net.HttpURLConnection
import java.net.URL

class HubFailure(val status: Int, val code: String, val retryAfterSeconds: Long = 0) : Exception()

/** Fixed route groups and no redirects: credentials remain on the selected LAN Hub. */
class HubClient {
    internal fun postAuth(endpoint: HubEndpoint, path: String, payload: String): String {
        require(path in setOf("/api/auth/login", "/api/auth/register"))
        return try { exchange(endpoint, path, "POST", payload, null) }
        catch (e: HubFailure) { throw AuthFailure(e.status, e.retryAfterSeconds) }
    }

    internal fun enrollment(endpoint: HubEndpoint, session: StoredToken, method: String, payload: String? = null): String {
        require(session.hub == endpoint.restBase)
        require(session.token.isNotBlank() && session.token.none(Char::isWhitespace))
        val path = when (method) {
            "GET" -> "/api/classrooms"
            "POST" -> "/api/classrooms/join"
            else -> error("Unsupported enrollment method")
        }
        return exchange(endpoint, path, method, payload, session.token)
    }

    internal fun teacherRoster(c: EnrollmentConnection, classroomId: String, action: String?, payload: String?): String {
        require(classroomId.matches(Regex("[A-Za-z0-9-]{1,128}")))
        require(c.session.hub == c.endpoint.restBase)
        require(c.session.token.isNotBlank() && c.session.token.none(Char::isWhitespace))
        require(action == null || action in setOf("approve", "reject"))
        return exchange(c.endpoint, "/api/classrooms/$classroomId/${action ?: "roster"}",
            if (action == null) "GET" else "POST", payload, c.session.token)
    }

    private fun exchange(endpoint: HubEndpoint, path: String, method: String, payload: String?, token: String?): String {
        val url = endpoint.restBase + path
        require(isAllowedHubUrl(url))
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = method
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status != 200) {
                val body = connection.errorStream?.use(::readBounded).orEmpty()
                val code = runCatching {
                    Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
                }.getOrNull() ?: "UNKNOWN"
                throw HubFailure(status, code,
                    connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60)
            }
            return connection.inputStream.use(::readBounded)
        } finally { connection.disconnect() }
    }

    private fun readBounded(input: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 65_536)
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }
}
