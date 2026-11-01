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
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.SyncStateEntity
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.remote.dto.ClassroomDto
import org.lara.app.domain.enrollment.*

@Serializable
private data class SavedClassStatus(val classroomId: String, val status: String)
@Serializable
private data class SavedClassAccess(val hub: String, val rows: List<SavedClassStatus>)

/** Only authenticated GET /classrooms results enter this cache; join/event notices cannot grant access. */
class RoomLearnerClassAccess(private val database: LaraDatabase, private val endpoint: () -> HubEndpoint?) {
    private fun key(userId: String) = "rest_class_access:$userId"
    suspend fun save(userId: String, rows: List<ClassroomDto>, hub: String = requireNotNull(endpoint()).restBase) = withContext(Dispatchers.IO) {
        database.withTransaction {
            check(endpoint()?.restBase == hub)
            check(database.authDao().currentUserId() == userId)
            check(database.authDao().user(userId)?.role == "STUDENT")
            require(rows.map { it.id }.distinct().size == rows.size)
            val statuses = rows.map { row ->
                SavedClassStatus(row.id, EnrollmentStatus.valueOf(requireNotNull(row.enrollmentStatus)).name)
            }
            val now = System.currentTimeMillis()
            database.cacheDao().upsertBatch(CacheBatch(classrooms = rows.map {
                it.savedEntity(database.rosterDao().savedClassroom(it.id), now)
            }))
            database.syncStateDao().put(SyncStateEntity(key(userId), Json.encodeToString(SavedClassAccess(hub, statuses)), now))
        }
    }
    fun classes(userId: String) = combine(database.classroomDao().observeStored(),
        database.syncStateDao().observe(key(userId)), database.syncStateDao().observe("current_user_id")) { classes, saved, owner ->
        if (owner?.value != userId) return@combine null
        val access = saved?.value?.let { runCatching { Json.decodeFromString<SavedClassAccess>(it) }.getOrNull() }
            ?.takeIf { it.hub == endpoint()?.restBase } ?: return@combine null
        access.rows.mapNotNull { row ->
            val status = runCatching { EnrollmentStatus.valueOf(row.status) }.getOrNull() ?: return@mapNotNull null
            val classroom = classes.firstOrNull { it.id == row.classroomId } ?: return@mapNotNull null
            LearnerClass(classroom.toDomain(), restStatus = status)
        }
    }.flowOn(Dispatchers.IO)
}
