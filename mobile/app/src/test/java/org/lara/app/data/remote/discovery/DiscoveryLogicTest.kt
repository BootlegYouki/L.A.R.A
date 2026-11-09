package org.lara.app.data.remote.discovery

import org.junit.Assert.*
import org.junit.Test
import org.lara.app.domain.discovery.DiscoveredHub

class DiscoveryLogicTest {
    @Test fun manualInputValidatesOctetsPortsAndPolicy() {
        listOf("192.168.1.50", " 10.0.2.2:1 ", "172.31.255.255:65535", "127.0.0.1").forEach {
            assertNotNull(it, HubEndpoint.manual(it))
        }
        listOf("", " ", "192.168.1.256", "192.168.1", "192.168.01.2", "192.168.1.2:0",
            "192.168.1.2:65536", "192.168.1.2:", "192.168.1.2:-1", "192.168.1.2:123456",
            "http://192.168.1.2", "example.com", "8.8.8.8", "172.15.0.1", "172.32.0.1", "[::1]").forEach {
            assertNull(it, HubEndpoint.manual(it))
        }
        assertEquals(8080, HubEndpoint.manual("10.0.2.2")?.httpPort)
        assertEquals("ws://10.0.2.2:8081", HubEndpoint.manual("10.0.2.2:9000")?.webSocketUrl)
    }
    @Test fun discoveredEndpointsUseTheSameSecurityBoundary() {
        assertNull(HubEndpoint.lan("example.com"))
        assertNull(HubEndpoint.lan("8.8.8.8"))
        assertNull(HubEndpoint.lan("192.168.1.2", 8080, 65536))
        assertNull(HubEndpoint.lan("192.168.1.2", 0, 8081))
        assertNotNull(HubEndpoint.lan("172.16.0.1", 9000, 9001))
    }
    private val beacon = """{"app":"lara","version":"1.0.0-mock","name":"Class Hub","ip":"192.168.1.50","http_port":8080,"ws_port":8081}"""
    @Test fun parsesContractBeaconAndIgnoresFutureMetadata() {
        assertEquals(DiscoveredHub("Class Hub", "192.168.1.50", 8080, 8081), BeaconParser.parse(beacon))
        assertNotNull(BeaconParser.parse(beacon.dropLast(1) + ",\"extra\":true}"))
    }
    @Test fun malformedTruncatedAndDisallowedBeaconsAreIgnored() {
        listOf("", "{}", "[]", beacon.dropLast(1), beacon.replace("lara", "other"),
            beacon.replace("192.168.1.50", "8.8.8.8"), beacon.replace("8080", "65536"),
            beacon.replace("8081", "\"8081\""), beacon.replace("Class Hub", ""),
            beacon.replace("\"ws_port\":8081", "\"wrong_port\":8081")).forEach {
            assertNull(it, BeaconParser.parse(it))
        }
    }
    @Test fun deduplicatesSourcesAndExpiresWithoutSleeping() {
        val hub = DiscoveredHub("Class", "192.168.1.50", 8080, 8081)
        val registry = HubRegistry(ttlMs = 10)
        registry.seen("mdns:Class", hub, 0)
        registry.seen("udp:${hub.key}", hub, 1)
        assertEquals(listOf(hub), registry.snapshot(2))
        registry.remove("mdns:Class")
        assertEquals(listOf(hub), registry.snapshot(9))
        assertTrue(registry.snapshot(11).isEmpty())
        registry.seen("udp:${hub.key}", hub, 12)
        registry.seen("udp:${hub.key}", hub, 18)
        assertEquals(listOf(hub), registry.snapshot(22))
    }
}
