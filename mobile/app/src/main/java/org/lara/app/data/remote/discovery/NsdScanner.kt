package org.lara.app.data.remote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lara.app.domain.discovery.DiscoveredHub
import kotlin.coroutines.resume

@Suppress("DEPRECATION") // Legacy resolution supports the project's API-26 minimum.
class NsdScanner(context: Context) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    suspend fun scan(onHub: (String, DiscoveredHub) -> Unit, onLost: (String) -> Unit, onError: () -> Unit) =
        withContext(Dispatchers.Main.immediate) {
            coroutineScope {
                var running = true
                var resolving = false
                val services = linkedMapOf<String, NsdServiceInfo>()
                val queue = Channel<NsdServiceInfo>(Channel.BUFFERED)
                fun key(info: NsdServiceInfo) = info.serviceName
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(type: String) = Unit
                    override fun onDiscoveryStopped(type: String) = Unit
                    override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { if (running) onError() } }
                    override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
                    override fun onServiceFound(info: NsdServiceInfo) {
                        main.post {
                            if (running && info.serviceType.trimEnd('.') == "_lara._tcp") {
                                if (services.size < 64 || services.containsKey(key(info))) {
                                    services[key(info)] = info
                                    queue.trySend(info)
                                }
                            }
                        }
                    }
                    override fun onServiceLost(info: NsdServiceInfo) {
                        main.post { if (running) { services.remove(key(info)); onLost(key(info)) } }
                    }
                }
                try {
                    // Android NSD supplies the .local domain; it takes _lara._tcp. here.
                    manager.discoverServices("_lara._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
                    val refresh = launch {
                        while (isActive) {
                            delay(4_000)
                            services.values.toList().forEach { queue.trySend(it) }
                        }
                    }
                    try {
                        for (info in queue) {
                            if (resolving || !services.containsKey(key(info))) continue
                            val result = withTimeoutOrNull(3_000) {
                                suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                                    val resolver = object : NsdManager.ResolveListener {
                                        override fun onResolveFailed(service: NsdServiceInfo, code: Int) {
                                            main.post { resolving = false; if (continuation.isActive) continuation.resume(null) }
                                        }
                                        override fun onServiceResolved(service: NsdServiceInfo) {
                                            main.post { resolving = false; if (continuation.isActive) continuation.resume(service) }
                                        }
                                    }
                                    resolving = true
                                    continuation.invokeOnCancellation {
                                        // Older APIs cannot cancel resolution; keep it serialized until its callback.
                                        if (Build.VERSION.SDK_INT >= 34) {
                                            main.post {
                                                try { manager.stopServiceResolution(resolver) } catch (_: RuntimeException) { }
                                                resolving = false
                                            }
                                        }
                                    }
                                    try { manager.resolveService(info, resolver) }
                                    catch (_: RuntimeException) { resolving = false; continuation.resume(null) }
                                }
                            }
                            if (result == null) { onError(); continue }
                            val endpoint = result.host?.hostAddress?.let { HubEndpoint.lan(it, result.port) }
                            if (running && services.containsKey(key(info)) && endpoint != null) {
                                onHub(key(info), DiscoveredHub(result.serviceName, endpoint.ip, endpoint.httpPort, endpoint.wsPort))
                            }
                        }
                    } finally { refresh.cancel() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RuntimeException) { onError() }
                finally {
                    running = false
                    queue.close()
                    try { manager.stopServiceDiscovery(listener) } catch (_: RuntimeException) { }
                    services.clear()
                }
            }
        }
}
