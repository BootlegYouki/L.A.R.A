package org.lara.app.data.remote

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import org.lara.app.domain.enrollment.*

internal fun reconnectDelayMillis(failures: Int): Long =
    (1_000L shl failures.coerceIn(0, 4)).coerceAtMost(10_000L)

internal fun decodeHubEvent(text: String): HubEvent? = runCatching {
    if (text.length > 65_536) return null
    val value = Json.parseToJsonElement(text).jsonObject
    fun string(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }
        ?.contentOrNull?.takeIf { it.isNotBlank() }
    fun number(key: String) = value[key]?.jsonPrimitive?.longOrNull
    when (string("event")) {
        "EVENT_HELLO_ACK" -> HubEvent.HelloAck(string("hub_id") ?: return null,
            number("sync_epoch") ?: return null, number("server_time") ?: return null)
        "EVENT_JOIN_APPROVAL" -> HubEvent.JoinApproval(string("classroom_id") ?: return null,
            string("student_id") ?: return null, EnrollmentStatus.valueOf(string("status") ?: return null).also {
                if (it == EnrollmentStatus.PENDING) return null
            }, number("timestamp") ?: return null)
        "EVENT_JOIN_REQUEST" -> HubEvent.JoinRequest(string("class_code") ?: return null,
            string("student_id") ?: return null, string("full_name") ?: return null,
            string("lrn") ?: return null, number("timestamp") ?: return null)
        else -> null
    }
}.getOrNull()

/** Foreground owners subscribe once per session; canceling the subscription releases its socket. */
class RealtimeClient : Closeable {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()

    fun events(connection: EnrollmentConnection, scope: CoroutineScope): SharedFlow<HubEvent> =
        observe(connection).shareIn(scope, SharingStarted.WhileSubscribed(0), replay = 0)

    internal fun observe(connection: EnrollmentConnection): Flow<HubEvent> = flow {
        require(isAllowedHubUrl(connection.endpoint.webSocketUrl))
        require(connection.session.hub == connection.endpoint.restBase)
        var failures = 0
        while (currentCoroutineContext().isActive) {
            if (connection.session.expiresAt <= System.currentTimeMillis()) {
                emit(HubEvent.SessionExpired)
                return@flow
            }
            var expired = false
            try {
                socket(connection).collect { event ->
                    if (event is HubEvent.HelloAck) failures = 0
                    if (event == HubEvent.SessionExpired) expired = true
                    emit(event)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: IOException) { /* Cached screens remain usable while reconnecting. */ }
            if (expired) return@flow
            delay(reconnectDelayMillis(failures))
            failures = (failures + 1).coerceAtMost(4)
        }
    }

    private fun socket(connection: EnrollmentConnection): Flow<HubEvent> = callbackFlow {
        val acknowledged = AtomicBoolean(false)
        val socket = client.newWebSocket(Request.Builder().url(connection.endpoint.webSocketUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val hello = buildJsonObject {
                        put("event", "EVENT_HELLO")
                        put("token", connection.session.token)
                    }
                    webSocket.send(hello.toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val event = decodeHubEvent(text)) {
                        is HubEvent.HelloAck -> { acknowledged.set(true); trySend(event) }
                        is HubEvent.JoinApproval -> if (acknowledged.get() && event.studentId == connection.session.userId) trySend(event)
                        is HubEvent.JoinRequest -> if (acknowledged.get()) trySend(event)
                        else -> Unit
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == 4401) { trySend(HubEvent.SessionExpired); channel.close() }
                    webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == 4401) trySend(HubEvent.SessionExpired)
                    channel.close()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) {
                        trySend(HubEvent.SessionExpired)
                        channel.close()
                    } else channel.close(IOException("Hub connection unavailable"))
                }
            })
        val handshakeTimeout = launch {
            delay(10_000)
            if (!acknowledged.get()) { channel.close(IOException("Hub handshake unavailable")); socket.cancel() }
        }
        awaitClose { handshakeTimeout.cancel(); socket.cancel() }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
