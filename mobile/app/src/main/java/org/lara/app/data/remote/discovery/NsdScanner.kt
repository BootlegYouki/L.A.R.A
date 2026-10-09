package org.lara.app.data.remote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Discovers the classroom Hub over mDNS/DNS-SD. The Hub registers `_lara._tcp.local` on its HTTP
 * port (`rules/networking-and-lan.md` §2); this scanner finds those services and resolves each one
 * to an IP + port, emitting a [DiscoveredHub] with [DiscoverySource.MDNS].
 *
 * [discover] returns a cold [Flow]: nothing runs until it is collected, and when collection stops
 * (the screen leaves, the ViewModel clears, a timeout fires) the NSD discovery is torn down and the
 * [MulticastLockManager] reference is released. The multicast lock is required because NSD rides on
 * multicast, which budget phones drop by default (§4.3).
 */
class NsdScanner(
    context: Context,
    private val multicastLock: MulticastLockManager,
) {
    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    /**
     * Start DNS-SD discovery for [SERVICE_TYPE] and emit every resolved Hub. The flow never
     * completes on its own; the caller controls its lifetime (cancel to stop).
     */
    fun discover(): Flow<DiscoveredHub> = callbackFlow {
        val manager = nsdManager
        if (manager == null) {
            Log.w(TAG, "NsdManager unavailable; skipping mDNS discovery")
            close()
            return@callbackFlow
        }

        multicastLock.acquire()

        // Resolving is single-shot on older APIs: a resolver that is busy rejects the next request
        // with FAILURE_ALREADY_ACTIVE. Queue services and resolve them one at a time to stay safe
        // across the minSdk 26..targetSdk 35 range.
        val pendingResolves = ConcurrentLinkedQueue<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val next = pendingResolves.poll() ?: return
            resolving = true
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolving = false
                    toDiscoveredHub(info)?.let { trySend(it) }
                    resolveNext()
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.d(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
                    resolving = false
                    resolveNext()
                }
            })
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS discovery started for $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.trimEnd('.').endsWith(SERVICE_TYPE.trimEnd('.'))) {
                    pendingResolves.add(service)
                    resolveNext()
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // The Hub went away. The ViewModel ages entries out; nothing to emit here.
                Log.d(TAG, "mDNS service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Start mDNS discovery failed: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.d(TAG, "Stop mDNS discovery failed: $errorCode")
            }
        }

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: IllegalArgumentException) {
            // Thrown if a listener is somehow already registered; treat as a non-fatal no-op.
            Log.w(TAG, "discoverServices rejected the listener", e)
            close()
        }

        awaitClose {
            runCatching { manager.stopServiceDiscovery(discoveryListener) }
            multicastLock.release()
        }
    }

    private fun toDiscoveredHub(info: NsdServiceInfo): DiscoveredHub? {
        val host = info.resolvedHostAddress() ?: return null
        val port = if (info.port in 1..65_535) info.port else DiscoveredHub.DEFAULT_HTTP_PORT
        return DiscoveredHub(
            name = info.serviceName?.takeIf { it.isNotBlank() } ?: host,
            ip = host,
            httpPort = port,
            // NSD advertises only the HTTP service; the realtime broker is the next port by convention.
            wsPort = DiscoveredHub.DEFAULT_WS_PORT,
            source = DiscoverySource.MDNS,
            version = info.txtAttribute("version"),
        )
    }

    /** Prefer an IPv4 literal; the Hub serves plain `http://ip:port` and pupils type IPv4. */
    private fun NsdServiceInfo.resolvedHostAddress(): String? {
        @Suppress("DEPRECATION")
        val legacyHost = host
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
                    ?: hostAddresses.firstOrNull()?.hostAddress
                    ?: legacyHost?.hostAddress
            else -> legacyHost?.hostAddress
        }
    }

    private fun NsdServiceInfo.txtAttribute(key: String): String? =
        attributes[key]?.let { String(it, Charsets.UTF_8) }

    private companion object {
        const val TAG = "NsdScanner"

        // Android NSD expects the type with a trailing dot segment; "_lara._tcp." maps to
        // "_lara._tcp.local" on the wire (rules/networking-and-lan.md §2).
        const val SERVICE_TYPE = "_lara._tcp."
    }
}
