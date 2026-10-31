package org.lara.app.sync

import android.app.Application
import android.net.NetworkCapabilities
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test
import org.junit.Assert.*
import org.lara.app.data.sync.SyncScheduler
import androidx.work.NetworkType
import androidx.work.BackoffPolicy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SyncSchedulerTest {
    @Test fun lanWorkDoesNotRequireInternetValidationAndUsesBatteryGuardAndBackoff() {
        val request = SyncScheduler.oneTime()
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(10_000L, request.workSpec.backoffDelayDuration)
        val network = SyncScheduler.networkRequest()
        assertFalse(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }
}
