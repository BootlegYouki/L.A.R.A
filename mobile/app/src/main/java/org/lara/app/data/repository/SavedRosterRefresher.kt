package org.lara.app.data.repository

import androidx.room.withTransaction
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.remote.ClassManagementRepository

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
                else dao.updateSavedLearner(row.studentId, row.fullName, row.lrn)
            }
        }
        // Keep saved data readable while clearly reporting a partial refresh to the caller.
        if (missing) throw UnsavedRosterEntries()
    }
}
