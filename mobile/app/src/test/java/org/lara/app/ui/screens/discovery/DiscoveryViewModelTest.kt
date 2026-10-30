package org.lara.app.ui.screens.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.lara.app.domain.discovery.*

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class Fake : HubDiscovery {
        val snapshots = MutableStateFlow(DiscoverySnapshot())
        var starts = 0
        var stops = 0
        var pending = false
        override fun observe() = flow {
            starts++
            try { snapshots.collect { emit(it) } } finally { stops++ }
        }
        override suspend fun connect(input: String): ConnectionResult {
            if (pending) awaitCancellation()
            return if (input.isBlank()) ConnectionResult.INVALID_ADDRESS else ConnectionResult.RESPONDING
        }
        override suspend fun connect(hub: DiscoveredHub) = connect(hub.ip)
    }
    @Test fun lifecycleAndReconnectionKeepOneSubscriptionAndClearStaleResults() = runTest(dispatcher) {
        val fake = Fake()
        val model = DiscoveryViewModel(fake)
        model.start(); model.start(); runCurrent()
        assertEquals(1, fake.starts)
        val hub = DiscoveredHub("Room", "192.168.1.1", 8080, 8081)
        fake.snapshots.value = DiscoverySnapshot(listOf(hub), true, true)
        runCurrent()
        assertEquals(listOf(hub), model.hubs.value)
        model.connect(hub); runCurrent()
        assertEquals(ConnectionResult.RESPONDING, model.result.value)
        fake.snapshots.value = DiscoverySnapshot(listOf(hub), true, false)
        runCurrent()
        assertEquals(ConnectionResult.RESPONDING, model.result.value)
        fake.snapshots.value = DiscoverySnapshot(listOf(hub), true, false, session = 1)
        runCurrent()
        assertNull(model.result.value)
        fake.snapshots.value = DiscoverySnapshot()
        runCurrent()
        assertNull(model.result.value)
        assertTrue(model.hubs.value.isEmpty())
        fake.snapshots.value = DiscoverySnapshot(wifiAvailable = true, scanning = true)
        runCurrent()
        assertTrue(model.state.value.scanning)
        model.retry(); runCurrent()
        assertEquals(2, fake.starts)
        assertEquals(1, fake.stops)
        model.stop(); runCurrent()
        assertEquals(2, fake.stops)
    }
    @Test fun invalidEntryAndCancelledCheckNeverReportSuccess() = runTest(dispatcher) {
        val fake = Fake()
        val model = DiscoveryViewModel(fake)
        model.connect(""); runCurrent()
        assertEquals(ConnectionResult.INVALID_ADDRESS, model.result.value)
        fake.pending = true
        model.connect("192.168.1.1"); runCurrent()
        assertTrue(model.connecting.value)
        model.stop(); runCurrent()
        assertFalse(model.connecting.value)
        assertNull(model.result.value)
    }
}
