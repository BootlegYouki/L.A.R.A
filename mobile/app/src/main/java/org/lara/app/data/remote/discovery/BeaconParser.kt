package org.lara.app.data.remote.discovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.lara.app.domain.discovery.DiscoveredHub

@Serializable
private data class Beacon(
    val app: String,
    val version: String,
    val name: String,
    val ip: String,
    @SerialName("http_port") val httpPort: Int,
    @SerialName("ws_port") val wsPort: Int,
)

object BeaconParser {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(payload: String): DiscoveredHub? {
        if (payload.length > 4096) return null
        return try {
            val value = json.parseToJsonElement(payload) as? JsonObject ?: return null
            for (key in listOf("http_port", "ws_port")) {
                val port = value[key] as? JsonPrimitive ?: return null
                if (port.isString) return null
            }
            val beacon = json.decodeFromJsonElement<Beacon>(value)
            val endpoint = HubEndpoint.lan(beacon.ip, beacon.httpPort, beacon.wsPort)
            if (beacon.app != "lara" || beacon.name.isBlank() || beacon.name.length > 160 ||
                beacon.version.isBlank() || endpoint == null) null
            else DiscoveredHub(beacon.name, endpoint.ip, endpoint.httpPort, endpoint.wsPort)
        } catch (_: IllegalArgumentException) { null }
    }
}

/** Source leases keep a lost mDNS service from removing a still-live UDP advertisement. */
class HubRegistry(private val ttlMs: Long = 10_000) {
    private data class Lease(val hub: DiscoveredHub, val seen: Long)
    private val leases = mutableMapOf<String, Lease>()
    fun seen(source: String, hub: DiscoveredHub, now: Long) { leases[source] = Lease(hub, now) }
    fun remove(source: String) { leases.remove(source) }
    fun snapshot(now: Long): List<DiscoveredHub> {
        leases.entries.removeAll { now - it.value.seen >= ttlMs }
        return leases.values.sortedByDescending { it.seen }.map { it.hub }.distinctBy { it.key }
            .sortedBy { it.name }
    }
}
