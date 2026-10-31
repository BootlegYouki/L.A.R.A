package org.lara.app.data.sync

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.local.dao.CacheBatch
import org.lara.app.data.local.entities.*
import org.lara.app.data.remote.dto.*

/** A decoded page, its deletions and its sequence cursor commit together, or not at all. */
class SyncStore(private val db: LaraDatabase) {
    private val json = Json
    suspend fun request(userId: String): SyncPullRequestDto = withContext(Dispatchers.IO) {
        db.withTransaction { checkOwner(userId); readRequest() }
    }
    private suspend fun readRequest(): SyncPullRequestDto {
        val state = db.syncStateDao()
        val cursor = state.get("cursor")?.value?.let { requireNotNull(it.toLongOrNull()) } ?: 0L
        val hub = state.get("hub_id")?.value
        val epoch = state.get("sync_epoch")?.value?.let { requireNotNull(it.toLongOrNull()) }
        require(cursor >= 0 && (hub == null) == (epoch == null))
        return SyncPullRequestDto(cursor, hub, epoch)
    }
    internal suspend fun checkOwner(userId: String) {
        require(db.authDao().currentUserId() == userId) { "Session changed" }
    }
    internal suspend fun needsFullSnapshot(userId: String, page: SyncPullResponseDto): Boolean = withContext(Dispatchers.IO) {
        val dao = db.syncDao()
        if (page.reset) return@withContext false
        if (page.enrollments.any { it.studentId == userId && it.status == "ACTIVE" && dao.enrollment(it.classroomId, userId)?.status != "ACTIVE" })
            return@withContext true
        val suppliedClasses = page.classrooms.map { it.id }.toSet()
        val referencedClasses = page.enrollments.map { it.classroomId } + page.announcements.map { it.classroomId } +
            page.materials.map { it.classroomId } + page.assignments.map { it.classroomId } +
            page.quizzes.map { it.classroomId } + page.topics.map { it.classroomId }
        if (referencedClasses.any { it !in suppliedClasses && dao.classroom(it) == null }) return@withContext true
        if (page.comments.any { row -> page.announcements.none { it.id == row.announcementId } && dao.announcement(row.announcementId) == null }) return@withContext true
        if (page.materialChunks.any { row -> page.materials.none { it.id == row.materialId } && dao.material(row.materialId) == null }) return@withContext true
        val assignmentIds = page.assignments.map { it.id }.toSet()
        (page.submissions.map { it.assignmentId } + page.privateComments.map { it.assignmentId })
            .any { it !in assignmentIds && dao.assignment(it) == null }
    }
    suspend fun apply(userId: String, request: SyncPullRequestDto, page: SyncPullResponseDto,
        expectedState: SyncPullRequestDto = request) = withContext(Dispatchers.IO) {
        db.withTransaction {
            checkOwner(userId)
            check(readRequest() == expectedState) { "Stale sync page" }
            require(page.hubId.isNotBlank() && page.syncEpoch >= 0 && page.nextCursor >= 0)
            if (!page.reset) {
                require(page.nextCursor >= request.cursor)
                require(request.hubId == null || request.hubId == page.hubId && request.syncEpoch == page.syncEpoch)
            }
            require(!page.hasMore || page.nextCursor > if (page.reset) 0 else request.cursor)
            val own = requireNotNull(db.syncDao().user(userId))
            if (own.role == "STUDENT") require(page.enrollments.all { it.studentId == userId })
            if (page.reset) {
                request.hubId?.let { bindUnboundQueued(it) }
                resetMirror(userId)
            }
            val dao = db.syncDao()
            val snapshot = page.serverTime
            val batch = CacheBatch(
                users = page.users.map { row ->
                    val old = dao.user(row.id)
                    val lrn = row.lrnOrId.takeIf { row.id == userId || own.role == "TEACHER" }
                    row.copy(lrnOrId = lrn).toEntity(old?.createdAt ?: snapshot, snapshot)
                },
                classrooms = page.classrooms.map { row ->
                    val old = dao.classroom(row.id)
                    ClassroomEntity(row.id, row.name, row.section, row.classCode, row.teacherId,
                        row.coTeacherIds?.let { json.encodeToString(it) }, row.archivedAt, old?.createdAt ?: snapshot, snapshot)
                },
                enrollments = page.enrollments.map { EnrollmentEntity(it.id, it.classroomId, it.studentId,
                    it.status, it.joinedAt ?: it.updatedAt, it.updatedAt) },
                announcements = page.announcements.map { it.toEntity() },
                announcementComments = page.comments.map { AnnouncementCommentEntity(it.id, it.announcementId,
                    it.authorId, it.content, it.createdAt, it.updatedAt ?: it.createdAt, "SYNCED") },
                materials = page.materials.map { row ->
                    val old = dao.material(row.id)
                    MaterialEntity(row.id, row.classroomId, row.title, row.fileType, old?.mimeType, row.fileSizeBytes,
                        row.downloadUrl, old?.localFilePath, row.topicId, row.assignmentId, row.announcementId,
                        old?.createdAt ?: row.updatedAt, row.updatedAt)
                },
                materialChunks = page.materialChunks.map { MaterialChunkEntity(it.id, it.materialId, it.orderIndex,
                    it.heading, it.text, updatedAt = snapshot) },
                assignments = page.assignments.map { row ->
                    val old = dao.assignment(row.id)
                    AssignmentEntity(row.id, row.classroomId, row.title, row.instructions ?: "", row.dueDate,
                        row.allowLate ?: false, row.maxPoints, row.topicId, old?.createdAt ?: snapshot, row.updatedAt ?: snapshot)
                },
                assignmentSubmissions = page.submissions.map { row ->
                    val old = dao.submission(row.id)
                    // Empty local path means this metadata has no downloaded attachment.
                    AssignmentSubmissionEntity(row.id, row.assignmentId, row.studentId, old?.filePath ?: "",
                        row.fileType, row.submittedAt, row.score, row.teacherFeedback, row.updatedAt ?: snapshot, "SYNCED")
                },
                quizzes = page.quizzes.map { row ->
                    val old = dao.quiz(row.id)
                    QuizEntity(row.id, row.classroomId, row.title, old?.instructions, old?.topicId, row.timeLimitMinutes,
                        old?.shuffleQuestions ?: false, row.status, row.startedAt, old?.createdAt ?: snapshot, snapshot)
                },
                topics = page.topics.map { TopicEntity(it.id, it.classroomId, it.name, it.orderIndex, it.updatedAt, it.updatedAt) },
                privateComments = page.privateComments.map { PrivateCommentEntity(it.id, it.assignmentId, it.studentId,
                    it.authorId, it.content, it.createdAt, it.updatedAt ?: it.createdAt, "SYNCED") },
            )
            db.cacheDao().upsertBatch(batch)
            for (submission in page.submissions) dao.applySubmissionGrade(submission.id, submission.score, submission.teacherFeedback)
            for (grade in page.quizAttempts) {
                // GradeReceipt lacks quiz/student/start identity. Retain it even when the attempt is not here yet.
                db.syncStateDao().put(SyncStateEntity("grade_receipt:${grade.attemptId}",
                    json.encodeToString(grade), snapshot))
                dao.applyGrade(grade.attemptId, grade.score, grade.totalPoints)
            }
            deferredTombstones().plus(page.deleted).distinctBy { it.entityTable to it.entityId }.forEach { tombstone(it) }
            for ((key, value) in listOf("hub_id" to page.hubId, "sync_epoch" to page.syncEpoch.toString(),
                "cursor" to page.nextCursor.toString())) db.syncStateDao().put(SyncStateEntity(key, value, snapshot))
        }
    }

