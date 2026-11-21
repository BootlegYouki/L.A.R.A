package org.lara.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.lara.app.data.remote.ClassworkAuthoringApi
import org.lara.app.data.remote.EnrollmentApi
import org.lara.app.data.remote.MATERIAL_LIMIT
import org.lara.app.data.sync.MaterialUploadWorker
import org.lara.app.domain.*

class ClassworkAuthoringRepository(private val context: Context, private val userId: String,
    private val sessions: EnrollmentApi, private val api: ClassworkAuthoringApi,
    private val refresh: suspend () -> Unit) : org.lara.app.domain.ClassworkAuthoringRepository {
    private val manager = WorkManager.getInstance(context)
    override suspend fun saveAssignment(classroomId: String, id: String?, draft: AssignmentDraft): String {
        require(draft.title.isNotBlank() && draft.maxPoints in 1..Int.MAX_VALUE && draft.dueDate > 0)
        val payload = buildJsonObject {
            if (id == null) put("classroom_id", classroomId)
            put("title", draft.title.trim()); put("instructions", draft.instructions.trim())
            put("max_points", draft.maxPoints); put("due_date", draft.dueDate); put("allow_late", draft.allowLate)
            if (id != null || draft.topicId != null) put("topic_id", draft.topicId?.let(::JsonPrimitive) ?: JsonNull)
        }
        val response = api.mutate(userId, "assignments", id, if (id == null) "POST" else "PATCH", payload)
        val saved = Json.parseToJsonElement(response).jsonObject.getValue("id").jsonPrimitive.content
        refresh()
        return saved
    }
    override suspend fun saveTopic(classroomId: String, id: String?, name: String) {
        require(name.isNotBlank())
        api.mutate(userId, "topics", id, if (id == null) "POST" else "PATCH", buildJsonObject {
            if (id == null) put("classroom_id", classroomId)
            put("name", name.trim())
        }); refresh()
    }
    override suspend fun saveMaterial(id: String, title: String, topicId: String?) {
        require(title.isNotBlank())
        api.mutate(userId, "materials", id, "PATCH", buildJsonObject {
            put("title", title.trim()); put("topic_id", topicId?.let(::JsonPrimitive) ?: JsonNull)
        }); refresh()
    }
    override suspend fun delete(kind: String, id: String) {
        api.mutate(userId, kind, id, "DELETE", null); refresh()
    }
    override suspend fun upload(classroomId: String, uri: String, draft: MaterialDraft) = withContext(Dispatchers.IO) {
        require(draft.title.isNotBlank())
        val connection = sessions.connection(userId)
        val hubId = api.uploadHubId(connection)
        val selected = Uri.parse(uri)
        require(selected.scheme == "content")
        var name = "attachment"
        context.contentResolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) require(it.getLong(sizeIndex) <= MATERIAL_LIMIT) { "FILE_TOO_LARGE" }
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = it.getString(nameIndex) ?: name
            }
        }
        val root = File(context.filesDir, "authoring-uploads").apply { mkdirs() }
        val file = File(root, UUID.randomUUID().toString() + "-" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(100))
        try {
            // Own the bytes before returning from the picker: durable retries need no provider grant.
            requireNotNull(context.contentResolver.openInputStream(selected)).use { input ->
                file.outputStream().use { output -> copyMaterial(input, output) }
            }
            require(file.length() > 0)
            val data = workDataOf("owner" to userId, "hub" to connection.endpoint.restBase, "hub_id" to hubId,
                "class" to classroomId, "file" to file.name, "title" to draft.title, "type" to draft.fileType,
                "topic" to draft.topicId, "assignment" to draft.assignmentId, "announcement" to draft.announcementId)
            manager.enqueue(request(data)).result.get()
            Unit
        } catch (error: Exception) { file.delete(); throw error }
    }
    override fun uploads(classroomId: String) = manager.getWorkInfosByTagFlow("authoring-$userId-$classroomId").map { rows ->
        val superseded = rows.flatMap { it.tags }.filter { it.startsWith("retry-of:") }.map { it.removePrefix("retry-of:") }.toSet()
        rows.filterNot { it.id.toString() in superseded }.sortedBy { it.id.toString() }.map { row -> AuthoringUpload(row.id.toString(),
            row.progress.getString("title") ?: row.outputData.getString("title").orEmpty(),
            row.state.name, if (row.state == WorkInfo.State.SUCCEEDED) 100 else row.progress.getInt("percent", 0)) }
    }
    override suspend fun retry(uploadId: String) = withContext(Dispatchers.IO) {
        val old = manager.getWorkInfoById(UUID.fromString(uploadId)).get() ?: return@withContext
        require(old.state == WorkInfo.State.FAILED && "authoring-$userId" in old.tags)
        val data = old.outputData
        require(data.getString("owner") == userId && data.getString("file") != null)
        manager.enqueueUniqueWork("authoring-retry-$uploadId", ExistingWorkPolicy.KEEP, request(data, uploadId)).result.get()
    }
    private fun request(data: Data, retryOf: String? = null) = OneTimeWorkRequestBuilder<MaterialUploadWorker>()
        .apply { retryOf?.let { addTag("retry-of:$it") } }.setInputData(data).addTag("authoring-$userId").addTag("authoring-$userId-${data.getString("class")}")
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
}

internal fun copyMaterial(input: java.io.InputStream, output: java.io.OutputStream) {
    val buffer = ByteArray(32 * 1024)
    var count = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return
        count += read
        require(count <= MATERIAL_LIMIT) { "FILE_TOO_LARGE" }
        output.write(buffer, 0, read)
    }
}
