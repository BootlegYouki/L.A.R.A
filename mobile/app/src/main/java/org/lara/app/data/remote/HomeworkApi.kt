package org.lara.app.data.remote

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.lara.app.data.remote.dto.SubmissionDto
import java.util.concurrent.TimeUnit

class HomeworkApi(private val api: EnrollmentApi) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun upload(userId: String, assignmentId: String, submissionId: String, file: File): SubmissionDto = withContext(Dispatchers.IO) {
        require(assignmentId.matches(Regex("[A-Za-z0-9-]{1,128}")))
        require(submissionId.matches(Regex("[A-Za-z0-9-]{1,128}")))
        val connection = api.connection(userId)
        val url = connection.endpoint.restBase + "/api/assignments/$assignmentId/submit"
        require(isAllowedHubUrl(url))
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("submission_id", submissionId)
            .addFormDataPart("file", "homework.jpg", file.asRequestBody("image/jpeg".toMediaType())).build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${connection.session.token}").post(body).build()
        client.newCall(request).execute().use { response ->
            val source = requireNotNull(response.body).source()
            source.request(65_537)
            require(source.buffer.size <= 65_536)
            val text = source.readUtf8()
            if (response.code != 200) {
                val code = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
                    ?.get("code")?.jsonPrimitive?.content }.getOrNull() ?: "UNKNOWN"
                throw HubFailure(response.code, code)
            }
            json.decodeFromString(SubmissionDto.serializer(), text).also {
                require(it.id.matches(Regex("[A-Za-z0-9-]{1,128}")) && it.assignmentId == assignmentId && it.studentId == userId && it.fileType == "IMAGE")
            }
        }
    }
}
