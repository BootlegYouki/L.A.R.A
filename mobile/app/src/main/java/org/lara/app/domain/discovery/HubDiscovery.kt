package org.lara.app.domain.discovery

import kotlinx.coroutines.flow.Flow

data class DiscoveredHub(val name: String, val ip: String, val httpPort: Int, val wsPort: Int) {
    val key: String get() = "$ip:$httpPort"
}

data class DiscoverySnapshot(
    val hubs: List<DiscoveredHub> = emptyList(),
    val wifiAvailable: Boolean = false,
    val scanning: Boolean = false,
    val warning: Boolean = false,
    val session: Int = 0,
)

enum class ConnectionResult { INVALID_ADDRESS, UNREACHABLE, RESPONDING }

interface HubDiscovery {
    fun observe(): Flow<DiscoverySnapshot>
    suspend fun connect(input: String): ConnectionResult
    suspend fun connect(hub: DiscoveredHub): ConnectionResult
}
