package org.lara.app.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubUrlPolicyTest {
    @Test
    fun cleartextAllowsOnlyApprovedIpv4RangesIncludingTheirBoundaries() {
        val hosts = listOf(
            "10.0.0.0", "10.0.2.2", "10.42.1.8", "10.255.255.255",
            "172.16.0.0", "172.16.4.9", "172.31.6.7", "172.31.255.255",
            "192.168.0.0", "192.168.1.50", "192.168.255.255",
            "127.0.0.0", "127.0.0.1", "127.255.255.255",
        )
        for (scheme in listOf("http", "ws")) {
            for (host in hosts) {
                val url = "$scheme://$host:8080/api/test"
                assertTrue(url, isAllowedHubUrl(url))
            }
        }
    }

    @Test
    fun cleartextRejectsOutsideBoundariesPublicHostsAndLookalikes() {
        val hosts = listOf(
            "9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0",
            "192.167.255.255", "192.169.0.0", "126.255.255.255", "128.0.0.0",
            "8.8.8.8", "0.0.0.0", "169.254.1.1", "localhost", "hub.local",
            "10.0.0.1.example.com", "10.0.0.256", "192.168.1.-1", "10.1",
            "167772161", "0x0a000001", "010.0.0.1", "10.00.0.1", "10.0.0.1.",
            "10..0.1", "[::1]", "[::ffff:10.0.0.1]", "１０.0.0.1",
        )
        for (scheme in listOf("http", "ws")) {
            for (host in hosts) {
                val url = "$scheme://$host:8080"
                assertFalse(url, isAllowedHubUrl(url))
            }
        }
    }

    @Test
    fun secureUrlsAllowValidHostsWithoutDnsResolution() {
        for (scheme in listOf("https", "wss")) {
            for (host in listOf("hub-school.lara.app", "8.8.8.8", "192.168.1.50", "[::1]")) {
                val url = "$scheme://$host:443/api/test?cursor=0"
                assertTrue(url, isAllowedHubUrl(url))
            }
        }
        assertTrue(isAllowedHubUrl("HTTP://10.0.2.2:8080"))
    }

    @Test
    fun malformedUrlsUnsupportedSchemesAndAmbiguousAuthoritiesFailClosed() {
        for (url in listOf(
            "", "10.0.2.2", "//10.0.2.2", "http:/10.0.2.2", "http://",
            "http:///10.0.2.2", " http://10.0.2.2", "http://10.0.2.2/has space",
            "http://10.0.2.2\\@8.8.8.8", "http://10.0.2.2:abc", "http://10.0.2.2:",
            "http://10.0.2.2:0", "http://10.0.2.2:65536", "http://10.0.2.2:-2",
            "http://user:secret@10.0.2.2", "http://10.0.2.2@8.8.8.8",
            "http://%31%30.0.2.2", "https://256.1.1.1", "https://010.0.0.1",
            "https://bad_host", "https://", "wss://[invalid]", "ftp://10.0.2.2",
            "file:///10.0.2.2", "javascript:alert(1)", "http://10.0.2.2/\n",
        )) {
            assertFalse(url, isAllowedHubUrl(url))
        }
    }
}
