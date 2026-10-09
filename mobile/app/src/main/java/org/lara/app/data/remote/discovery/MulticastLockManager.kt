package org.lara.app.data.remote.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Holds the [WifiManager.MulticastLock] that Android requires before the Wi-Fi chip will deliver
 * UDP broadcast and multicast packets to the app.
 *
 * Why this exists (hardware workaround, `rules/networking-and-lan.md` §4.3): Android power
 * management drops incoming broadcast/multicast frames by default to save battery. Budget Transsion
 * (Infinix, TECNO, itel) and realme phones are the aggressive end of this behavior, so without the
 * lock the UDP beacon on `:8888` and `_lara._tcp.local` mDNS resolution silently never arrive and
 * the pupil is stuck typing an IP by hand.
 *
 * The lock is reference-counted and idempotent: [acquire] can be called by both the NSD and UDP
 * scanners and the chip stays enabled until the matching number of [release] calls. Call sites pair
 * acquire/release around the lifetime of discovery, never leaving it held (it keeps the radio warm
 * and drains the battery we are trying to protect).
 */
class MulticastLockManager(context: Context) {

    // Hold the application context so a short-lived Activity/ViewModel can't leak through this.
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var lock: WifiManager.MulticastLock? = null

    /**
     * Acquire (or increment the reference count on) the multicast lock. Safe to call repeatedly;
     * the first call creates the lock, later calls bump its reference count.
     */
    @Synchronized
    fun acquire() {
        val manager = wifiManager
        if (manager == null) {
            Log.w(TAG, "WifiManager unavailable; UDP/mDNS discovery may miss packets on this device")
            return
        }
        val current = lock ?: manager.createMulticastLock(LOCK_TAG).apply {
            setReferenceCounted(true)
        }.also { lock = it }
        current.acquire()
    }

    /**
     * Release one reference on the multicast lock. The Wi-Fi chip stops processing broadcast packets
     * only once every [acquire] has been matched by a [release]. Guards against an unbalanced release
     * so a double-stop can never throw.
     */
    @Synchronized
    fun release() {
        val current = lock ?: return
        if (current.isHeld) {
            current.release()
        }
    }

    private companion object {
        const val TAG = "MulticastLockManager"

        // Tag shown in `adb shell dumpsys wifi` so a held lock is traceable to this app.
        const val LOCK_TAG = "lara_discovery_lock"
    }
}
