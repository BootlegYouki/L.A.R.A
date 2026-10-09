package org.lara.app.data.remote.discovery

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * Single entry point for LAN Hub discovery (named in `mobile/docs/TECH_SPEC.md` §5). It fans the two
 * passive discovery sources into one stream of [DiscoveredHub]s:
 *
 *  - [NsdScanner] resolves `_lara._tcp.local` over mDNS.
 *  - [UdpScanner] listens for the JSON beacon on `:8888`.
 *
 * Both share one [MulticastLockManager], so the Wi-Fi chip is kept awake for broadcast/multicast
 * exactly while [discover] is collected and released as soon as collection stops. De-duplication of
 * the two sources (they usually surface the same Hub) and the manual-IP fallback live in the
 * ViewModel, which owns user-facing state; this class stays a thin, testable seam over the Android
 * discovery APIs.
 */
class HubDiscoveryService(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val multicastLock = MulticastLockManager(appContext)
    private val nsdScanner = NsdScanner(appContext, multicastLock)
    private val udpScanner = UdpScanner(multicastLock)

    /**
     * Cold flow of every Hub seen over mDNS or the UDP beacon. Nothing runs until collected; both
     * sources and the multicast lock are torn down when collection is cancelled.
     */
    fun discover(): Flow<DiscoveredHub> = merge(nsdScanner.discover(), udpScanner.listen())

    /**
     * Build a [DiscoveredHub] from a validated manual entry (`ip` or `ip:port`). Returns null if the
     * text does not match [MANUAL_ENTRY_REGEX] so the UI can show inline validation instead of
     * attempting a connection to garbage input.
     */
    fun manualHub(raw: String): DiscoveredHub? {
        val input = raw.trim()
        if (!MANUAL_ENTRY_REGEX.matches(input)) return null
        val (ip, portPart) = input.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
        if (!ip.isValidIpv4()) return null
        val httpPort = portPart?.toIntOrNull()?.takeIf { it in 1..65_535 }
            ?: DiscoveredHub.DEFAULT_HTTP_PORT
        return DiscoveredHub(
            name = ip,
            ip = ip,
            httpPort = httpPort,
            wsPort = DiscoveredHub.DEFAULT_WS_PORT,
            source = DiscoverySource.MANUAL,
        )
    }

    companion object {
        /**
         * Manual IP/port pattern from the issue acceptance criteria. The structural regex only bounds
         * digit counts; [isValidIpv4] additionally rejects octets above 255 (e.g. "999.1.1.1").
         */
        val MANUAL_ENTRY_REGEX = Regex("""^([0-9]{1,3}\.){3}[0-9]{1,3}(:[0-9]{1,5})?$""")

        /** True only when [input] (ip or ip:port) is a usable manual Hub address. */
        fun isValidManualEntry(input: String): Boolean {
            val trimmed = input.trim()
            if (!MANUAL_ENTRY_REGEX.matches(trimmed)) return false
            val parts = trimmed.split(":", limit = 2)
            if (!parts[0].isValidIpv4()) return false
            val port = parts.getOrNull(1)?.toIntOrNull() ?: return true
            return port in 1..65_535
        }

        private fun String.isValidIpv4(): Boolean {
            val octets = split(".")
            if (octets.size != 4) return false
            return octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true }
        }
    }
}
