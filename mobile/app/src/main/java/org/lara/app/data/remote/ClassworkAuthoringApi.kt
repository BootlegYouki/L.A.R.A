package org.lara.app.data.remote

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.lara.app.domain.MaterialDraft

internal const val MATERIAL_LIMIT = 250L * 1024 * 1024

class ClassworkAuthoringApi(private val sessions: EnrollmentApi) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()

    suspend fun mutate(userId: String, kind: String, id: String?, method: String, payload: JsonObject?): String = withContext(Dispatchers.IO) {
        require(kind in setOf("assignments", "materials", "topics", "announcements"))
        require(id == null || id.matches(Regex("[A-Za-z0-9-]{1,128}")))
        require(method in setOf("POST", "PATCH", "DELETE"))
        val c = sessions.connection(userId)
        val url = c.endpoint.restBase + "/api/$kind" + (id?.let { "/$it" } ?: "")
        require(isAllowedHubUrl(url))
        val body = payload?.toString()?.toRequestBody("application/json".toMediaType())
        execute(Request.Builder().url(url).header("Authorization", "Bearer ${c.session.token}")
            .method(method, body).build())
    }

    private val hubClient = HubClient()

    internal suspend fun uploadHubId(connection: EnrollmentConnection): String = withContext(Dispatchers.IO) {
        readHubId(connection)
    }

    private fun readHubId(connection: EnrollmentConnection): String {
        val response = hubClient.sync(connection, "pull", """{"cursor":0}""")
        return requireNotNull(Json.parseToJsonElement(response).jsonObject["hub_id"]?.jsonPrimitive?.contentOrNull)
            .also { require(it.isNotBlank()) }
    }

    suspend fun upload(userId: String, hub: String, classroomId: String, file: File, draft: MaterialDraft,
        hubId: String? = null, onSending: () -> Unit = {}, validateReceipt: Boolean = false,
        progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        require(file.length() in 1..MATERIAL_LIMIT)
        require(draft.fileType in setOf("DOCUMENT", "VIDEO", "WORKSHEET"))
        require(draft.assignmentId == null || draft.announcementId == null)
        val c = sessions.connection(userId)
        val currentAddress = c.endpoint.restBase
        if (hubId != null) require(readHubId(c) == hubId)
        else require(currentAddress == hub)
        val url = currentAddress + "/api/materials"
        require(isAllowedHubUrl(url))
        val streaming = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: BufferedSink) {
                onSending()
                file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var sent = 0L
                    var last = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        sent += count
                        val percent = (sent * 100 / contentLength()).toInt().coerceAtMost(99)
                        if (percent != last) { last = percent; progress(percent) }
                    }
                }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("classroom_id", classroomId).addFormDataPart("title", draft.title)
            .addFormDataPart("file_type", draft.fileType).apply {
                draft.topicId?.let { addFormDataPart("topic_id", it) }
                draft.assignmentId?.let { addFormDataPart("assignment_id", it) }
                draft.announcementId?.let { addFormDataPart("announcement_id", it) }
            }.addFormDataPart("file", file.name.substringAfter('-'), streaming).build()
        val receipt = executeUpload(Request.Builder().url(url).header("Authorization", "Bearer ${c.session.token}").post(body).build())
        if (validateReceipt) {
            val created = Json.parseToJsonElement(receipt).jsonObject
            require(!created["id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
            require(created["classroom_id"]?.jsonPrimitive?.contentOrNull == classroomId)
            require(created["file_type"]?.jsonPrimitive?.contentOrNull == draft.fileType)
            require(created["file_size_bytes"]?.jsonPrimitive?.longOrNull == file.length())
        }
        progress(100)
    }

    private suspend fun executeUpload(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: java.io.IOException) {
                continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                runCatching { response.use(::readResponse) }.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    private fun execute(request: Request): String = client.newCall(request).execute().use(::readResponse)

    private fun readResponse(response: Response): String {
        val source = requireNotNull(response.body).source()
        source.request(65_537)
        require(source.buffer.size <= 65_536)
        val text = source.readUtf8()
        if (response.code != 200) {
            val code = runCatching { Json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
                ?.get("code")?.jsonPrimitive?.content }.getOrNull() ?: "UNKNOWN"
            throw HubFailure(response.code, code)
        }
        return text
    }
}
