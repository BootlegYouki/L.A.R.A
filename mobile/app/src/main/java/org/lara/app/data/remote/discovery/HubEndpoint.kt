package org.lara.app.data.remote.discovery

import org.lara.app.data.remote.isAllowedHubUrl

class HubEndpoint private constructor(val ip: String, val httpPort: Int, val wsPort: Int) {
    val restBase: String get() = "http://$ip:$httpPort"
    val webSocketUrl: String get() = "ws://$ip:$wsPort"

    companion object {
        fun lan(ip: String, httpPort: Int = 8080, wsPort: Int = 8081): HubEndpoint? {
            if (httpPort !in 1..65535 || wsPort !in 1..65535) return null
            if (!isAllowedHubUrl("http://$ip:$httpPort") || !isAllowedHubUrl("ws://$ip:$wsPort")) return null
            return HubEndpoint(ip, httpPort, wsPort)
        }

        fun manual(input: String): HubEndpoint? {
            val value = input.trim()
            if (!Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}(:[0-9]{1,5})?$").matches(value)) return null
            val parts = value.split(':')
            val port = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 8080
            return lan(parts[0], port)
        }
    }
}
