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
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SyncSchedulerTest {
    @Test fun reconnectWorkStartsWithoutWaitingForAnOfflineRetryChain() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val starts = AtomicInteger()
        val configuration = Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, parameters: WorkerParameters): ListenableWorker =
                    object : Worker(context, parameters) {
                        override fun doWork(): Result { starts.incrementAndGet(); return Result.retry() }
                    }
            }).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        val manager = WorkManager.getInstance(context)
        try {
            val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
            val scheduler = SyncScheduler(context)
            scheduler.enqueueNow()
            val original = manager.getWorkInfosForUniqueWork(SyncScheduler.NOW).get().single()
            driver.setAllConstraintsMet(original.id)
            assertEquals(1, manager.getWorkInfoById(original.id).get()?.runAttemptCount)
            assertEquals(1, starts.get())
            scheduler.enqueueReconnect()
            val reconnect = manager.getWorkInfosForUniqueWork(SyncScheduler.RECONNECT).get().single()
            assertNotEquals(original.id, reconnect.id)
            driver.setAllConstraintsMet(reconnect.id)
            assertEquals(2, starts.get())
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(original.id).get()?.state)
            assertEquals(1, manager.getWorkInfoById(original.id).get()?.runAttemptCount)
        } finally {
            manager.cancelAllWork().result.get()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
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
