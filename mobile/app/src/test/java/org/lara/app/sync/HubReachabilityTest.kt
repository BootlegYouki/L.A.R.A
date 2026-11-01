package org.lara.app.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.lara.app.data.repository.observeHubReachability

@OptIn(ExperimentalCoroutinesApi::class)
class HubReachabilityTest {
    @Test fun networkChangesProbeImmediatelyInsteadOfWaitingForPeriodicPoll() = runTest {
        val changes = MutableSharedFlow<Unit>()
        var available = false
        val seen = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeHubReachability(changes) { available }.collect { seen.add(it) }
        }
        runCurrent()
        assertEquals(listOf(false), seen)
        advanceTimeBy(100)
        available = true
        changes.emit(Unit)
        runCurrent()
        assertEquals(listOf(false, true), seen)
        assertEquals(100L, currentTime)
        available = false
        changes.emit(Unit)
        runCurrent()
        assertEquals(listOf(false, true, false), seen)
        job.cancel()
    }
}
