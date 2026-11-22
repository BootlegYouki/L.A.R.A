package org.lara.app.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.AssignmentSubmissionEntity
import org.lara.app.data.local.entities.SyncStateEntity
import org.lara.app.data.remote.dto.SubmissionDto
import org.lara.app.data.remote.HubFailure

@Serializable
internal data class QueuedGrade(
    val submissionId: String,
    val assignmentId: String,
    val learnerId: String,
    val hubId: String,
    val score: Long,
    val feedback: String,
    val expectedUpdatedAt: Long,
    val rejected: Boolean = false,
)

internal class GradeOutbox(
    private val db: LaraDatabase,
    private val send: suspend (QueuedGrade) -> SubmissionDto,
    private val userId: String,
    private val enqueue: () -> Unit,
) {
    private val mutex = Mutex()
    private val state = db.syncStateDao()

    fun observe() = state.observePrefix(PREFIX).map { rows ->
        rows.map { Json.decodeFromString<QueuedGrade>(it.value) }.associateBy { it.submissionId }
    }

    suspend fun save(submission: AssignmentSubmissionEntity, score: Long, feedback: String) = mutex.withLock {
        db.withTransaction {
            check(db.authDao().currentUserId() == userId)
            val hubId = requireNotNull(state.get("hub_id")).value
            val key = PREFIX + submission.id
            val previous = state.get(key)?.let { Json.decodeFromString<QueuedGrade>(it.value) }
            require(previous == null || previous.hubId == hubId)
            val item = QueuedGrade(submission.id, submission.assignmentId, submission.studentId, hubId, score,
                feedback, previous?.takeUnless { it.rejected }?.expectedUpdatedAt ?: submission.updatedAt)
            state.put(SyncStateEntity(key, Json.encodeToString(item), System.currentTimeMillis()))
        }
        enqueue()
    }

    suspend fun flush() = mutex.withLock {
        check(db.authDao().currentUserId() == userId)
        for (entry in state.entries(PREFIX)) {
            val item = Json.decodeFromString<QueuedGrade>(entry.value)
            if (item.rejected || state.get("hub_id")?.value != item.hubId) continue
            try {
                val receipt = send(item)
                require(receipt.id == item.submissionId && receipt.assignmentId == item.assignmentId &&
                    receipt.studentId == item.learnerId && receipt.score == item.score &&
                    receipt.teacherFeedback == item.feedback)
                db.withTransaction {
                    check(db.authDao().currentUserId() == userId && state.get("hub_id")?.value == item.hubId)
                    val current = db.syncDao().submission(item.submissionId)
                    if (current != null && (receipt.updatedAt ?: current.updatedAt) >= current.updatedAt) {
                        db.homeworkDao().save(current.copy(score = receipt.score, teacherFeedback = receipt.teacherFeedback,
                            updatedAt = receipt.updatedAt ?: current.updatedAt))
                    }
                    state.remove(entry.key)
                }
            } catch (error: HubFailure) {
                if (error.status !in setOf(400, 403, 404, 409, 422)) throw error
                state.put(entry.copy(value = Json.encodeToString(item.copy(rejected = true))))
            }
        }
    }

    private companion object { const val PREFIX = "pending_submission_grade:" }
}
