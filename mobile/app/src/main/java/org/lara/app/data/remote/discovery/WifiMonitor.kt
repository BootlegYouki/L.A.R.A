package org.lara.app.data.remote.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

class WifiMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    fun observe() = callbackFlow<Network?> {
        val networks = linkedSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networks.add(network); trySend(network) }
            override fun onLost(network: Network) { networks.remove(network); trySend(networks.lastOrNull()) }
        }
        // No INTERNET/VALIDATED requirement: isolated classroom Wi-Fi is sufficient.
        val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try {
            trySend(null)
            manager.registerNetworkCallback(request, callback)
        } catch (_: RuntimeException) { close(); return@callbackFlow }
        awaitClose { try { manager.unregisterNetworkCallback(callback) } catch (_: RuntimeException) { } }
    }
}
