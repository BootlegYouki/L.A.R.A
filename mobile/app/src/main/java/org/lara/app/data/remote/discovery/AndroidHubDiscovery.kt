package org.lara.app.data.remote.discovery

import android.content.Context
import android.net.Network
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lara.app.domain.discovery.ConnectionResult
import org.lara.app.domain.discovery.DiscoveredHub
import org.lara.app.domain.discovery.DiscoverySnapshot
import org.lara.app.domain.discovery.HubDiscovery
import java.net.HttpURLConnection

class AndroidHubDiscovery(context: Context) : HubDiscovery {
    private val context = context.applicationContext
    private val store = HubEndpointStore(context)
    @Volatile private var wifi: Network? = null

    override fun observe(): Flow<DiscoverySnapshot> = channelFlow {
        var session = 0
        WifiMonitor(context).observe().distinctUntilChanged().collectLatest { network ->
            wifi = network
            if (network == null) { send(DiscoverySnapshot()); return@collectLatest }
            withContext(Dispatchers.Main.immediate) {
                session++
                val registry = HubRegistry()
                val lock = MulticastLockManager(context)
                var warning = !lock.acquire()
                val started = SystemClock.elapsedRealtime()
                var firstHubSeen = false
                fun publish() {
                    val now = SystemClock.elapsedRealtime()
                    trySend(DiscoverySnapshot(registry.snapshot(now), true, now - started < 3_000, warning, session))
                }
                fun error() { warning = true; publish() }
                fun accept(source: String, hub: DiscoveredHub) {
                    if (!firstHubSeen) {
                        firstHubSeen = true
                        Log.d("LaraDiscovery", "First Hub discovered after ${SystemClock.elapsedRealtime() - started} ms of scanning")
                    }
                    registry.seen(source, hub, SystemClock.elapsedRealtime())
                    publish()
                }
                publish()
                val nsd = launch {
                    NsdScanner(context).scan({ source, hub ->
                        accept("mdns:$source", hub)
                    }, { source -> registry.remove("mdns:$source"); publish() }, ::error)
                }
                val udp = launch {
                    UdpScanner().scan({ hub ->
                        // Registry and UI state have a single owner, even though UDP receives on IO.
                        launch(Dispatchers.Main.immediate) {
                            accept("udp:${hub.key}", hub)
                        }
                    }, { launch(Dispatchers.Main.immediate) { error() } })
                }
                try { while (isActive) { delay(1_000); publish() } }
                finally { nsd.cancel(); udp.cancel(); lock.release(); wifi = null }
            }
        }
    }

    override suspend fun connect(input: String): ConnectionResult {
        val endpoint = HubEndpoint.manual(input) ?: return ConnectionResult.INVALID_ADDRESS
        return probe(endpoint)
    }
    override suspend fun connect(hub: DiscoveredHub): ConnectionResult {
        val endpoint = HubEndpoint.lan(hub.ip, hub.httpPort, hub.wsPort) ?: return ConnectionResult.INVALID_ADDRESS
        return probe(endpoint)
    }
    private suspend fun probe(endpoint: HubEndpoint): ConnectionResult {
        val network = wifi
        val responds = HubProbe().responds(endpoint) { url ->
            (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        }
        return if (responds && network == wifi) {
            store.save(endpoint)
            ConnectionResult.RESPONDING
        } else ConnectionResult.UNREACHABLE
    }
}
