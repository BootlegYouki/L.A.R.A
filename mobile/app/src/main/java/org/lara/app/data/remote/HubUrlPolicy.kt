package org.lara.app.data.remote

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** URL syntax and transport policy only; this does not authorize a remote Hub or resolve DNS. */
fun isAllowedHubUrl(value: String): Boolean {
    val uri = try {
        URI(value)
    } catch (_: URISyntaxException) {
        return false
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme !in setOf("http", "ws", "https", "wss")) return false
    val host = uri.host ?: return false
    if (uri.rawUserInfo != null || uri.port !in -1..65535 || uri.port == 0) return false
    if (uri.rawAuthority?.endsWith(":") == true) return false

    val octets = parseIpv4(host)
    if (host.all { it in '0'..'9' || it == '.' } && octets == null) return false
    if (scheme == "https" || scheme == "wss") return true
    if (octets == null) return false
    return octets[0] == 10 || octets[0] == 127 ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168)
}

private fun parseIpv4(host: String): List<Int>? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    return parts.map { part ->
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
        // Reject alternate octal-like spellings so callers cannot disagree on the destination.
        if (part.length > 1 && part.startsWith('0')) return null
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        octet
    }
}
