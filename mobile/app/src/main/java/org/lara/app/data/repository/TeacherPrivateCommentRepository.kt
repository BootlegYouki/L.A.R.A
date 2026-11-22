package org.lara.app.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.PrivateCommentEntity
import org.lara.app.data.local.entities.SyncStateEntity
import org.lara.app.data.remote.PrivateCommentApi

/** Stores teacher thread snapshots in Room and keeps local, account-scoped read markers. */
class TeacherPrivateCommentRepository(
    private val db: LaraDatabase,
    private val userId: String,
    private val api: PrivateCommentApi,
) {
    fun comments(assignmentId: String, studentId: String): Flow<List<PrivateCommentEntity>> =
        db.streamDao().observePrivateComments(assignmentId).map { rows -> rows.filter { it.studentId == studentId } }

    suspend fun refresh(assignmentId: String, studentId: String) {
        val rows = api.list(userId, assignmentId, studentId).map {
            PrivateCommentEntity(it.id, it.assignmentId, it.studentId, it.authorId, it.content,
                it.createdAt, it.updatedAt ?: it.createdAt, "SYNCED")
        }
        db.withTransaction {
            db.cacheDao().upsertBatch(org.lara.app.data.local.dao.CacheBatch(privateComments = rows))
        }
    }

    suspend fun reply(assignmentId: String, studentId: String, content: String) {
        val dto = api.reply(userId, assignmentId, studentId, content)
        val row = PrivateCommentEntity(dto.id, dto.assignmentId, dto.studentId, dto.authorId,
            dto.content, dto.createdAt, dto.updatedAt ?: dto.createdAt, "SYNCED")
        db.withTransaction {
            db.cacheDao().upsertBatch(org.lara.app.data.local.dao.CacheBatch(privateComments = listOf(row)))
        }
    }

    fun accessDenied(assignmentId: String, studentId: String) =
        db.syncStateDao().observe(denialKey(assignmentId, studentId)).map { it?.value == "1" }

    suspend fun setAccessDenied(assignmentId: String, studentId: String, denied: Boolean) {
        val key = denialKey(assignmentId, studentId)
        if (denied) db.syncStateDao().put(SyncStateEntity(key, "1", System.currentTimeMillis()))
        else db.syncStateDao().remove(key)
    }

    private fun denialKey(assignmentId: String, studentId: String) =
        "teacher_private_denied:$assignmentId/$studentId"

    fun unreadThreads(classroomId: String) = db.streamDao().observeUnreadPrivateThreads(classroomId, READ_PREFIX)

    suspend fun markRead(assignmentId: String, studentId: String, commentIds: List<String>) {
        val ids = commentIds.toSet()
        db.withTransaction {
            val state = db.syncStateDao()
            if (state.get(denialKey(assignmentId, studentId))?.value == "1") return@withTransaction
            val hub = state.get("hub_id")?.value.orEmpty()
            val key = "$READ_PREFIX$hub/$assignmentId/$studentId/"
            val now = System.currentTimeMillis()
            db.streamDao().privateThread(assignmentId, studentId)
                .filter { it.authorId == studentId && it.id in ids }
                .forEach { state.put(SyncStateEntity(key + it.id, "1", now)) }
        }
    }

    companion object { private const val READ_PREFIX = "teacher_private_read:" }
}
