package org.lara.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** LAN Wi-Fi need not be internet-validated. Hub transport probes/authenticates each attempt itself. */
class SyncScheduler(context: Context) {
    private val app = context.applicationContext
    private val manager = WorkManager.getInstance(app)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private var started = false
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val networkChanges = changes.asSharedFlow()
    fun enqueueHomework() {
        manager.enqueueUniqueWork(HOMEWORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<HomeworkUploadWorker>().setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())
    }
    fun enqueueNow() {
        manager.enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, oneTime())
    }
    fun enqueueReconnect() {
        // Reconnection must not wait behind an offline outbox chain's retry backoff.
        manager.enqueueUniqueWork(RECONNECT, ExistingWorkPolicy.REPLACE, oneTime())
    }
    @Synchronized fun start() {
        if (started) return
        started = true
        // Old requests keep their battery constraint. The saved outbox will drain through delta sync.
        manager.cancelUniqueWork("lara-homework-upload")
        manager.cancelUniqueWork("lara-delta-sync-now")
        manager.cancelUniqueWork("lara-delta-sync-reconnect")
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DeltaSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())
        connectivity.registerNetworkCallback(networkRequest(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { changes.tryEmit(Unit); enqueueReconnect() }
                override fun onLost(network: Network) { changes.tryEmit(Unit) }
            })
    }
    companion object {
        const val NOW = "lara-delta-sync-now-v2"
        private const val HOMEWORK = "lara-homework-upload-v2"
        const val PERIODIC = "lara-delta-sync-periodic"
        const val RECONNECT = "lara-delta-sync-reconnect-v2"
        internal fun networkRequest() = NetworkRequest.Builder().build()
        internal fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        internal fun oneTime() = OneTimeWorkRequestBuilder<DeltaSyncWorker>().setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
    }
}