    private fun execute(sql: String, vararg args: Any) = db.openHelper.writableDatabase.execSQL(sql, args)

    private fun resetMirror(userId: String) {
        // Delete children first. Keep queued rows AND all required ancestors to prevent FK cascades losing work.
        for (table in listOf("announcement_comments", "private_comments", "assignment_submissions", "quiz_attempts"))
            execute("DELETE FROM $table WHERE sync_status != 'QUEUED_FOR_SYNC'")
        execute("DELETE FROM quiz_questions WHERE quiz_id NOT IN (SELECT quiz_id FROM quiz_attempts)")
        execute("DELETE FROM quizzes WHERE id NOT IN (SELECT quiz_id FROM quiz_attempts)")
        execute("DELETE FROM assignments WHERE id NOT IN (SELECT assignment_id FROM assignment_submissions UNION SELECT assignment_id FROM private_comments)")
        execute("DELETE FROM announcements WHERE id NOT IN (SELECT announcement_id FROM announcement_comments)")
        execute("DELETE FROM material_chunks")
        execute("DELETE FROM materials")
        execute("DELETE FROM topics")
        execute("DELETE FROM enrollments")
        execute("DELETE FROM classrooms WHERE id NOT IN (SELECT classroom_id FROM quizzes UNION SELECT classroom_id FROM assignments UNION SELECT classroom_id FROM announcements UNION SELECT classroom_id FROM ai_chat_messages)")
        execute("DELETE FROM users WHERE id != ? AND id NOT IN (SELECT author_id FROM announcement_comments UNION SELECT author_id FROM private_comments UNION SELECT student_id FROM private_comments UNION SELECT student_id FROM quiz_attempts UNION SELECT student_id FROM assignment_submissions)", userId)
        execute("DELETE FROM sync_state WHERE `key` LIKE 'grade_receipt:%' OR `key` LIKE 'deferred_tombstone:%'")
    }

