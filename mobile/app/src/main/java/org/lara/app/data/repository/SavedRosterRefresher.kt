package org.lara.app.data.repository

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.ClassManagementRepository
import org.lara.app.data.remote.dto.ClassroomDto

/** REST snapshots use server identities and never advance the delta-sync cursor. */
class SavedRosterRefresher(private val database: LaraDatabase, private val remote: ClassManagementRepository,
    private val userId: String) {
    private suspend fun checkTeacher() {
        check(database.authDao().currentUserId() == userId)
        check(database.authDao().user(userId)?.role == "TEACHER")
    }
    suspend fun refreshClasses() {
        val rows = remote.classrooms()
        database.withTransaction {
            checkTeacher()
            require(rows.all { it.teacherId == userId || userId in it.coTeacherIds.orEmpty() })
            val now = System.currentTimeMillis()
            database.cacheDao().upsertBatch(CacheBatch(classrooms = rows.map { row ->
                row.savedEntity(database.rosterDao().savedClassroom(row.id), now)
            }))
        }
    }
    suspend fun refresh(classroomId: String) {
        val rows = remote.roster(classroomId)
        database.withTransaction {
            checkTeacher()
            val dao = database.rosterDao()
            val classroom = requireNotNull(dao.savedClassroom(classroomId))
            require(classroom.archivedAt == null && (classroom.teacherId == userId ||
                userId in Json.decodeFromString<List<String>>(classroom.coTeacherIdsJson ?: "[]")))
            require(rows.map { it.studentId }.distinct().size == rows.size)
            require(rows.map { it.enrollmentId }.distinct().size == rows.size)
            val now = System.currentTimeMillis()
            val users = rows.map { row ->
                require(row.enrollmentId.isNotBlank() && row.studentId.isNotBlank())
                val old = database.authDao().user(row.studentId)
                require(old == null || old.role == "STUDENT")
                UserEntity(row.studentId, row.lrn, row.fullName, "STUDENT", old?.createdAt ?: row.joinedAt ?: now, now)
            }
            val enrollments = rows.map { row ->
                val old = dao.savedEnrollment(classroomId, row.studentId)
                EnrollmentEntity(row.enrollmentId, classroomId, row.studentId, row.status.name,
                    row.joinedAt ?: old?.joinedAt ?: now, now)
            }
            database.cacheDao().upsertBatch(CacheBatch(users = users, enrollments = enrollments))
        }
    }
    suspend fun refreshIfActive(classroomId: String) {
        val row = database.rosterDao().savedClassroom(classroomId) ?: return
        if (row.archivedAt == null) refresh(classroomId)
    }
}

internal fun ClassroomDto.savedEntity(old: ClassroomEntity?, now: Long) = ClassroomEntity(
    id, name, section, classCode, teacherId, coTeacherIds?.let { Json.encodeToString(it) },
    archivedAt, old?.createdAt ?: now, now,
)
