package org.lara.app.data.remote.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Portal reachability only. It cannot authenticate a Hub or establish a user session. */
class HubProbe {
    suspend fun responds(endpoint: HubEndpoint, open: (URL) -> HttpURLConnection): Boolean =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = open(URL("${endpoint.restBase}/download"))
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 3_000
                connection.readTimeout = 3_000
                val responds = connection.responseCode == 200 &&
                    connection.contentType.orEmpty().startsWith("text/html", ignoreCase = true)
                currentCoroutineContext().ensureActive()
                responds
            } catch (_: java.io.IOException) { false }
            catch (_: SecurityException) { false }
            finally { connection?.disconnect() }
        }
}
