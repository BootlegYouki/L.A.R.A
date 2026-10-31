package org.lara.app.data.remote

import org.lara.app.data.remote.discovery.HubEndpoint
import java.net.HttpURLConnection
import java.net.URL

/** Auth-only transport. Redirects are rejected before credentials can reach another host. */
class HubClient {
    internal fun postAuth(endpoint: HubEndpoint, path: String, payload: String): String {
        require(path in setOf("/api/auth/login", "/api/auth/register"))
        val url = endpoint.restBase + path
        require(isAllowedHubUrl(url))
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status != 200) throw AuthFailure(status,
                connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60)
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 65_536)
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }
}
