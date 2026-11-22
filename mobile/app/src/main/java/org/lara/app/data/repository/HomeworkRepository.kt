package org.lara.app.data.repository

import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.lara.app.data.local.entities.LocalSubmissionAttachment
import org.lara.app.data.local.entities.attachments
import org.lara.app.data.local.entities.withLocalPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.AssignmentSubmissionEntity
import org.lara.app.data.local.entities.SyncStateEntity
import org.lara.app.data.remote.HubFailure
import org.lara.app.data.remote.dto.SubmissionDto

class HomeworkRepository(private val db: LaraDatabase, private val root: File, private val userId: String,
    private val upload: suspend (String, String, List<File>, Long) -> SubmissionDto, private val onQueued: () -> Unit) {
    private val mutex = Mutex()
    fun observe(assignmentId: String) = db.homeworkDao().observe(assignmentId, userId)

    suspend fun discardDraft(path: String) = mutex.withLock { withContext(Dispatchers.IO) {
        checkOwner()
        val file = File(path).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator))
        if (!db.homeworkDao().referencesFile(file.path, Json.encodeToString(file.path))) file.delete()
    } }

    suspend fun turnIn(assignmentId: String, attachment: File) = turnIn(assignmentId, listOf(attachment))

    suspend fun turnIn(assignmentId: String, attachments: List<File>) = mutex.withLock { withContext(Dispatchers.IO) {
        require(attachments.size in 1..HomeworkAttachment.MAX_ATTACHMENTS)
        val files = attachments.map { it.canonicalFile }
        require(files.distinct().size == files.size && files.sumOf(File::length) <= HomeworkAttachment.MAX_DOCUMENT_BYTES)
        val records = files.map { file ->
            val type = requireNotNull(HomeworkAttachment.type(file))
            require(file.path.startsWith(root.canonicalPath + File.separator) && file.isFile &&
                file.length() in 1..HomeworkAttachment.maxBytes(file))
            LocalSubmissionAttachment(HomeworkAttachment.displayName(file), type, file.path)
        }
        val first = records.first()
        var previous = emptyList<String>()
        db.withTransaction {
            checkOwner()
            val assignment = requireNotNull(db.syncDao().assignment(assignmentId))
            require(db.syncDao().user(userId)?.role == "STUDENT")
            require(db.syncDao().classroom(assignment.classroomId)?.archivedAt == null)
            require(db.syncDao().enrollment(assignment.classroomId, userId)?.status == "ACTIVE")
            val old = db.homeworkDao().find(assignmentId, userId)
            previous = old?.attachments().orEmpty().mapNotNull { it.path }
            require(old?.score == null)
            val now = System.currentTimeMillis()
            val id = old?.id ?: UUID.randomUUID().toString()
            val hub = requireNotNull(db.syncStateDao().get("hub_id")).value
            val binding = db.syncStateDao().get("queued_hub:assignment_submissions:$id")?.value
            require(binding == null || binding == hub)
            db.homeworkDao().save(AssignmentSubmissionEntity(id, assignmentId, userId, requireNotNull(first.path), first.fileType,
                submittedAt = now, score = old?.score, teacherFeedback = old?.teacherFeedback, updatedAt = now, createdAt = old?.createdAt ?: now, attachmentsJson = Json.encodeToString(records)))
            db.syncStateDao().put(SyncStateEntity("queued_hub:assignment_submissions:$id", hub, now))
        }
        for (path in previous - files.map { it.path }.toSet()) {
            val oldFile = File(path).canonicalFile
            if (oldFile.path.startsWith(root.canonicalPath + File.separator)) oldFile.delete()
        }
        onQueued()
    } }

    suspend fun uploadQueued() = mutex.withLock { withContext(Dispatchers.IO) {
        checkOwner()
        var missingFile = false
        for (row in db.homeworkDao().queued(userId)) {
            val expectedHub = db.syncStateDao().get("hub_id")?.value ?: continue
            val binding = db.syncStateDao().get("queued_hub:assignment_submissions:${row.id}")?.value
            if (binding != null && binding != expectedHub) continue
            val records = row.attachments()
            val files = records.mapNotNull { record ->
                record.path?.let { File(it).canonicalFile }?.takeIf { file ->
                    file.path.startsWith(root.canonicalPath + File.separator) && file.isFile &&
                        HomeworkAttachment.type(file) == record.fileType && file.length() in 1..HomeworkAttachment.maxBytes(file)
                }
            }
            if (files.size != records.size) {
                missingFile = true
                continue
            }
            try {
                val receipt = upload(row.assignmentId, row.id, files, row.submittedAt)
                db.withTransaction {
                    checkOwner()
                    require(db.syncStateDao().get("hub_id")?.value == expectedHub)
                    val current = db.homeworkDao().find(row.assignmentId, userId)
                    if (current == row) {
                        // Another device may already have created the one submission for this learner/assignment.
                        if (receipt.id != row.id) db.homeworkDao().adoptId(row.id, receipt.id)
                        db.homeworkDao().save(row.copy(id = receipt.id, syncStatus = "SYNCED", score = receipt.score,
                            teacherFeedback = receipt.teacherFeedback, submittedAt = receipt.submittedAt,
                            updatedAt = receipt.updatedAt ?: receipt.submittedAt, receivedAt = receipt.receivedAt, attachmentsJson = if (receipt.attachments.isEmpty()) row.attachmentsJson
                                else Json.encodeToString(receipt.attachments.withLocalPaths(records))))
                        db.syncStateDao().put(SyncStateEntity("queued_hub:assignment_submissions:${receipt.id}", expectedHub, row.updatedAt))
                    }
                }
            } catch (error: HubFailure) {
                if (error.status !in setOf(403, 404, 409, 413, 422)) throw error
                db.withTransaction {
                    checkOwner()
                    if (db.homeworkDao().find(row.assignmentId, userId) == row) db.homeworkDao().save(
                        row.copy(syncStatus = "REJECTED", syncRejectReason = error.code))
                }
            }
        }
        if (missingFile) throw java.io.IOException("Queued homework file unavailable")
    } }

    private suspend fun checkOwner() { require(db.authDao().currentUserId() == userId) }
}
