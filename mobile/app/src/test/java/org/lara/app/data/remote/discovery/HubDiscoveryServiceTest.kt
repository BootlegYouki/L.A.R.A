package org.lara.app.data.remote.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic guards for the manual IP fallback (issue acceptance criterion 3: a manual IP must
 * connect immediately when router isolation blocks discovery) and the beacon mapping. These are the
 * parts of discovery that do not need an Android device, so they run on the JVM in `./gradlew test`.
 */
class HubDiscoveryServiceTest {

    @Test
    fun acceptsPlainIp() {
        assertTrue(HubDiscoveryService.isValidManualEntry("192.168.1.50"))
    }

    @Test
    fun acceptsIpWithPort() {
        assertTrue(HubDiscoveryService.isValidManualEntry("192.168.1.50:8080"))
    }

    @Test
    fun rejectsOctetAbove255() {
        // Structurally the regex matches, but 999 is not a valid octet.
        assertFalse(HubDiscoveryService.isValidManualEntry("999.1.1.1"))
    }

    @Test
    fun rejectsIncompleteIp() {
        assertFalse(HubDiscoveryService.isValidManualEntry("192.168.1."))
        assertFalse(HubDiscoveryService.isValidManualEntry("192.168.1"))
    }

    @Test
    fun rejectsNonNumeric() {
        assertFalse(HubDiscoveryService.isValidManualEntry("hub.local"))
        assertFalse(HubDiscoveryService.isValidManualEntry(""))
    }

    @Test
    fun rejectsPortOutOfRange() {
        assertFalse(HubDiscoveryService.isValidManualEntry("192.168.1.50:70000"))
    }

    @Test
    fun regexMatchesIssuePattern() {
        // The exact pattern from the issue acceptance checklist.
        val issueRegex = Regex("""^([0-9]{1,3}\.){3}[0-9]{1,3}(:[0-9]{1,5})?$""")
        assertEquals(issueRegex.pattern, HubDiscoveryService.MANUAL_ENTRY_REGEX.pattern)
    }

    @Test
    fun beaconMapsToHubWithDefaults() {
        val hub = UdpBeacon(app = "lara", name = "Grade 4 - Science", ip = "192.168.1.50")
            .toDiscoveredHub(senderIp = "192.168.1.50")
        assertEquals("192.168.1.50", hub?.ip)
        assertEquals(DiscoveredHub.DEFAULT_HTTP_PORT, hub?.httpPort)
        assertEquals(DiscoverySource.UDP_BEACON, hub?.source)
    }

    @Test
    fun beaconFromForeignAppIsIgnored() {
        val hub = UdpBeacon(app = "not-lara", ip = "192.168.1.50")
            .toDiscoveredHub(senderIp = "192.168.1.50")
        assertNull(hub)
    }

    @Test
    fun beaconFallsBackToSenderIpWhenMissing() {
        val hub = UdpBeacon(app = "lara", ip = null).toDiscoveredHub(senderIp = "192.168.1.77")
        assertEquals("192.168.1.77", hub?.ip)
    }
}
