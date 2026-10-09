package org.lara.app.data.remote.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Reports Wi-Fi availability via [ConnectivityManager.NetworkCallback] so discovery can restart when
 * the phone (re)joins the classroom network (`mobile/AGENTS.md` "Discovery": re-connect on a
 * `ConnectivityManager.NetworkCallback`).
 *
 * Budget phones hop between the school AP and mobile data constantly; when a pupil walks back into
 * Wi-Fi range the discovery flow needs to re-run so the Hub reappears within seconds without the
 * user tapping anything. [wifiAvailability] is a cold [Flow] of booleans (true = a Wi-Fi transport
 * with internet-less local connectivity is available); the ViewModel restarts its scan on each
 * `true`.
 */
class NetworkMonitor(context: Context) {
    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Emits `true` when a Wi-Fi network becomes available and `false` when it is lost. The first
     * emission reflects the current state so a collector started while already on Wi-Fi scans
     * immediately. Conflated because only the latest state matters.
     */
    fun wifiAvailability(): Flow<Boolean> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            Log.w(TAG, "ConnectivityManager unavailable; auto-reconnect disabled")
            trySend(false)
            close()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }

        // Local-only classrooms have no internet, so we must NOT require NET_CAPABILITY_INTERNET;
        // we only care that a Wi-Fi transport exists to reach the Hub.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            manager.registerNetworkCallback(request, callback)
        } catch (e: RuntimeException) {
            // Too many callbacks registered (framework cap) or a transient framework error.
            Log.w(TAG, "registerNetworkCallback failed", e)
            trySend(false)
            close(e)
            return@callbackFlow
        }

        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.conflate()

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
