package org.lara.app.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.lara.app.domain.VideoFailure
import org.lara.app.domain.VideoProblem
import org.lara.app.data.media.DownloadManager
import org.lara.app.data.media.MediaResponse
import kotlin.coroutines.resumeWithException

internal data class ByteRange(val start: Long, val end: Long, val total: Long) {
    companion object {
        fun parse(value: String?): ByteRange? {
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty()) ?: return null
            val (start, end, total) = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
            return if (start >= 0 && end >= start && end < total) ByteRange(start, end, total) else null
        }
    }
}

/** One selected endpoint, no redirects, and credentials rechecked on every media request. */
internal class MediaHttp(private val api: EnrollmentApi, private val userId: String) {
    private val clients = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()
    @androidx.media3.common.util.UnstableApi
    fun stream(id: String, size: Long): Pair<android.net.Uri, androidx.media3.datasource.DataSource.Factory> {
        require(id.matches(Regex("[A-Za-z0-9-]{1,128}")))
        val c = runBlocking(kotlinx.coroutines.Dispatchers.IO) { connection() }
        val uri = android.net.Uri.parse("${c.endpoint.restBase}/api/materials/$id/stream")
        val upstream = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client(c.endpoint.restBase))
        return uri to androidx.media3.datasource.DataSource.Factory { VideoDataSource(upstream.createDataSource(), size) }
    }
    fun downloads(root: java.io.File) = DownloadManager(root, { id, start, end, validator ->
        val response = open(id, start, end, validator)
        object : MediaResponse {
            override val status = response.code
            override fun header(name: String) = response.header(name)
            override val input = requireNotNull(response.body).byteStream()
            override fun close() = response.close()
        }
    })
    suspend fun connection() = try { api.connection(userId) }
        catch (_: HubFailure) { throw VideoFailure(VideoProblem.SIGN_IN) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: IOException) { throw e }
        catch (_: Exception) { throw VideoFailure(VideoProblem.SIGN_IN) }

    fun client(base: String): OkHttpClient = clients.computeIfAbsent(base) { OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val c = runBlocking { connection() }
            val request = chain.request()
            val url = request.url.toString()
            if (c.endpoint.restBase != base || !isAllowedHubUrl(url) ||
                !url.startsWith("$base/api/materials/") || request.url.encodedQuery != null ||
                !request.url.encodedPath.matches(Regex("/api/materials/[A-Za-z0-9-]{1,128}/(stream|download)")))
                throw VideoFailure(VideoProblem.SIGN_IN)
            val response = chain.proceed(request.newBuilder()
                .header("Authorization", "Bearer ${c.session.token}")
                .header("Accept-Encoding", "identity").build())
            if (response.code == 401 || response.code == 403) {
                response.close(); throw VideoFailure(VideoProblem.SIGN_IN)
            }
            response
        }.build() }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun open(id: String, start: Long, end: Long, validator: String?): Response {
        require(id.matches(Regex("[A-Za-z0-9-]{1,128}")))
        val c = connection()
        val request = Request.Builder().url("${c.endpoint.restBase}/api/materials/$id/download")
            .header("Range", "bytes=$start-$end")
            .apply { validator?.let { header("If-Range", it) } }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client(c.endpoint.restBase).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response, onCancellation = { response.close() })
                }
            })
        }
    }
}
