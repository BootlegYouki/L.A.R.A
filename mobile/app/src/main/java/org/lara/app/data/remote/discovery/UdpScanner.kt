package org.lara.app.data.remote.discovery

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Listens for the Hub's UDP JSON beacon on port [BEACON_PORT] and emits a [DiscoveredHub] for every
 * valid packet. This is the discovery path that keeps working when mDNS is filtered but broadcast is
 * not; between this and [NsdScanner] a Hub is usually found within one beacon interval (~3 s).
 *
 * The socket requires the [MulticastLockManager] to be held so the Wi-Fi chip delivers broadcast
 * frames on budget phones (`rules/networking-and-lan.md` §4.3). [listen] is a cold [Flow]: the
 * socket opens on collection and is closed (unblocking the receive loop) plus the lock released on
 * cancellation. A malformed or foreign packet is skipped, never fatal.
 */
class UdpScanner(
    private val multicastLock: MulticastLockManager,
    private val json: Json = DefaultJson,
) {
    fun listen(): Flow<DiscoveredHub> = callbackFlow {
        multicastLock.acquire()

        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                // SO_BROADCAST is for sending; we only receive, but binding to the wildcard address
                // on the beacon port is what lets the broadcast datagrams reach us.
                broadcast = true
                soTimeout = SOCKET_TIMEOUT_MS
                bind(InetSocketAddress(BEACON_PORT))
            }
        } catch (e: SocketException) {
            Log.w(TAG, "Could not bind UDP :$BEACON_PORT for beacon discovery", e)
            multicastLock.release()
            close(e)
            return@callbackFlow
        }

        val buffer = ByteArray(MAX_PACKET_BYTES)
        val running = Thread {
            while (!Thread.currentThread().isInterrupted && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    // Idle interval; loop again so cancellation (socket close) is noticed promptly.
                    continue
                } catch (_: SocketException) {
                    // Expected when the socket is closed on cancellation; exit the loop quietly.
                    break
                } catch (e: Exception) {
                    Log.d(TAG, "UDP receive error", e)
                    continue
                }

                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val hub = parseBeacon(text, packet.address?.hostAddress)
                if (hub != null) {
                    trySend(hub)
                }
            }
        }.apply {
            name = "lara-udp-beacon"
            isDaemon = true
            start()
        }

        awaitClose {
            running.interrupt()
            socket.close()
            multicastLock.release()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseBeacon(text: String, senderIp: String?): DiscoveredHub? =
        try {
            json.decodeFromString<UdpBeacon>(text).toDiscoveredHub(senderIp)
        } catch (e: Exception) {
            // Any non-L.A.R.A or malformed broadcast on :8888 lands here; ignore it.
            Log.v(TAG, "Ignoring non-beacon UDP packet", e)
            null
        }

    private companion object {
        const val TAG = "UdpScanner"
        const val BEACON_PORT = 8888

        // Beacons are tiny; cap the buffer well above the documented payload to tolerate extra fields.
        const val MAX_PACKET_BYTES = 2048

        // Wake the blocking receive periodically so a cancelled flow tears down within ~1 s.
        const val SOCKET_TIMEOUT_MS = 1000

        // Lenient so an added beacon field never breaks discovery across versions.
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