    private fun bindUnboundQueued(hubId: String) {
        // An old Hub's queued work is retained on reset but never uploaded into a different school Hub.
        for (table in listOf("announcement_comments", "private_comments", "assignment_submissions", "quiz_attempts"))
            execute("INSERT OR IGNORE INTO sync_state (`key`,value,updated_at) SELECT 'queued_hub:$table:' || id, ?, updated_at FROM $table WHERE sync_status='QUEUED_FOR_SYNC'", hubId)
    }
    private fun deferredTombstones(): List<DeletedRecordDto> = db.openHelper.writableDatabase.query("SELECT value FROM sync_state WHERE `key` LIKE 'deferred_tombstone:%'").use { cursor ->
        buildList { while (cursor.moveToNext()) add(json.decodeFromString<DeletedRecordDto>(cursor.getString(0))) }
    }
    private suspend fun tombstone(row: DeletedRecordDto) {
        when (row.entityTable) {
            "materials", "material_chunks", "topics" -> execute("DELETE FROM ${row.entityTable} WHERE id=?", row.entityId)
            "announcement_comments", "private_comments" -> execute("DELETE FROM ${row.entityTable} WHERE id=? AND sync_status='SYNCED'", row.entityId)
            "announcements" -> execute("DELETE FROM announcements WHERE id=? AND NOT EXISTS (SELECT 1 FROM announcement_comments WHERE announcement_id=announcements.id AND sync_status='QUEUED_FOR_SYNC')", row.entityId)
            "assignments" -> execute("DELETE FROM assignments WHERE id=? AND NOT EXISTS (SELECT 1 FROM assignment_submissions WHERE assignment_id=assignments.id AND sync_status='QUEUED_FOR_SYNC') AND NOT EXISTS (SELECT 1 FROM private_comments WHERE assignment_id=assignments.id AND sync_status='QUEUED_FOR_SYNC')", row.entityId)
            else -> error("Unsupported tombstone entity")
        }
        val remains = db.openHelper.writableDatabase.query("SELECT 1 FROM ${row.entityTable} WHERE id=?", arrayOf(row.entityId)).use { it.moveToFirst() }
        val key = "deferred_tombstone:${row.entityTable}:${row.entityId}"
        if (remains) db.syncStateDao().put(SyncStateEntity(key, json.encodeToString(row), System.currentTimeMillis()))
        else execute("DELETE FROM sync_state WHERE `key`=?", key)
    }
}
