package org.lara.app.data.remote.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

class MulticastLockManager(context: Context) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.MulticastLock? = null

    fun acquire(): Boolean = try {
        if (lock?.isHeld != true) {
            lock = wifi.createMulticastLock("lara_discovery_lock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d("LaraDiscovery", "Multicast lock acquired")
        }
        true
    } catch (_: RuntimeException) { release(); false }

    fun release() {
        try {
            if (lock?.isHeld == true) {
                lock?.release()
                Log.d("LaraDiscovery", "Multicast lock released")
            }
        } catch (_: RuntimeException) {
            Log.w("LaraDiscovery", "Multicast lock release failed")
        } finally { lock = null }
    }
}
