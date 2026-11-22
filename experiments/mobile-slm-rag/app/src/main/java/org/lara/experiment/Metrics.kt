package org.lara.experiment

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug

/** Peak memory of this process, sampled every 500 ms while a run is going. Model weights are mmap'ed, so they show in PSS, not in the native heap. */
class MemSampler {
    @Volatile var peakPssMb = 0.0; private set
    @Volatile var peakJavaMb = 0.0; private set
    @Volatile var peakNativeMb = 0.0; private set
    @Volatile private var run = false
    private var th: Thread? = null

    fun sampleOnce() {
        val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        peakPssMb = maxOf(peakPssMb, mi.totalPss / 1024.0)
        peakJavaMb = maxOf(peakJavaMb, (rt.totalMemory() - rt.freeMemory()) / 1048576.0)
        peakNativeMb = maxOf(peakNativeMb, Debug.getNativeHeapAllocatedSize() / 1048576.0)
    }

    fun start() { run = true; th = Thread { while (run) { sampleOnce(); Thread.sleep(500) } }.also { it.isDaemon = true; it.start() } }
    fun stop() { run = false; th?.join(); sampleOnce() }
}

object Device {
    /** Battery temperature in degrees C (the system reports tenths), or NaN. */
    fun batteryTempC(c: Context): Double =
        c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 } ?: Double.NaN

    fun ramTotalMb(c: Context): Long = ActivityManager.MemoryInfo().also { (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.totalMem / 1048576
    fun ramAvailMb(c: Context): Long = ActivityManager.MemoryInfo().also { (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.availMem / 1048576
    val name: String get() = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, ${Build.SUPPORTED_ABIS.firstOrNull()})"
}
