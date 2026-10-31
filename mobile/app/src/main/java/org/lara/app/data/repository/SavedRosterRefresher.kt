package org.lara.app.data.repository

import androidx.room.withTransaction
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.remote.ClassManagementRepository
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.UserEntity

class UnsavedRosterEntries : Exception()

/** 2.4 refreshes existing identities only. New authoritative enrollment rows belong to 2.5. */
class SavedRosterRefresher(private val database: LaraDatabase, private val remote: ClassManagementRepository) {
    suspend fun refresh(classroomId: String) {
        val rows = remote.roster(classroomId)
        var missing = false
        database.withTransaction {
            val dao = database.rosterDao()
            for (row in rows) {
                if (dao.updateSavedStatus(classroomId, row.studentId, row.status.name) == 0) missing = true
                else {
                    val old = database.syncDao().user(row.studentId)
                    require(old == null || old.role == "STUDENT")
                    val snapshot = System.currentTimeMillis()
                    database.cacheDao().upsertBatch(CacheBatch(users = listOf(UserEntity(row.studentId,
                        row.lrn, row.fullName, "STUDENT", old?.createdAt ?: snapshot, snapshot))))
                }
            }
        }
        // Keep saved data readable while clearly reporting a partial refresh to the caller.
        if (missing) throw UnsavedRosterEntries()
    }
}
