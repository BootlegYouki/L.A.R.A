package org.lara.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import androidx.work.*
import java.util.concurrent.TimeUnit

/** LAN Wi-Fi need not be internet-validated. Hub transport probes/authenticates each attempt itself. */
class SyncScheduler(context: Context) {
    private val app = context.applicationContext
    private val manager = WorkManager.getInstance(app)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private var started = false
    fun enqueueNow() {
        manager.enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, oneTime())
    }
    @Synchronized fun start() {
        if (started) return
        started = true
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeltaSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())
        connectivity.registerNetworkCallback(networkRequest(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { enqueueNow() }
            })
    }
    companion object {
        const val NOW = "lara-delta-sync-now"
        const val PERIODIC = "lara-delta-sync-periodic"
        internal fun networkRequest() = NetworkRequest.Builder().build()
        internal fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true).build()
        internal fun oneTime() = OneTimeWorkRequestBuilder<DeltaSyncWorker>().setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
    }
}
