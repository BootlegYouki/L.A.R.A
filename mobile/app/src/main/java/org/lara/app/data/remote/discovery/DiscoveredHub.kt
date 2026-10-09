package org.lara.app.data.remote.discovery

/**
 * How a [DiscoveredHub] was found. The UI shows the same card for every source, but keeping the
 * origin lets us de-duplicate (NSD and the UDP beacon usually surface the same Hub) and lets manual
 * entries sort to the top of the list.
 */
enum class DiscoverySource {
    /** Android NSD resolved an `_lara._tcp.local` service. */
    MDNS,

    /** A UDP JSON beacon arrived on `:8888` (works even when mDNS is filtered). */
    UDP_BEACON,

    /** The teacher/pupil typed an IP because router AP isolation blocked both of the above. */
    MANUAL,
}

/**
 * One classroom Hub the client can connect to, normalized from any [DiscoverySource].
 *
 * [ip] + [httpPort] are the unicast REST/download endpoint (`http://ip:httpPort`); [wsPort] is the
 * realtime broker. The identity used for de-duplication is `ip:httpPort`, so the same Hub seen over
 * both NSD and the UDP beacon collapses to a single card ([key]).
 */
data class DiscoveredHub(
    val name: String,
    val ip: String,
    val httpPort: Int,
    val wsPort: Int,
    val source: DiscoverySource,
    val version: String? = null,
) {
    /** Stable identity across discovery sources: the unicast HTTP endpoint. */
    val key: String get() = "$ip:$httpPort"

    /** Base URL for REST + the captive `/download` portal. */
    val httpBaseUrl: String get() = "http://$ip:$httpPort"

    companion object {
        /** Default Hub ports from `rules/networking-and-lan.md` §2, used when a beacon omits them. */
        const val DEFAULT_HTTP_PORT: Int = 8080
        const val DEFAULT_WS_PORT: Int = 8081
    }
}
