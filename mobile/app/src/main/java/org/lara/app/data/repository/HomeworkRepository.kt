package org.lara.app.data.repository

import androidx.room.withTransaction
import java.io.File
import java.util.UUID
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
    private val upload: suspend (String, String, File, Long) -> SubmissionDto, private val onQueued: () -> Unit) {
    private val mutex = Mutex()
    fun observe(assignmentId: String) = db.homeworkDao().observe(assignmentId, userId)

    suspend fun discardDraft(path: String) = mutex.withLock { withContext(Dispatchers.IO) {
        checkOwner()
        val file = File(path).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator))
        if (!db.homeworkDao().referencesFile(file.path)) file.delete()
    } }

    suspend fun turnIn(assignmentId: String, photo: File) = mutex.withLock { withContext(Dispatchers.IO) {
        val file = photo.canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator) && file.isFile && file.length() in 1 until HomeworkPhoto.MAX_BYTES)
        var previous: String? = null
        db.withTransaction {
            checkOwner()
            val assignment = requireNotNull(db.syncDao().assignment(assignmentId))
            require(db.syncDao().user(userId)?.role == "STUDENT")
            require(db.syncDao().classroom(assignment.classroomId)?.archivedAt == null)
            require(db.syncDao().enrollment(assignment.classroomId, userId)?.status == "ACTIVE")
            val old = db.homeworkDao().find(assignmentId, userId)
            previous = old?.filePath
            require(old?.score == null)
            val now = System.currentTimeMillis()
            val id = old?.id ?: UUID.randomUUID().toString()
            val hub = requireNotNull(db.syncStateDao().get("hub_id")).value
            val binding = db.syncStateDao().get("queued_hub:assignment_submissions:$id")?.value
            require(binding == null || binding == hub)
            db.homeworkDao().save(AssignmentSubmissionEntity(id, assignmentId, userId, file.path,
                submittedAt = now, score = old?.score, teacherFeedback = old?.teacherFeedback, updatedAt = now))
            db.syncStateDao().put(SyncStateEntity("queued_hub:assignment_submissions:$id", hub, now))
        }
        previous?.takeIf { it != file.path && it.isNotEmpty() }?.let { path ->
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
            val file = File(row.filePath).canonicalFile
            if (!file.path.startsWith(root.canonicalPath + File.separator) || !file.isFile || file.length() !in 1 until HomeworkPhoto.MAX_BYTES) {
                missingFile = true
                continue
            }
            try {
                val receipt = upload(row.assignmentId, row.id, file, row.submittedAt)
                db.withTransaction {
                    checkOwner()
                    require(db.syncStateDao().get("hub_id")?.value == expectedHub)
                    val current = db.homeworkDao().find(row.assignmentId, userId)
                    if (current == row) {
                        // Another device may already have created the one submission for this learner/assignment.
                        if (receipt.id != row.id) db.homeworkDao().adoptId(row.id, receipt.id)
                        db.homeworkDao().save(row.copy(id = receipt.id, syncStatus = "SYNCED", score = receipt.score,
                            teacherFeedback = receipt.teacherFeedback, submittedAt = receipt.submittedAt,
                            updatedAt = receipt.updatedAt ?: receipt.submittedAt, receivedAt = receipt.receivedAt))
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
