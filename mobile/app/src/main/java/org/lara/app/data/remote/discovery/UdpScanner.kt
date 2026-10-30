package org.lara.app.data.remote.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import org.lara.app.domain.discovery.DiscoveredHub

class UdpScanner {
    suspend fun scan(onHub: (DiscoveredHub) -> Unit, onError: () -> Unit) = withContext(Dispatchers.IO) {
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.soTimeout = 500
                socket.bind(InetSocketAddress(8888))
                val buffer = ByteArray(4097)
                while (currentCoroutineContext().isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        if (packet.length <= 4096) {
                            BeaconParser.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
                                ?.let(onHub)
                        }
                    } catch (_: SocketTimeoutException) { /* Bound cancellation latency on old Android. */ }
                }
            }
        } catch (_: java.io.IOException) { onError() }
        catch (_: SecurityException) { onError() }
    }
}
