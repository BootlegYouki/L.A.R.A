package org.lara.app.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.lara.app.data.remote.dto.PrivateCommentDto

/** Teacher access to the contract's per-learner assignment thread. */
class PrivateCommentApi(private val sessions: EnrollmentApi) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).build()

    private suspend fun request(userId: String, assignmentId: String): Request.Builder {
        require(validId(assignmentId))
        val connection = sessions.connection(userId)
        val url = connection.endpoint.restBase + "/api/assignments/$assignmentId/private-comments"
        require(isAllowedHubUrl(url))
        return Request.Builder().url(url).header("Authorization", "Bearer ${connection.session.token}")
    }

    suspend fun list(userId: String, assignmentId: String, studentId: String): List<PrivateCommentDto> = withContext(Dispatchers.IO) {
        require(validId(studentId))
        val builder = request(userId, assignmentId)
        val url = requireNotNull(builder.build().url.newBuilder().addQueryParameter("student_id", studentId).build())
        client.newCall(builder.url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw HubFailure(response.code, "PRIVATE_COMMENT_REQUEST_FAILED")
            val source = response.body?.source() ?: throw IOException("Missing response")
            source.request(512 * 1024 + 1L)
            if (source.buffer.size > 512 * 1024) throw IOException("Response too large")
            json.decodeFromString(ListSerializer(PrivateCommentDto.serializer()), source.readUtf8())
        }
    }

    suspend fun reply(userId: String, assignmentId: String, studentId: String, content: String): PrivateCommentDto = withContext(Dispatchers.IO) {
        require(validId(studentId) && content.isNotBlank() && content.length <= 4000)
        val body = buildJsonObject {
            put("student_id", studentId)
            put("content", content.trim())
        }.toString().toRequestBody("application/json".toMediaType())
        client.newCall(request(userId, assignmentId).post(body).build()).execute().use { response ->
            if (!response.isSuccessful) throw HubFailure(response.code, "PRIVATE_COMMENT_REQUEST_FAILED")
            val source = response.body?.source() ?: throw IOException("Missing response")
            source.request(65537)
            if (source.buffer.size > 65536) throw IOException("Response too large")
            json.decodeFromString(PrivateCommentDto.serializer(), source.readUtf8())
        }
    }

    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9-]{1,128}"))
}
