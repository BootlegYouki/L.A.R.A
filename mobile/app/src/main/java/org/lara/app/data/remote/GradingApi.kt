package org.lara.app.data.remote

import java.io.File
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
import org.lara.app.data.remote.dto.SubmissionDto

class GradingApi(private val sessions: EnrollmentApi) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).build()

    private suspend fun request(userId: String, path: String): Request.Builder {
        val connection = sessions.connection(userId)
        val url = connection.endpoint.restBase + path
        require(isAllowedHubUrl(url))
        return Request.Builder().url(url).header("Authorization", "Bearer ${connection.session.token}")
    }

    suspend fun list(userId: String, assignmentId: String): List<SubmissionDto> = withContext(Dispatchers.IO) {
        require(validId(assignmentId))
        client.newCall(request(userId, "/api/assignments/$assignmentId/submissions").get().build()).execute().use { response ->
            if (!response.isSuccessful) throw HubFailure(response.code, "GRADING_REQUEST_FAILED")
            val source = response.body?.source() ?: throw IOException("Missing response")
            source.request(1024 * 1024 + 1L)
            if (source.buffer.size > 1024 * 1024) throw IOException("Response too large")
            json.decodeFromString(ListSerializer(SubmissionDto.serializer()), source.readUtf8())
        }
    }

    suspend fun grade(userId: String, submissionId: String, score: Long, feedback: String): SubmissionDto = withContext(Dispatchers.IO) {
        require(validId(submissionId) && score >= 0)
        val body = buildJsonObject { put("score", score); put("teacher_feedback", feedback) }
            .toString().toRequestBody("application/json".toMediaType())
        client.newCall(request(userId, "/api/submissions/$submissionId").patch(body).build()).execute().use { response ->
            if (!response.isSuccessful) throw HubFailure(response.code, "GRADING_REQUEST_FAILED")
            val source = response.body?.source() ?: throw IOException("Missing response")
            source.request(65537)
            if (source.buffer.size > 65536) throw IOException("Response too large")
            json.decodeFromString(SubmissionDto.serializer(), source.readUtf8())
        }
    }

    suspend fun file(userId: String, submissionId: String, destination: File, maxBytes: Long = 20L * 1024 * 1024) = withContext(Dispatchers.IO) {
        require(validId(submissionId))
        try {
            client.newCall(request(userId, "/api/submissions/$submissionId/file").get().build()).execute().use { response ->
                if (!response.isSuccessful) throw HubFailure(response.code, "GRADING_REQUEST_FAILED")
                val body = response.body ?: throw IOException("Missing file")
                body.byteStream().use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > maxBytes) throw IOException("File too large")
                        output.write(buffer, 0, count)
                    }
                    if (size == 0L) throw IOException("Empty file")
                } }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9-]{1,128}"))
}
