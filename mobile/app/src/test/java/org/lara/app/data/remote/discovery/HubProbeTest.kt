package org.lara.app.data.remote.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class HubProbeTest {
    private class Response(private val code: Int, private val type: String?) : HttpURLConnection(URL("http://127.0.0.1")) {
        var released = false
        override fun connect() = Unit
        override fun disconnect() { released = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getContentType() = type
    }
    @Test fun checksExistingPortalAndAlwaysReleasesConnection() = runBlocking {
        val endpoint = requireNotNull(HubEndpoint.manual("127.0.0.1:9000"))
        val response = Response(200, "text/html; charset=utf-8")
        assertTrue(HubProbe().responds(endpoint) { url ->
            assertEquals("http://127.0.0.1:9000/download", url.toString())
            response
        })
        assertTrue(response.released)
        assertFalse(response.instanceFollowRedirects)
        assertEquals(3000, response.connectTimeout)
        assertEquals(3000, response.readTimeout)
    }
    @Test fun redirectsErrorsAndNonPortalResponsesNeverCountAsSuccess() = runBlocking {
        val endpoint = requireNotNull(HubEndpoint.manual("127.0.0.1"))
        for (response in listOf(Response(302, "text/html"), Response(500, "text/html"), Response(200, "application/json"), Response(200, null))) {
            assertFalse(HubProbe().responds(endpoint) { response })
            assertTrue(response.released)
        }
        assertFalse(HubProbe().responds(endpoint) { throw java.net.SocketTimeoutException() })
        assertFalse(HubProbe().responds(endpoint) { throw SecurityException() })
    }
}
