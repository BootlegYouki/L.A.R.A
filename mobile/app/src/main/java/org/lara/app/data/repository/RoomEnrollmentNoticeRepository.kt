package org.lara.app.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.entities.SyncStateEntity
import org.lara.app.domain.enrollment.*

@Serializable
private data class SavedEnrollmentNotice(val classroomId: String, val title: String?,
    val status: String, val hubId: String?)

/** The join receipt has no enrollment ID. Persist its metadata without inventing a membership row. */
class RoomEnrollmentNoticeRepository(private val database: LaraDatabase) : EnrollmentNoticeRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefix(userId: String) = "enrollment_notice:$userId:"
    override fun notices(userId: String) = combine(database.syncStateDao().observePrefix(prefix(userId)),
        database.syncStateDao().observe("hub_id")) { rows, hub ->
        rows.mapNotNull { row -> runCatching {
            val saved = json.decodeFromString<SavedEnrollmentNotice>(row.value)
            if (saved.hubId != null && saved.hubId != hub?.value) null
            else EnrollmentNotice(saved.classroomId, saved.title, EnrollmentStatus.valueOf(saved.status))
        }.getOrNull() }
    }.flowOn(Dispatchers.IO)

    override suspend fun save(userId: String, notice: EnrollmentNotice) = withContext(Dispatchers.IO) {
        database.withTransaction {
            check(database.authDao().currentUserId() == userId)
            require(notice.classroomId.isNotBlank())
            val key = prefix(userId) + notice.classroomId
            val previous = database.syncStateDao().get(key)?.value?.let {
                runCatching { json.decodeFromString<SavedEnrollmentNotice>(it) }.getOrNull()
            }
            val hubId = database.syncStateDao().get("hub_id")?.value
            val title = notice.title ?: previous?.takeIf { it.hubId == null || it.hubId == hubId }?.title
            database.syncStateDao().put(SyncStateEntity(key, json.encodeToString(SavedEnrollmentNotice(
                notice.classroomId, title, notice.status.name, hubId)), System.currentTimeMillis()))
        }
    }
}
