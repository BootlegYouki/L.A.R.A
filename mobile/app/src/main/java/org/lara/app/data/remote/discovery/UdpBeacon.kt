package org.lara.app.data.remote.discovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The UDP discovery beacon the Hub broadcasts to `255.255.255.255:8888` every 3 seconds
 * (`rules/networking-and-lan.md` §2). Field names mirror the contract exactly via [SerialName];
 * never invent a field (mobile/AGENTS "DTOs").
 *
 * ```json
 * {"app":"lara","version":"1.2.0","name":"Grade 4 - Science","ip":"192.168.1.50","http_port":8080,"ws_port":8081}
 * ```
 */
@Serializable
data class UdpBeacon(
    val app: String,
    val version: String? = null,
    val name: String? = null,
    val ip: String? = null,
    @SerialName("http_port") val httpPort: Int = DiscoveredHub.DEFAULT_HTTP_PORT,
    @SerialName("ws_port") val wsPort: Int = DiscoveredHub.DEFAULT_WS_PORT,
) {
    /**
     * Convert a beacon to a [DiscoveredHub]. [senderIp] is the datagram's source address, used as a
     * trustworthy fallback when the beacon omits or misreports its own `ip`. Returns null for a
     * foreign app's beacon so unrelated broadcast traffic can never masquerade as a Hub.
     */
    fun toDiscoveredHub(senderIp: String?): DiscoveredHub? {
        if (app != APP_IDENTIFIER) return null
        val address = ip?.takeIf { it.isNotBlank() } ?: senderIp ?: return null
        return DiscoveredHub(
            name = name?.takeIf { it.isNotBlank() } ?: address,
            ip = address,
            httpPort = httpPort,
            wsPort = wsPort,
            source = DiscoverySource.UDP_BEACON,
            version = version,
        )
    }

    companion object {
        /** Only packets whose `app` equals this are treated as L.A.R.A Hubs. */
        const val APP_IDENTIFIER = "lara"
    }
}
