package org.lara.app.ui.screens.discovery

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lara.app.domain.discovery.ConnectionResult
import org.lara.app.domain.discovery.DiscoveredHub
import org.lara.app.domain.discovery.DiscoverySnapshot

class DiscoveryFeedbackTest {
    private val scanning = DiscoverySnapshot(wifiAvailable = true, scanning = true)

    @Test fun discoveryAloneNeverReportsConnected() {
        val discovered = scanning.copy(hubs = listOf(DiscoveredHub("Test Hub", "192.168.1.2", 8080, 8081)))
        assertEquals(HubBeacon.SEARCHING, hubBeacon(discovered, null, false))
        assertEquals(HubBeacon.NOT_CONNECTED, hubBeacon(discovered.copy(scanning = false), null, false))
    }

    @Test fun onlySuccessfulProbeReportsConnected() {
        assertEquals(HubBeacon.SEARCHING, hubBeacon(scanning, null, true))
        assertEquals(HubBeacon.CONNECTED, hubBeacon(scanning, ConnectionResult.RESPONDING, false))
        assertEquals(HubBeacon.NOT_CONNECTED, hubBeacon(scanning, ConnectionResult.UNREACHABLE, false))
        assertEquals(HubBeacon.NOT_CONNECTED, hubBeacon(scanning, ConnectionResult.INVALID_ADDRESS, false))
    }

    @Test fun lostConnectionAndInterruptedScanningDoNotReportSearching() {
        assertEquals(HubBeacon.NOT_CONNECTED, hubBeacon(DiscoverySnapshot(), null, false))
        assertEquals(HubBeacon.NOT_CONNECTED, hubBeacon(scanning.copy(warning = true), null, false))
    }
}
